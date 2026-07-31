// Package authplugin 实现 frps 的 HTTP server plugin，做每玩家令牌校验。
//
// 分层鉴权：frps 自身的 auth.token 仍是基础校验（挡住完全无凭证的连接），
// 本插件在其上叠加身份层——Login 时校验 metas 里的每玩家令牌，NewProxy 时
// 只放行持静态令牌的会话（即 serve 端）。于是全局 token 泄露的后果从
// 「能在 frps 上注册任意代理」降为「什么都做不了」。
//
// 身份刻意放在 metas（metas.user / metas.token）而不是 frp 的 User 字段：
// User 会给 visitor 的目标代理名加 "user." 前缀（见 frp 的
// naming.BuildTargetServerProxyName），一旦使用就要求两端同步改名；
// metas 对命名零影响，老客户端也天然兼容（没带 metas 就走 legacy 分支）。
//
// 令牌格式与 Java 侧 core 的 TokenIssuer 逐字节一致，改动必须两边同步：
//
//	<过期秒级时间戳>.<hex(HMAC-SHA256(签发密钥, user + "\n" + 过期时间戳))>
//
// 校验无状态：过期时间就在令牌里，插件只需持有签发密钥。吊销 = 换签发密钥
// （全局作废）或等待过期；按玩家即刻吊销刻意不做——白名单本身已挡住 MC 登录，
// 隧道也只通向 MC 端口，为此维护一套黑名单状态不划算。
package authplugin

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// MetaUser 与 MetaToken 是 metas 里的键名，与 Go 侧 frpxtcp backend
// 写入 Metadatas 的键一致。
const (
	MetaUser  = "user"
	MetaToken = "token"
)

// IssueToken 签发令牌。与 Java 侧 TokenIssuer.issue 必须逐字节一致。
func IssueToken(key, user string, expiryUnix int64) string {
	mac := hmac.New(sha256.New, []byte(key))
	mac.Write([]byte(user + "\n" + strconv.FormatInt(expiryUnix, 10)))
	return strconv.FormatInt(expiryUnix, 10) + "." + hex.EncodeToString(mac.Sum(nil))
}

// VerifyToken 校验令牌与 user 的绑定及有效期。
func VerifyToken(key, user, token string, now time.Time) error {
	dot := strings.IndexByte(token, '.')
	if dot <= 0 {
		return fmt.Errorf("令牌格式错误")
	}
	expiry, err := strconv.ParseInt(token[:dot], 10, 64)
	if err != nil {
		return fmt.Errorf("令牌格式错误")
	}
	if user == "" {
		return fmt.Errorf("缺少 metas.user")
	}
	if now.Unix() > expiry {
		return fmt.Errorf("令牌已于 %s 过期（重新经中转进服一次即自动续签）",
			time.Unix(expiry, 0).Format("2006-01-02"))
	}
	// 重新签发再整体比对，hmac.Equal 恒定时间
	if !hmac.Equal([]byte(IssueToken(key, user, expiry)), []byte(token)) {
		return fmt.Errorf("令牌校验失败")
	}
	return nil
}

// KeyFingerprint 返回签发密钥的短指纹（SHA-256 前 4 字节）。
// 两侧启动日志都打印它，便于核对「密钥是否一致」而不暴露密钥本身。
// 与 Java 侧 TokenIssuer.keyFingerprint 必须一致。
func KeyFingerprint(key string) string {
	sum := sha256.Sum256([]byte(key))
	return hex.EncodeToString(sum[:4])
}

// Config 是插件的全部策略。
type Config struct {
	// SigningKey 为每玩家令牌的签发密钥，与服务端 mod 的 tokenSigningKey 一致。
	SigningKey string
	// StaticTokens 是静态令牌白名单；serve 端凭它表明身份并获准注册代理。
	StaticTokens []string
	// AllowLegacy 放行不带令牌的登录。迁移期打开，等独立运行的 join/serve
	// 都带上令牌（或被淘汰）后关掉，全局 token 泄露就彻底无害了。
	AllowLegacy bool
	// Logf 输出决策日志；nil 表示静默。
	Logf func(format string, args ...any)
}

// Handler 实现 frps httpPlugins 的 HTTP 端点。
type Handler struct {
	cfg Config
}

func NewHandler(cfg Config) *Handler {
	return &Handler{cfg: cfg}
}

// request 只解出需要的字段；content 按 op 再解。
type request struct {
	Op      string          `json:"op"`
	Content json.RawMessage `json:"content"`
}

type loginContent struct {
	User          string            `json:"user"`
	Metas         map[string]string `json:"metas"`
	ClientAddress string            `json:"client_address"`
}

type userInfo struct {
	User  string            `json:"user"`
	Metas map[string]string `json:"metas"`
}

type newProxyContent struct {
	User      userInfo `json:"user"`
	ProxyName string   `json:"proxy_name"`
	ProxyType string   `json:"proxy_type"`
}

// response 对应 frp 的 plugin.Response。
type response struct {
	Reject       bool   `json:"reject"`
	RejectReason string `json:"reject_reason,omitempty"`
	Unchange     bool   `json:"unchange"`
}

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var req request
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}

	var reason string
	switch req.Op {
	case "Login":
		var c loginContent
		if err := json.Unmarshal(req.Content, &c); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		reason = h.decideLogin(c)
	case "NewProxy":
		var c newProxyContent
		if err := json.Unmarshal(req.Content, &c); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		reason = h.decideNewProxy(c)
	default:
		// 未订阅的 op 不该到这里；到了也原样放行，别把 frps 弄坏
	}

	w.Header().Set("Content-Type", "application/json")
	if reason != "" {
		_ = json.NewEncoder(w).Encode(response{Reject: true, RejectReason: reason})
		return
	}
	_ = json.NewEncoder(w).Encode(response{Unchange: true})
}

// decideLogin 返回空串表示放行，否则为拒绝原因。
func (h *Handler) decideLogin(c loginContent) string {
	token := c.Metas[MetaToken]
	user := c.Metas[MetaUser]

	if token == "" {
		if h.cfg.AllowLegacy {
			h.logf("放行 legacy 登录（未带令牌）: %s", c.ClientAddress)
			return ""
		}
		h.logf("拒绝登录（未带令牌，且未开 -allow-legacy）: %s", c.ClientAddress)
		return "登录未携带令牌；旧客户端请重新经服务器中转进服一次以获取新凭证"
	}
	if h.isStatic(token) {
		h.logf("放行静态令牌登录: %s", c.ClientAddress)
		return ""
	}
	if err := VerifyToken(h.cfg.SigningKey, user, token, time.Now()); err != nil {
		h.logf("拒绝登录（user=%s, %v）: %s", user, err, c.ClientAddress)
		return fmt.Sprintf("%v", err)
	}
	h.logf("放行玩家登录: user=%s %s", user, c.ClientAddress)
	return ""
}

// decideNewProxy 只许静态令牌（serve）注册代理；玩家令牌一律拒绝——
// 玩家侧只有 visitor，任何注册代理的企图都不是本项目的正常流量。
func (h *Handler) decideNewProxy(c newProxyContent) string {
	token := c.User.Metas[MetaToken]
	if token == "" {
		if h.cfg.AllowLegacy {
			h.logf("放行 legacy 会话注册代理: %s (%s)", c.ProxyName, c.ProxyType)
			return ""
		}
		h.logf("拒绝注册代理（未带静态令牌）: %s (%s)", c.ProxyName, c.ProxyType)
		return "未带静态令牌的会话不允许注册代理"
	}
	if h.isStatic(token) {
		h.logf("放行代理注册: %s (%s)", c.ProxyName, c.ProxyType)
		return ""
	}
	h.logf("拒绝注册代理（玩家令牌，user=%s）: %s (%s)",
		c.User.Metas[MetaUser], c.ProxyName, c.ProxyType)
	return "玩家令牌不允许注册代理"
}

func (h *Handler) isStatic(token string) bool {
	ok := false
	for _, s := range h.cfg.StaticTokens {
		// 全部比完不提前返回，避免比较耗时泄露命中位置
		if hmac.Equal([]byte(s), []byte(token)) {
			ok = true
		}
	}
	return ok
}

func (h *Handler) logf(format string, args ...any) {
	if h.cfg.Logf != nil {
		h.cfg.Logf(format, args...)
	}
}
