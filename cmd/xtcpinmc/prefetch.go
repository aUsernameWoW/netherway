package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/ripplecraft/xtcpinmc/internal/credfile"
)

// prefetch 子命令在玩家机器运行（通常由启动器 Pre-launch 调用）：
// 经 authbridge 做预认证，提前拿到 frp 玩家令牌与房间凭证并落盘。
// 游戏启动后 WarmupController 读缓存凭证预热打洞，玩家第一次进服即直连。
//
// 流程：
//  1. 向 authbridge 领取 serverId
//  2. 拿 accessToken 去皮肤站 /join 报到（token 只在本机↔皮肤站）
//  3. 让 authbridge 去 /hasJoined 查证，换回凭证
//  4. 凭证写进缓存目录，格式与 Java 侧 CredentialCache 兼容
func cmdPrefetch(args []string) error {
	fs := flag.NewFlagSet("prefetch", flag.ExitOnError)
	bridge := fs.String("bridge", "", "authbridge 地址，如 http://authbridge.example.com:7201")
	authServer := fs.String("authserver", "", "皮肤站 API root，如 https://skin.example.com/api/yggdrasil")
	token := fs.String("token", os.Getenv("XTCPINMC_ACCESS_TOKEN"),
		"启动器登录后拿到的 accessToken；\n"+
			"也可经环境变量 XTCPINMC_ACCESS_TOKEN 传入（避免出现在进程列表里）")
	uuid := fs.String("uuid", "", "玩家 UUID（带不带连字符均可）")
	username := fs.String("username", "", "玩家名")
	cacheDir := fs.String("cache-dir", "",
		"凭证缓存目录，即 mod 的 .minecraft/xtcpinmc/credentials")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *bridge == "" || *authServer == "" || *token == "" ||
		*uuid == "" || *username == "" || *cacheDir == "" {
		return errors.New("bridge/authserver/token/uuid/username/cache-dir 均不能为空")
	}

	client := &http.Client{Timeout: 15 * time.Second}

	// 1. 领取 serverId
	serverID, err := doPrefetch(client, *bridge, *username, *uuid)
	if err != nil {
		return err
	}

	// 2. 拿 accessToken 去皮肤站 join —— token 只在本机↔皮肤站，不经过 authbridge
	if err := doJoin(client, *authServer, *token, *uuid, serverID); err != nil {
		return err
	}

	// 3. authbridge 去 hasJoined 查证，换回凭证
	cred, room, backendID, err := doConfirm(client, *bridge, serverID, *username, *uuid)
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
	fmt.Printf("预拉取成功：房间 %q，凭证已写入 %s\n", room, target)
	return nil
}

// doPrefetch 向 authbridge 领取 serverId。
func doPrefetch(client *http.Client, bridge, username, uuid string) (string, error) {
	body, _ := json.Marshal(map[string]string{"username": username, "uuid": uuid})
	resp, err := client.Post(bridge+"/prefetch", "application/json", bytes.NewReader(body))
	if err != nil {
		return "", fmt.Errorf("请求 authbridge /prefetch: %w", err)
	}
	defer resp.Body.Close()
	var out struct {
		ServerID string `json:"serverId"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return "", fmt.Errorf("解析 /prefetch 响应: %w", err)
	}
	if out.ServerID == "" {
		return "", errors.New("authbridge 返回空 serverId")
	}
	return out.ServerID, nil
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
