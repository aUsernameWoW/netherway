package authplugin

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/aUsernameWoW/netherway/internal/i18n"
)

// 文案断言以 zh 目录为基准；en 侧由 internal/i18n 的一致性测试覆盖。
func init() { i18n.Use(i18n.ZH) }

// 跨语言已知答案：与 Java 侧 SelfTest 的 testTokenIssuerKnownAnswer 用同一组
// 常量。任何一侧的算法改动都会先在这里撞车，而不是在玩家连不上时才发现。
const (
	kaKey    = "k3y"
	kaUser   = "069a79f4-44e9-4726-a5be-fca90e38aaf5"
	kaExpiry = int64(1893456000)
	kaToken  = "1893456000.0b91c0bf30d621d5c890a011253ea4b83c918422d19689334a18508e9b6db088"
	kaFinger = "a49b1287"
)

func TestIssueTokenKnownAnswer(t *testing.T) {
	if got := IssueToken(kaKey, kaUser, kaExpiry); got != kaToken {
		t.Fatalf("已知答案不匹配:\n got=%s\nwant=%s", got, kaToken)
	}
	if got := KeyFingerprint(kaKey); got != kaFinger {
		t.Fatalf("密钥指纹不匹配: got=%s want=%s", got, kaFinger)
	}
}

func TestVerifyToken(t *testing.T) {
	now := time.Unix(1700000000, 0) // 早于 kaExpiry
	cases := []struct {
		name    string
		user    string
		token   string
		wantErr string // 空表示应通过；否则为错误信息应包含的片段
	}{
		{"有效令牌", kaUser, kaToken, ""},
		{"错误的 user", "someone-else", kaToken, "校验失败"},
		{"缺 user", "", kaToken, "metas.user"},
		{"被篡改的签名", kaUser, kaToken[:len(kaToken)-1] + "0", "校验失败"},
		{"被篡改的过期时间", kaUser, "1999999999." + strings.SplitN(kaToken, ".", 2)[1], "校验失败"},
		{"格式错误", kaUser, "not-a-token", "格式错误"},
		{"空令牌", kaUser, "", "格式错误"},
	}
	for _, c := range cases {
		err := VerifyToken(kaKey, c.user, c.token, now)
		if c.wantErr == "" && err != nil {
			t.Errorf("%s: 应通过, got %v", c.name, err)
		}
		if c.wantErr != "" && (err == nil || !strings.Contains(err.Error(), c.wantErr)) {
			t.Errorf("%s: 应含 %q, got %v", c.name, c.wantErr, err)
		}
	}

	// 过期判定用传入的时钟
	if err := VerifyToken(kaKey, kaUser, kaToken, time.Unix(kaExpiry+1, 0)); err == nil ||
		!strings.Contains(err.Error(), "过期") {
		t.Errorf("过期令牌应被拒绝, got %v", err)
	}
}

// post 模拟 frps 的插件调用，返回解析后的响应。
func post(t *testing.T, h http.Handler, op string, content any) response {
	t.Helper()
	body, err := json.Marshal(map[string]any{"version": "0.1.0", "op": op, "content": content})
	if err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest("POST",
		fmt.Sprintf("/handler?version=0.1.0&op=%s", op), bytes.NewReader(body))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("HTTP %d: %s", rec.Code, rec.Body.String())
	}
	var res response
	if err := json.Unmarshal(rec.Body.Bytes(), &res); err != nil {
		t.Fatalf("响应不是合法 JSON: %v", err)
	}
	return res
}

func login(user, token string) map[string]any {
	metas := map[string]string{}
	if user != "" {
		metas[MetaUser] = user
	}
	if token != "" {
		metas[MetaToken] = token
	}
	return map[string]any{"metas": metas, "client_address": "203.0.113.9:5000"}
}

func newProxy(token, name string) map[string]any {
	metas := map[string]string{}
	if token != "" {
		metas[MetaToken] = token
	}
	return map[string]any{
		"user":       map[string]any{"user": "", "metas": metas},
		"proxy_name": name,
		"proxy_type": "stcp",
	}
}

func TestLoginPolicy(t *testing.T) {
	valid := IssueToken(kaKey, kaUser, time.Now().Unix()+3600)
	expired := IssueToken(kaKey, kaUser, time.Now().Unix()-1)

	strict := NewHandler(Config{SigningKey: kaKey, StaticTokens: []string{"serve-static"}})
	lenient := NewHandler(Config{SigningKey: kaKey, AllowLegacy: true})

	cases := []struct {
		name   string
		h      http.Handler
		user   string
		token  string
		reject bool
	}{
		{"有效玩家令牌放行", strict, kaUser, valid, false},
		{"过期令牌拒绝", strict, kaUser, expired, true},
		{"令牌绑定他人拒绝", strict, "other-user", valid, true},
		{"静态令牌放行", strict, "", "serve-static", false},
		{"无令牌默认拒绝", strict, "", "", true},
		{"无令牌 legacy 放行", lenient, "", "", false},
		{"乱写的令牌拒绝", strict, kaUser, "garbage", true},
	}
	for _, c := range cases {
		res := post(t, c.h, "Login", login(c.user, c.token))
		if res.Reject != c.reject {
			t.Errorf("%s: reject=%v (原因 %q), want %v",
				c.name, res.Reject, res.RejectReason, c.reject)
		}
		if !res.Reject && !res.Unchange {
			t.Errorf("%s: 放行时必须 unchange=true", c.name)
		}
	}
}

func TestNewProxyPolicy(t *testing.T) {
	playerToken := IssueToken(kaKey, kaUser, time.Now().Unix()+3600)
	strict := NewHandler(Config{SigningKey: kaKey, StaticTokens: []string{"serve-static"}})
	lenient := NewHandler(Config{SigningKey: kaKey, AllowLegacy: true})

	cases := []struct {
		name   string
		h      http.Handler
		token  string
		reject bool
	}{
		{"静态令牌可注册代理", strict, "serve-static", false},
		{"玩家令牌不可注册代理", strict, playerToken, true},
		{"无令牌默认不可注册", strict, "", true},
		{"无令牌 legacy 可注册", lenient, "", false},
	}
	for _, c := range cases {
		res := post(t, c.h, "NewProxy", newProxy(c.token, "survival-p2p"))
		if res.Reject != c.reject {
			t.Errorf("%s: reject=%v (原因 %q), want %v",
				c.name, res.Reject, res.RejectReason, c.reject)
		}
	}
}

func TestRejectsBadRequests(t *testing.T) {
	h := NewHandler(Config{SigningKey: kaKey})
	req := httptest.NewRequest("GET", "/handler", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusMethodNotAllowed {
		t.Errorf("GET 应 405, got %d", rec.Code)
	}

	req = httptest.NewRequest("POST", "/handler", strings.NewReader("not json"))
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("坏 JSON 应 400, got %d", rec.Code)
	}
}
