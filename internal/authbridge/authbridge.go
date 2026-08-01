// Package authbridge 实现预认证服务：在玩家进服前用 hasJoined 撮合
// 验证 accessToken 有效性，据此提前签发 frp 玩家令牌与房间凭证。
//
// 这是方案 1B：accessToken 全程只在「prefetch 程序 ↔ 皮肤站」之间，
// authbridge 扮演 MC 服务端角色去 hasJoined，碰不到 token——与 MC
// 原生进服验证同款安全模型。
//
// authbridge 无状态：serverId 是随机字符串，prefetch 拿去让玩家本机
// 调 /join 报到，皮肤站记录在案；authbridge 随后用同一个 serverId 调
// /hasJoined 查证。状态全在皮肤站，authbridge 重启不影响任何在途请求。
package authbridge

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/ripplecraft/xtcpinmc/internal/authplugin"
	"github.com/ripplecraft/xtcpinmc/internal/credfile"
)

// 令牌有效期默认值：与服务端 mod 的默认 tokenTtlDays=30 一致。
// 预认证签发的令牌会随凭证缓存，覆盖玩家两次游玩的间隔足够。
const defaultTokenTTL = 30 * 24 * time.Hour

// Config 是预认证服务的全部策略。
type Config struct {
	// SigningKey 与 authplugin 的 -key、服务端 mod 的 tokenSigningKey 同值。
	SigningKey string
	// AuthServer 皮肤站 API root，如 https://skin.example.com/api/yggdrasil
	AuthServer string
	// RoomParams 房间凭证参数（frp-xtcp 键名：server/serverPort/token/stun/room/secret）。
	// 必须与 serve 端实际注册的参数同源，否则打洞时密钥不匹配。
	RoomParams map[string]string
	// PunchTimeoutMs 服务端建议的打洞超时；0 表示由客户端配置决定。
	PunchTimeoutMs int
	// TokenTTL 签发令牌的有效期；非正值回退默认 30 天（与 mod 的 tokenTtlDays 同语义）。
	TokenTTL time.Duration
	// Logf 输出决策日志；nil 表示静默。
	Logf func(format string, args ...any)
}

// NewHandler 构造 HTTP handler。
func NewHandler(cfg Config) http.Handler {
	if cfg.TokenTTL <= 0 {
		cfg.TokenTTL = defaultTokenTTL
	}
	return &handler{cfg: cfg}
}

type handler struct {
	cfg Config
}

// 三个 JSON 结构对应前后端约定的协议。
type prefetchRequest struct {
	Username string `json:"username"`
	UUID     string `json:"uuid"`
}

type prefetchResponse struct {
	ServerID string `json:"serverId"`
}

type confirmRequest struct {
	ServerID string `json:"serverId"`
	Username string `json:"username"`
	UUID     string `json:"uuid"`
}

type confirmResponse struct {
	OK         bool   `json:"ok"`
	Reason     string `json:"reason,omitempty"`
	Credential string `json:"credential,omitempty"` // base64 编码的凭证字节
	Room       string `json:"room,omitempty"`
	BackendID  string `json:"backendId,omitempty"`
}

func (h *handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	switch r.URL.Path {
	case "/prefetch":
		h.handlePrefetch(w, r)
	case "/confirm":
		h.handleConfirm(w, r)
	default:
		http.NotFound(w, r)
	}
}

// handlePrefetch 生成随机 serverId 返回。不存状态——皮肤站会在 join 时记录。
func (h *handler) handlePrefetch(w http.ResponseWriter, r *http.Request) {
	var req prefetchRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	if req.Username == "" || req.UUID == "" {
		writeJSON(w, http.StatusBadRequest, confirmResponse{OK: false, Reason: "username 和 uuid 不能为空"})
		return
	}
	serverID, err := randomServerID()
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, confirmResponse{OK: false, Reason: "生成 serverId 失败: " + err.Error()})
		return
	}
	h.logf("prefetch: 玩家 %s (%s) 领取 serverId", req.Username, req.UUID)
	writeJSON(w, http.StatusOK, prefetchResponse{ServerID: serverID})
}

// handleConfirm 调皮肤站 hasJoined 查证，成功则签发令牌 + 组装凭证。
func (h *handler) handleConfirm(w http.ResponseWriter, r *http.Request) {
	var req confirmRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	if req.ServerID == "" || req.Username == "" || req.UUID == "" {
		writeJSON(w, http.StatusBadRequest, confirmResponse{OK: false, Reason: "serverId/username/uuid 不能为空"})
		return
	}

	// 扮演 MC 服务端角色去皮肤站查证。皮肤站会查「有没有人持 accessToken
	// 用这个 serverId 来 /join 报过到」——有则返回 Profile，证明 token 有效。
	profile, err := h.hasJoined(r.Context(), req.Username, req.ServerID)
	if err != nil {
		h.logf("confirm: %s hasJoined 失败: %v", req.Username, err)
		writeJSON(w, http.StatusOK, confirmResponse{OK: false, Reason: "验证失败: " + err.Error()})
		return
	}
	// 防「用别人的 serverId + 自己的 username」骗凭证：比对 uuid。
	// hasJoined 返回的 id 是无连字符的，请求侧可能带连字符，统一去连字符比对。
	if norm(profile.ID) != norm(req.UUID) {
		h.logf("confirm: %s uuid 不匹配（请求 %s，皮肤站 %s）", req.Username, req.UUID, profile.ID)
		writeJSON(w, http.StatusOK, confirmResponse{OK: false, Reason: "uuid 不匹配"})
		return
	}

	// 签发玩家令牌，user 用 prefetch 传入的 uuid（与 frpc metas.user 一致）。
	expiry := time.Now().Add(h.cfg.TokenTTL).Unix()
	userToken := authplugin.IssueToken(h.cfg.SigningKey, req.UUID, expiry)

	cred := h.assembleCredential(req.UUID, userToken)
	data := cred.Encode()

	h.logf("confirm: %s (%s) 验证通过，签发令牌（有效期至 %s），房间 %s",
		req.Username, req.UUID, time.Unix(expiry, 0).Format("2006-01-02"), cred.Room())

	writeJSON(w, http.StatusOK, confirmResponse{
		OK:         true,
		Credential: base64.StdEncoding.EncodeToString(data),
		Room:       cred.Room(),
		BackendID:  cred.BackendID,
	})
}

// assembleCredential 把房间参数与玩家身份组装成凭证。
func (h *handler) assembleCredential(user, userToken string) credfile.Credentials {
	// 保序：房间参数先拷贝，再追加玩家身份（与 Java 侧 withExtraParams 同纪律）。
	params := make([]credfile.KV, 0, len(h.cfg.RoomParams)+2)
	// RoomParams 是 map，顺序不保证；凭证落盘后顺序不影响 WarmupController 解析，
	// 它只按 key 取值。此处保持简洁，不做额外排序。
	for k, v := range h.cfg.RoomParams {
		params = append(params, credfile.KV{Key: k, Value: v})
	}
	params = append(params,
		credfile.KV{Key: "user", Value: user},
		credfile.KV{Key: "userToken", Value: userToken},
	)
	return credfile.Credentials{
		BackendID:      "frp-xtcp",
		PunchTimeoutMs: h.cfg.PunchTimeoutMs,
		Params:         params,
	}
}

// profile 是 hasJoined 返回的玩家档案，只取需要的字段。
type profile struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

// hasJoined 调皮肤站的 /session/minecraft/hasJoined。
// 路径与 Mojang 原生一致，authlib-injector 会把 sessionserver.mojang.com
// 替换成皮肤站，但 authbridge 是独立进程不经字节码替换，需手动拼完整路径。
func (h *handler) hasJoined(ctx context.Context, username, serverID string) (*profile, error) {
	u := strings.TrimRight(h.cfg.AuthServer, "/") +
		"/sessionserver/session/minecraft/hasJoined?username=" +
		url.QueryEscape(username) + "&serverId=" + url.QueryEscape(serverID)

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, fmt.Errorf("构造请求: %w", err)
	}
	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("请求皮肤站: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		// 204/403 都表示「没人 join 过」——可能是 prefetch 没调 /join，
		// 或调了但 accessToken 无效被皮肤站拒了。
		return nil, fmt.Errorf("皮肤站返回 %d（玩家未 join 或 token 无效）", resp.StatusCode)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("读取响应: %w", err)
	}
	var p profile
	if err := json.Unmarshal(body, &p); err != nil {
		return nil, fmt.Errorf("解析 Profile: %w", err)
	}
	if p.ID == "" {
		return nil, fmt.Errorf("Profile 缺少 id")
	}
	return &p, nil
}

func (h *handler) logf(format string, args ...any) {
	if h.cfg.Logf != nil {
		h.cfg.Logf(format, args...)
	}
}

// randomServerID 生成 32 字符的 hex 随机串，与 MC 服务端的 serverId 用途一致。
func randomServerID() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

// norm 去掉 uuid 的连字符，用于比对（请求侧可能带连字符，皮肤站返回的不带）。
func norm(uuid string) string {
	return strings.ReplaceAll(uuid, "-", "")
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
