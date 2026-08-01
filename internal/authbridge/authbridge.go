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
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/aUsernameWoW/netherway/internal/authplugin"
	"github.com/aUsernameWoW/netherway/internal/credfile"
)

// 令牌有效期默认值：与服务端 mod 的默认 tokenTtlDays=30 一致。
// 预认证签发的令牌会随凭证缓存，覆盖玩家两次游玩的间隔足够。
const defaultTokenTTL = 30 * 24 * time.Hour

// maxBodyBytes 限制请求体大小。两个端点的合法请求都只有三个短字符串，
// 4 KiB 绰绰有余；不设限的话一个 GB 级 JSON 就能撑爆内存——
// 这是全项目唯一面向玩家侧公网的进程，必须按被打的姿态设防。
const maxBodyBytes = 4 << 10

// 默认限流参数。一次正常的预拉取 = /prefetch + /confirm 共 2 个请求，
// 每分钟 30 个对玩家侧的重试绰绰有余，对滥用则是数量级的收敛。
const (
	defaultPerIPPerMinute        = 30
	defaultMaxConcurrentConfirms = 16
)

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
	// PerIPPerMinute 每来源 IP 每分钟请求数上限；非正值回退默认 30。
	PerIPPerMinute int
	// MaxConcurrentConfirms 并发 hasJoined 外呼上限；非正值回退默认 16。
	// confirm 每次触发一次最长 10s 的上游请求，不设上限等于把
	// 「打皮肤站 + 耗尽本机 fd」的放大器免费送给任何人。
	MaxConcurrentConfirms int
	// TrustProxyHeader 为 true 时取 X-Forwarded-For 首跳作为来源 IP。
	// 只有部署在反代之后（直连来源就是反代）才能开：直接暴露时开着它，
	// 任何人都能伪造头绕过限流。
	TrustProxyHeader bool
	// Logf 输出决策日志；nil 表示静默。
	Logf func(format string, args ...any)
}

// NewHandler 构造 HTTP handler。
func NewHandler(cfg Config) http.Handler {
	if cfg.TokenTTL <= 0 {
		cfg.TokenTTL = defaultTokenTTL
	}
	if cfg.PerIPPerMinute <= 0 {
		cfg.PerIPPerMinute = defaultPerIPPerMinute
	}
	if cfg.MaxConcurrentConfirms <= 0 {
		cfg.MaxConcurrentConfirms = defaultMaxConcurrentConfirms
	}
	return &handler{
		cfg: cfg,
		// 复用连接：每请求新建 Client 会让每次 confirm 都重握手，
		// 高峰期白白消耗皮肤站与本机的 fd
		client:     &http.Client{Timeout: 10 * time.Second},
		limiter:    newIPLimiter(cfg.PerIPPerMinute),
		confirmSem: make(chan struct{}, cfg.MaxConcurrentConfirms),
	}
}

type handler struct {
	cfg        Config
	client     *http.Client
	limiter    *ipLimiter
	confirmSem chan struct{}
}

// 这些 JSON 结构对应前后端约定的协议。
type prefetchRequest struct {
	Username string `json:"username"`
	UUID     string `json:"uuid"`
}

type prefetchResponse struct {
	ServerID string `json:"serverId"`
	// AuthServer 把皮肤站 API root 告诉客户端：authbridge 本来就配置了它，
	// 客户端因此只需知道 bridge 一个地址（mod 内建预取的前提）。
	AuthServer string `json:"authServer,omitempty"`
}

// infoResponse 是 GET /info 的发现信标：mod 扫描 server.dat 推导候选地址后，
// 靠这个响应识别「这个主机上确实挂着 netherway 的 authbridge」。
// 无副作用、内容固定，被无关服务器 404/超时掉都是预期路径。
type infoResponse struct {
	Service  string `json:"service"`
	Protocol int    `json:"protocol"`
}

// InfoService 是 /info 里 service 字段的固定值，prefetch 侧按它识别。
const InfoService = "netherway-authbridge"

// InfoProtocol 是发现协议版本，将来不兼容变更时递增。
const InfoProtocol = 1

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
	ip := clientIP(r, h.cfg.TrustProxyHeader)
	// /info 也在限流之内：它是给 mod 的发现探测用的，正常一次启动只有
	// 每个候选主机一发，配额绰绰有余；不豁免则少一条可白嫖的路径。
	if !h.limiter.allow(ip) {
		h.logf("限流: %s 对 %s 请求过于频繁", ip, r.URL.Path)
		writeJSON(w, http.StatusTooManyRequests, confirmResponse{OK: false, Reason: "请求过于频繁，稍后重试"})
		return
	}
	if r.URL.Path == "/info" {
		if r.Method != http.MethodGet {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		writeJSON(w, http.StatusOK, infoResponse{Service: InfoService, Protocol: InfoProtocol})
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
	switch r.URL.Path {
	case "/prefetch":
		h.handlePrefetch(w, r, ip)
	case "/confirm":
		h.handleConfirm(w, r, ip)
	default:
		http.NotFound(w, r)
	}
}

// decodeBody 解出请求体，区分「超限」与「不是合法 JSON」。
// 失败时已写好响应，调用方直接 return。
func decodeBody(w http.ResponseWriter, r *http.Request, v any) bool {
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			writeJSON(w, http.StatusRequestEntityTooLarge, confirmResponse{OK: false, Reason: "请求体超限"})
		} else {
			writeJSON(w, http.StatusBadRequest, confirmResponse{OK: false, Reason: "请求体不是合法 JSON"})
		}
		return false
	}
	return true
}

// handlePrefetch 生成随机 serverId 返回。不存状态——皮肤站会在 join 时记录。
func (h *handler) handlePrefetch(w http.ResponseWriter, r *http.Request, ip string) {
	var req prefetchRequest
	if !decodeBody(w, r, &req) {
		return
	}
	if reason := validateIdentity(req.Username, req.UUID); reason != "" {
		// 不合法的值不回显进日志——它们正是可能藏着换行/控制字符的输入
		h.logf("prefetch: 拒绝来自 %s 的请求（%s）", ip, reason)
		writeJSON(w, http.StatusBadRequest, confirmResponse{OK: false, Reason: reason})
		return
	}
	serverID, err := randomServerID()
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, confirmResponse{OK: false, Reason: "生成 serverId 失败: " + err.Error()})
		return
	}
	h.logf("prefetch: 玩家 %s (%s) 从 %s 领取 serverId", req.Username, req.UUID, ip)
	writeJSON(w, http.StatusOK, prefetchResponse{ServerID: serverID, AuthServer: h.cfg.AuthServer})
}

// handleConfirm 调皮肤站 hasJoined 查证，成功则签发令牌 + 组装凭证。
func (h *handler) handleConfirm(w http.ResponseWriter, r *http.Request, ip string) {
	var req confirmRequest
	if !decodeBody(w, r, &req) {
		return
	}
	if reason := validateIdentity(req.Username, req.UUID); reason != "" {
		h.logf("confirm: 拒绝来自 %s 的请求（%s）", ip, reason)
		writeJSON(w, http.StatusBadRequest, confirmResponse{OK: false, Reason: reason})
		return
	}
	if !validServerID(req.ServerID) {
		h.logf("confirm: 拒绝来自 %s 的请求（serverId 不合法）", ip)
		writeJSON(w, http.StatusBadRequest, confirmResponse{OK: false, Reason: "serverId 不合法"})
		return
	}

	// 并发上限：每个 confirm 都是一次最长 10s 的上游外呼，洪峰时宁可
	// 让个别玩家稍后重试，也不能把皮肤站打挂或耗尽本机连接
	select {
	case h.confirmSem <- struct{}{}:
		defer func() { <-h.confirmSem }()
	default:
		h.logf("confirm: 并发已满，拒绝来自 %s 的请求", ip)
		writeJSON(w, http.StatusServiceUnavailable, confirmResponse{OK: false, Reason: "服务繁忙，稍后重试"})
		return
	}

	// 扮演 MC 服务端角色去皮肤站查证。皮肤站会查「有没有人持 accessToken
	// 用这个 serverId 来 /join 报过到」——有则返回 Profile，证明 token 有效。
	profile, err := h.hasJoined(r.Context(), req.Username, req.ServerID)
	if err != nil {
		h.logf("confirm: %s (%s) hasJoined 失败: %v", req.Username, ip, err)
		writeJSON(w, http.StatusOK, confirmResponse{OK: false, Reason: "验证失败: " + err.Error()})
		return
	}
	// 防「用别人的 serverId + 自己的 username」骗凭证：比对 uuid。
	// hasJoined 返回的 id 是无连字符的，请求侧可能带连字符，统一去连字符比对。
	if norm(profile.ID) != norm(req.UUID) {
		h.logf("confirm: %s (%s) uuid 不匹配（请求 %s，皮肤站 %s）",
			req.Username, ip, req.UUID, sanitizeLog(profile.ID))
		writeJSON(w, http.StatusOK, confirmResponse{OK: false, Reason: "uuid 不匹配"})
		return
	}

	// 签发玩家令牌，user 用 prefetch 传入的 uuid（与 frpc metas.user 一致）。
	expiry := time.Now().Add(h.cfg.TokenTTL).Unix()
	userToken := authplugin.IssueToken(h.cfg.SigningKey, req.UUID, expiry)

	cred := h.assembleCredential(req.UUID, userToken)
	data := cred.Encode()

	h.logf("confirm: %s (%s) 从 %s 验证通过，签发令牌（有效期至 %s），房间 %s",
		req.Username, req.UUID, ip, time.Unix(expiry, 0).Format("2006-01-02"), cred.Room())

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
	resp, err := h.client.Do(req)
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

// validateIdentity 校验 username/uuid 的形状，返回拒绝原因，合法返回空串。
// 这两个字段会原样进日志，拒绝控制字符从源头掐死日志注入（换行伪造日志行）；
// 与 Java 侧 UpgradeReport.sanitize 是同一条纪律。
func validateIdentity(username, uuid string) string {
	if username == "" || uuid == "" {
		return "username 和 uuid 不能为空"
	}
	// 皮肤站的用户名不一定限于 Mojang 的 [A-Za-z0-9_]{3,16}（可能允许中文），
	// 只卡长度与控制字符，具体合法性交给皮肤站的 hasJoined 判定
	if utf8.RuneCountInString(username) > 32 || hasControlChar(username) {
		return "username 不合法"
	}
	if !isHex32(norm(uuid)) {
		return "uuid 不合法（应为 32 位 hex，可带连字符）"
	}
	return ""
}

// validServerID 校验 serverId：authbridge 自己签发的是 32 位 hex，
// 放宽到 64 位以内的 hex 与连字符，给未来的格式变化留余量。
func validServerID(s string) bool {
	if s == "" || len(s) > 64 {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case c >= '0' && c <= '9', c >= 'a' && c <= 'f', c >= 'A' && c <= 'F', c == '-':
		default:
			return false
		}
	}
	return true
}

func hasControlChar(s string) bool {
	for _, r := range s {
		if r < 0x20 || r == 0x7f {
			return true
		}
	}
	return false
}

func isHex32(s string) bool {
	if len(s) != 32 {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case c >= '0' && c <= '9', c >= 'a' && c <= 'f', c >= 'A' && c <= 'F':
		default:
			return false
		}
	}
	return true
}

// sanitizeLog 替换掉控制字符，用于回显来自上游（皮肤站）的值。
// 上游是服主自选的站点，风险低，但日志完整性不该赌在别人的实现上。
func sanitizeLog(s string) string {
	return strings.Map(func(r rune) rune {
		if r < 0x20 || r == 0x7f {
			return '?'
		}
		return r
	}, s)
}

// clientIP 取来源 IP。TrustProxyHeader 开启时优先取 X-Forwarded-For 首跳
//（部署在反代之后时，RemoteAddr 永远是反代自己）。
func clientIP(r *http.Request, trustProxy bool) string {
	if trustProxy {
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			first := strings.TrimSpace(strings.SplitN(xff, ",", 2)[0])
			if first != "" {
				return sanitizeLog(first)
			}
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// ipLimiter 是每 IP 的令牌桶限流器：初始满桶（容量 = 每分钟配额），
// 按配额速率回填。无第三方依赖，桶表带惰性清理防止无限增长。
type ipLimiter struct {
	mu     sync.Mutex
	perMin float64
	bucket map[string]*tokenBucket
	// now 可在测试里替换以避免真实计时
	now func() time.Time
}

type tokenBucket struct {
	tokens float64
	last   time.Time
}

func newIPLimiter(perMinute int) *ipLimiter {
	return &ipLimiter{
		perMin: float64(perMinute),
		bucket: make(map[string]*tokenBucket),
		now:    time.Now,
	}
}

func (l *ipLimiter) allow(ip string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	b := l.bucket[ip]
	if b == nil {
		// 惰性清理：桶表过大时先淘汰一分钟没动静的（它们已回满，删了无损）
		if len(l.bucket) > 4096 {
			for k, v := range l.bucket {
				if now.Sub(v.last) > time.Minute {
					delete(l.bucket, k)
				}
			}
		}
		b = &tokenBucket{tokens: l.perMin, last: now}
		l.bucket[ip] = b
	}
	b.tokens += now.Sub(b.last).Seconds() * l.perMin / 60
	if b.tokens > l.perMin {
		b.tokens = l.perMin
	}
	b.last = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
