package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/aUsernameWoW/netherway/internal/authbridge"
	"github.com/aUsernameWoW/netherway/internal/config"
	"github.com/aUsernameWoW/netherway/internal/credfile"
)

// prefetch 子命令在玩家机器运行（mod 在 FML 加载期调用，启动器 Pre-launch
// 也仍可用）：经 authbridge 做预认证，提前拿到 frp 玩家令牌与房间凭证并落盘。
// 游戏启动后 WarmupController 读缓存凭证预热打洞，玩家第一次进服即直连。
//
// 流程：
//  0. -bridge 给了多个候选时，逐个 GET /info 探测，取第一个自报
//     netherway-authbridge 的（候选来自 mod 对 server.dat 的扫描推导）
//  1. 向 authbridge 领取 serverId（响应顺带告知皮肤站地址）
//  2. 拿 accessToken 去皮肤站 /join 报到（token 只在本机↔皮肤站）
//  3. 让 authbridge 去 /hasJoined 查证，换回凭证
//  4. 凭证写进缓存目录，格式与 Java 侧 CredentialCache 兼容
func cmdPrefetch(args []string) error {
	fs := flag.NewFlagSet("prefetch", flag.ExitOnError)
	var bridges multiFlag
	fs.Var(&bridges, "bridge",
		"authbridge 地址，如 https://authbridge.example.com（应经 TLS，凭证会在响应里回传）。\n"+
			"可重复给出多个候选：逐个 GET /info 探测，用第一个应答者")
	authServer := fs.String("authserver", config.DefaultAuthServer,
		"皮肤站 API root，如 https://skin.example.com/api/yggdrasil；\n"+
			"留空时用 authbridge /prefetch 响应里告知的地址")
	token := fs.String("token", os.Getenv("NETHERWAY_ACCESS_TOKEN"),
		"启动器登录后拿到的 accessToken；\n"+
			"也可经环境变量 NETHERWAY_ACCESS_TOKEN 传入（避免出现在进程列表里）")
	uuid := fs.String("uuid", "", "玩家 UUID（带不带连字符均可）")
	username := fs.String("username", "", "玩家名")
	cacheDir := fs.String("cache-dir", "",
		"凭证缓存目录，即 mod 的 .minecraft/netherway/credentials")
	discoverTimeout := fs.Int("discover-timeout", 5,
		"单个候选的 /info 探测超时秒数（候选多来自 server.dat 扫描，无关主机要能快速跳过）")
	insecureHTTP := fs.Bool("insecure-http", false,
		"允许对非回环地址走明文 http（仅调试用；凭证与 accessToken 会明文过网）")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if len(bridges) == 0 && config.DefaultAuthBridge != "" {
		bridges = append(bridges, config.DefaultAuthBridge)
	}
	if len(bridges) == 0 || *token == "" || *uuid == "" || *username == "" || *cacheDir == "" {
		return errors.New("bridge/token/uuid/username/cache-dir 均不能为空")
	}

	client := &http.Client{Timeout: 15 * time.Second}

	// 0. 候选里选定 bridge。TLS 校验在探测之前：凭证与 accessToken 都会
	// 过这条链路，明文 http 一次配置失误就是全泄露——强制 https，回环调试豁免。
	bridge, err := pickBridge(bridges, time.Duration(*discoverTimeout)*time.Second, *insecureHTTP)
	if err != nil {
		return err
	}

	// 1. 领取 serverId（响应顺带告知皮肤站地址）
	serverID, bridgeAuthServer, err := doPrefetch(client, bridge, *username, *uuid)
	if err != nil {
		return err
	}
	skinAPI := *authServer
	if skinAPI == "" {
		skinAPI = bridgeAuthServer
	}
	if skinAPI == "" {
		return errors.New("皮肤站地址为空：用 -authserver 指定，或升级 authbridge" +
			"（新版会在 /prefetch 响应里告知）")
	}
	if err := requireTLS("authserver", skinAPI, *insecureHTTP); err != nil {
		return err
	}

	// 2. 拿 accessToken 去皮肤站 join —— token 只在本机↔皮肤站，不经过 authbridge
	if err := doJoin(client, skinAPI, *token, *uuid, serverID); err != nil {
		return err
	}

	// 3. authbridge 去 hasJoined 查证，换回凭证
	cred, room, backendID, err := doConfirm(client, bridge, serverID, *username, *uuid)
	if err != nil {
		return err
	}

	// 4. 落盘
	data, err := base64.StdEncoding.DecodeString(cred)
	if err != nil {
		return fmt.Errorf("解码凭证: %w", err)
	}
	target, err := credfile.WriteRaw(data, backendID, room, *cacheDir)
	if err != nil {
		return err
	}
	fmt.Printf("预拉取成功：房间 %q（经 %s），凭证已写入 %s\n", room, bridge, target)
	return nil
}

// multiFlag 让 -bridge 可以重复给出。
type multiFlag []string

func (m *multiFlag) String() string { return strings.Join(*m, ",") }

func (m *multiFlag) Set(v string) error {
	*m = append(*m, v)
	return nil
}

// pickBridge 从候选里选定要用的 authbridge。
//
// 单个候选直接采用（兼容未提供 /info 的旧版 authbridge，也少一次往返）；
// 多个候选时逐个 GET /info，认下第一个自报 netherway-authbridge 的。
// 候选多来自 mod 对 server.dat 的扫描推导，大部分主机上根本没有这个服务，
// 探测失败是预期路径，只在全军覆没时才报错。
func pickBridge(bridges []string, timeout time.Duration, insecure bool) (string, error) {
	if len(bridges) == 1 {
		if err := requireTLS("bridge", bridges[0], insecure); err != nil {
			return "", err
		}
		return bridges[0], nil
	}
	probe := &http.Client{Timeout: timeout}
	var tried []string
	for _, b := range bridges {
		if err := requireTLS("bridge", b, insecure); err != nil {
			fmt.Fprintf(os.Stderr, "跳过候选 %s: %v\n", b, err)
			continue
		}
		if err := probeInfo(probe, b); err != nil {
			tried = append(tried, fmt.Sprintf("%s（%v）", b, err))
			continue
		}
		fmt.Fprintf(os.Stderr, "发现 authbridge: %s\n", b)
		return b, nil
	}
	return "", fmt.Errorf("所有候选都未应答 /info: %s", strings.Join(tried, "; "))
}

// probeInfo 探测一个候选的 GET /info，不是 netherway-authbridge 则报错。
func probeInfo(client *http.Client, bridge string) error {
	resp, err := client.Get(strings.TrimRight(bridge, "/") + "/info")
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	var out struct {
		Service string `json:"service"`
	}
	// 限读几 KB 就够了：/info 的合法响应只有几十字节，别让一个
	// 恰好在该路径上返回大文件的无关站点撑爆内存
	if err := json.NewDecoder(io.LimitReader(resp.Body, 4<<10)).Decode(&out); err != nil {
		return fmt.Errorf("响应不是 JSON: %w", err)
	}
	if out.Service != authbridge.InfoService {
		return fmt.Errorf("service 字段是 %q", out.Service)
	}
	return nil
}

// doPrefetch 向 authbridge 领取 serverId；新版 authbridge 会在响应里
// 顺带告知皮肤站地址（authServer），旧版没有则返回空串。
func doPrefetch(client *http.Client, bridge, username, uuid string) (string, string, error) {
	body, _ := json.Marshal(map[string]string{"username": username, "uuid": uuid})
	resp, err := client.Post(bridge+"/prefetch", "application/json", bytes.NewReader(body))
	if err != nil {
		return "", "", fmt.Errorf("请求 authbridge /prefetch: %w", err)
	}
	defer resp.Body.Close()
	// 出错时 authbridge 以非 200 状态 + {"reason":...} 回复，原因要透传出来，
	// 不能吞成一句「空 serverId」——prefetch 的日志是排查时的唯一线索。
	var out struct {
		ServerID   string `json:"serverId"`
		AuthServer string `json:"authServer"`
		Reason     string `json:"reason"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return "", "", fmt.Errorf("解析 /prefetch 响应（HTTP %d）: %w", resp.StatusCode, err)
	}
	if resp.StatusCode != http.StatusOK || out.ServerID == "" {
		reason := out.Reason
		if reason == "" {
			reason = fmt.Sprintf("HTTP %d，且无 serverId", resp.StatusCode)
		}
		return "", "", errors.New("authbridge /prefetch 拒绝: " + reason)
	}
	return out.ServerID, out.AuthServer, nil
}

// doJoin 拿 accessToken 去皮肤站报到。这是唯一接触 token 的一步。
// selectedProfile 要求无连字符的 uuid（Yggdrasil 规范）。
func doJoin(client *http.Client, authServer, token, uuid, serverID string) error {
	body, _ := json.Marshal(map[string]string{
		"accessToken":     token,
		"selectedProfile": normUUID(uuid),
		"serverId":        serverID,
	})
	url := strings.TrimRight(authServer, "/") + "/sessionserver/session/minecraft/join"
	resp, err := client.Post(url, "application/json", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("请求皮肤站 /join: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		respBody, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("皮肤站 /join 返回 %d: %s", resp.StatusCode, string(respBody))
	}
	return nil
}

// doConfirm 让 authbridge 去 hasJoined 查证，换回凭证字节。
func doConfirm(client *http.Client, bridge, serverID, username, uuid string) (cred, room, backendID string, err error) {
	body, _ := json.Marshal(map[string]string{
		"serverId": serverID,
		"username": username,
		"uuid":     uuid,
	})
	resp, err := client.Post(bridge+"/confirm", "application/json", bytes.NewReader(body))
	if err != nil {
		return "", "", "", fmt.Errorf("请求 authbridge /confirm: %w", err)
	}
	defer resp.Body.Close()
	var out struct {
		OK         bool   `json:"ok"`
		Reason     string `json:"reason"`
		Credential string `json:"credential"`
		Room       string `json:"room"`
		BackendID  string `json:"backendId"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return "", "", "", fmt.Errorf("解析 /confirm 响应: %w", err)
	}
	if !out.OK {
		return "", "", "", fmt.Errorf("预认证失败: %s", out.Reason)
	}
	return out.Credential, out.Room, out.BackendID, nil
}

// normUUID 去掉连字符，Yggdrasil 的 selectedProfile.id 用无连字符格式。
func normUUID(uuid string) string {
	return strings.ReplaceAll(uuid, "-", "")
}

// requireTLS 断言地址走 https。回环地址（本机调试、端口转发）豁免；
// 其余明文只能经 -insecure-http 显式放行，绝不默认。
func requireTLS(name, raw string, insecure bool) error {
	u, err := url.Parse(raw)
	if err != nil {
		return fmt.Errorf("解析 %s 地址 %q: %w", name, raw, err)
	}
	if u.Scheme == "https" {
		return nil
	}
	host := u.Hostname()
	if host == "localhost" {
		return nil
	}
	if ip := net.ParseIP(host); ip != nil && ip.IsLoopback() {
		return nil
	}
	if insecure {
		return nil
	}
	return fmt.Errorf("%s 地址 %q 不是 https：凭证/令牌会明文过网；"+
		"请改用 https，本机调试可用回环地址，或加 -insecure-http 显式放行",
		name, raw)
}
