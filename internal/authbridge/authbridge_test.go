package authbridge

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/ripplecraft/xtcpinmc/internal/authplugin"
)

const testKey = "test-signing-key"

// testUUID 是形状合法的占位 uuid：入参校验要求 32 位 hex（可带连字符）。
const testUUID = "11112222-3333-4444-5555-666677778888"

// stubSkin 模拟皮肤站的 hasJoined：按测试预设返回 Profile 或 204。
type stubSkin struct {
	profileID string // 空表示「没人 join 过」，返回 204
	name      string
	lastQuery map[string]string
}

func (s *stubSkin) handler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/sessionserver/session/minecraft/hasJoined") {
			http.NotFound(w, r)
			return
		}
		s.lastQuery = map[string]string{
			"username": r.URL.Query().Get("username"),
			"serverId": r.URL.Query().Get("serverId"),
		}
		if s.profileID == "" {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]string{"id": s.profileID, "name": s.name})
	})
}

func newBridge(t *testing.T, skin *stubSkin) *httptest.Server {
	t.Helper()
	skinSrv := httptest.NewServer(skin.handler())
	t.Cleanup(skinSrv.Close)
	h := NewHandler(Config{
		SigningKey: testKey,
		AuthServer: skinSrv.URL,
		RoomParams: map[string]string{"room": "gtnh", "secret": "s3"},
		TokenTTL:   time.Hour,
	})
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	return srv
}

func post(t *testing.T, url string, req any, out any) *http.Response {
	t.Helper()
	body, _ := json.Marshal(req)
	resp, err := http.Post(url, "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if err := json.NewDecoder(resp.Body).Decode(out); err != nil {
		t.Fatal(err)
	}
	return resp
}

func TestPrefetchIssuesRandomServerID(t *testing.T) {
	srv := newBridge(t, &stubSkin{})
	var a, b struct {
		ServerID string `json:"serverId"`
	}
	post(t, srv.URL+"/prefetch", map[string]string{"username": "Alice", "uuid": testUUID}, &a)
	post(t, srv.URL+"/prefetch", map[string]string{"username": "Alice", "uuid": testUUID}, &b)
	if len(a.ServerID) != 32 {
		t.Fatalf("serverId 长度 %d，应为 32 位 hex", len(a.ServerID))
	}
	if a.ServerID == b.ServerID {
		t.Fatal("两次 serverId 相同，随机性可疑")
	}
}

func TestPrefetchRejectsEmptyIdentity(t *testing.T) {
	srv := newBridge(t, &stubSkin{})
	var out struct {
		Reason string `json:"reason"`
	}
	resp := post(t, srv.URL+"/prefetch", map[string]string{"username": "", "uuid": ""}, &out)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("状态码 %d，应为 400", resp.StatusCode)
	}
	if out.Reason == "" {
		t.Fatal("拒绝时应带 reason，供 prefetch 侧透传")
	}
}

// TestConfirmFullFlow 走通验证成功路径，并核对凭证内容与令牌有效性。
func TestConfirmFullFlow(t *testing.T) {
	// 皮肤站返回无连字符 id；请求带连字符——覆盖归一化比对
	skin := &stubSkin{profileID: "11112222333344445555666677778888", name: "Alice"}
	srv := newBridge(t, skin)

	var out struct {
		OK         bool   `json:"ok"`
		Reason     string `json:"reason"`
		Credential string `json:"credential"`
		Room       string `json:"room"`
		BackendID  string `json:"backendId"`
	}
	post(t, srv.URL+"/confirm", map[string]string{
		"serverId": "deadbeef",
		"username": "Alice",
		"uuid":     "11112222-3333-4444-5555-666677778888",
	}, &out)
	if !out.OK {
		t.Fatalf("confirm 失败: %s", out.Reason)
	}
	if out.Room != "gtnh" || out.BackendID != "frp-xtcp" {
		t.Fatalf("room=%q backendId=%q 不符", out.Room, out.BackendID)
	}
	if skin.lastQuery["serverId"] != "deadbeef" || skin.lastQuery["username"] != "Alice" {
		t.Fatalf("hasJoined 查询参数不符: %v", skin.lastQuery)
	}

	data, err := base64.StdEncoding.DecodeString(out.Credential)
	if err != nil {
		t.Fatal(err)
	}
	params := decodeCredential(t, data)
	if params["room"] != "gtnh" || params["secret"] != "s3" {
		t.Fatalf("房间参数不符: %v", params)
	}
	if params["user"] != "11112222-3333-4444-5555-666677778888" {
		t.Fatalf("user 应保留请求侧原样（与 frpc metas.user 一致），实际 %q", params["user"])
	}
	// 令牌须能通过 authplugin 的校验——两者共用同一套签发/校验实现
	if err := authplugin.VerifyToken(testKey, params["user"], params["userToken"], time.Now()); err != nil {
		t.Fatalf("签发的令牌未通过 authplugin 校验: %v", err)
	}
}

func TestConfirmRejectsUUIDMismatch(t *testing.T) {
	skin := &stubSkin{profileID: "aaaabbbbccccddddeeeeffff00001111", name: "Mallory"}
	srv := newBridge(t, skin)
	var out struct {
		OK     bool   `json:"ok"`
		Reason string `json:"reason"`
	}
	// 用别人的 serverId 报自己的 uuid：皮肤站返回的档案 id 对不上，必须拒绝
	post(t, srv.URL+"/confirm", map[string]string{
		"serverId": "deadbeef", "username": "Mallory",
		"uuid": "11112222-3333-4444-5555-666677778888",
	}, &out)
	if out.OK {
		t.Fatal("uuid 不匹配应被拒绝")
	}
	if !strings.Contains(out.Reason, "uuid") {
		t.Fatalf("reason 应说明 uuid 不匹配，实际 %q", out.Reason)
	}
}

func TestConfirmRejectsWhenNotJoined(t *testing.T) {
	srv := newBridge(t, &stubSkin{}) // 204：没人 join 过
	var out struct {
		OK     bool   `json:"ok"`
		Reason string `json:"reason"`
	}
	post(t, srv.URL+"/confirm", map[string]string{
		"serverId": "deadbeef", "username": "Alice", "uuid": testUUID,
	}, &out)
	if out.OK {
		t.Fatal("未 join 时应被拒绝")
	}
}

// ---------- 加固回归 ----------

func TestRejectsOversizedBody(t *testing.T) {
	srv := newBridge(t, &stubSkin{})
	// 5 KiB 的 username，超过 4 KiB 的请求体上限
	huge := strings.Repeat("A", 5<<10)
	body, _ := json.Marshal(map[string]string{"username": huge, "uuid": testUUID})
	resp, err := http.Post(srv.URL+"/prefetch", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("状态码 %d，应为 413", resp.StatusCode)
	}
}

func TestRejectsMalformedIdentity(t *testing.T) {
	srv := newBridge(t, &stubSkin{})
	cases := []struct {
		name     string
		username string
		uuid     string
	}{
		// 换行能伪造日志行，是日志注入的经典载荷
		{"用户名带控制字符", "Alice\nFAKE-LOG-LINE", testUUID},
		{"uuid 不是 hex", "Alice", "not-a-uuid"},
		{"uuid 长度不对", "Alice", "11112222"},
		{"用户名超长", strings.Repeat("甲", 33), testUUID},
	}
	for _, c := range cases {
		var out struct {
			Reason string `json:"reason"`
		}
		resp := post(t, srv.URL+"/prefetch",
			map[string]string{"username": c.username, "uuid": c.uuid}, &out)
		if resp.StatusCode != http.StatusBadRequest {
			t.Fatalf("%s: 状态码 %d，应为 400", c.name, resp.StatusCode)
		}
		if out.Reason == "" {
			t.Fatalf("%s: 拒绝时应带 reason", c.name)
		}
	}
}

func TestConfirmRejectsMalformedServerID(t *testing.T) {
	srv := newBridge(t, &stubSkin{profileID: "11112222333344445555666677778888"})
	var out struct {
		OK     bool   `json:"ok"`
		Reason string `json:"reason"`
	}
	resp := post(t, srv.URL+"/confirm", map[string]string{
		"serverId": "../../etc/passwd", "username": "Alice", "uuid": testUUID,
	}, &out)
	if resp.StatusCode != http.StatusBadRequest || out.OK {
		t.Fatalf("非法 serverId 应被 400 拒绝，实际 %d ok=%v", resp.StatusCode, out.OK)
	}
}

func TestRateLimitPerIP(t *testing.T) {
	skinSrv := httptest.NewServer((&stubSkin{}).handler())
	t.Cleanup(skinSrv.Close)
	h := NewHandler(Config{
		SigningKey:     testKey,
		AuthServer:     skinSrv.URL,
		RoomParams:     map[string]string{"room": "gtnh", "secret": "s3"},
		PerIPPerMinute: 2, // 初始满桶 2 个令牌，第 3 个请求必被拒
	})
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)

	var last int
	for range 3 {
		var out struct {
			Reason string `json:"reason"`
		}
		last = post(t, srv.URL+"/prefetch",
			map[string]string{"username": "Alice", "uuid": testUUID}, &out).StatusCode
	}
	if last != http.StatusTooManyRequests {
		t.Fatalf("第 3 个请求状态码 %d，应为 429（限流）", last)
	}
}

// TestConfirmConcurrencyCap 用卡住的皮肤站占满并发额度，
// 验证后续 confirm 被 503 拒绝而不是排队堆积。
func TestConfirmConcurrencyCap(t *testing.T) {
	release := make(chan struct{})
	entered := make(chan struct{})
	var once sync.Once
	blockedSkin := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// 通报「额度已被占住」——占到信号量之后才会走到这里
		once.Do(func() { close(entered) })
		<-release // 卡住第一个 hasJoined 外呼
		w.WriteHeader(http.StatusNoContent)
	})
	skinSrv := httptest.NewServer(blockedSkin)
	t.Cleanup(skinSrv.Close)
	defer close(release)

	h := NewHandler(Config{
		SigningKey:            testKey,
		AuthServer:            skinSrv.URL,
		RoomParams:            map[string]string{"room": "gtnh", "secret": "s3"},
		MaxConcurrentConfirms: 1,
		// 抬高限流阈值，别让 429 混进并发上限的断言
		PerIPPerMinute: 100000,
	})
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)

	confirmBody := map[string]string{
		"serverId": "deadbeef", "username": "Alice", "uuid": testUUID,
	}
	// 第一个 confirm 在后台被皮肤站卡住，占满唯一的并发额度。
	// 不用 post 助手：它在失败时调 t.Fatal，而这里是非测试 goroutine。
	go func() {
		body, _ := json.Marshal(confirmBody)
		resp, err := http.Post(srv.URL+"/confirm", "application/json", bytes.NewReader(body))
		if err == nil {
			resp.Body.Close()
		}
	}()
	select {
	case <-entered: // 额度确定已被后台请求占住，第二个请求必被拒
	case <-time.After(5 * time.Second):
		t.Fatal("后台 confirm 没有到达皮肤站")
	}

	var out struct {
		Reason string `json:"reason"`
	}
	code := post(t, srv.URL+"/confirm", confirmBody, &out).StatusCode
	if code != http.StatusServiceUnavailable {
		t.Fatalf("并发额度占满后，后续 confirm 应被 503 拒绝，实际 %d", code)
	}
}

// decodeCredential 按 Java 侧 Credentials 的字节布局解出参数表。
func decodeCredential(t *testing.T, data []byte) map[string]string {
	t.Helper()
	r := bytes.NewReader(data)
	version, _ := r.ReadByte()
	if version != 2 {
		t.Fatalf("凭证版本 %d，应为 2", version)
	}
	readUTF := func() string {
		var n uint16
		if err := binary.Read(r, binary.BigEndian, &n); err != nil {
			t.Fatal(err)
		}
		buf := make([]byte, n)
		if _, err := r.Read(buf); err != nil {
			t.Fatal(err)
		}
		return string(buf)
	}
	if backend := readUTF(); backend != "frp-xtcp" {
		t.Fatalf("backendId %q", backend)
	}
	var punchMs int32
	_ = binary.Read(r, binary.BigEndian, &punchMs)
	var count uint16
	_ = binary.Read(r, binary.BigEndian, &count)
	params := make(map[string]string, count)
	for i := 0; i < int(count); i++ {
		k := readUTF()
		params[k] = readUTF()
	}
	if r.Len() != 0 {
		t.Fatalf("凭证尾部残留 %d 字节", r.Len())
	}
	return params
}
