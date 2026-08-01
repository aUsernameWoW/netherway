package authbridge

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/ripplecraft/xtcpinmc/internal/authplugin"
)

const testKey = "test-signing-key"

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
	post(t, srv.URL+"/prefetch", map[string]string{"username": "Alice", "uuid": "u1"}, &a)
	post(t, srv.URL+"/prefetch", map[string]string{"username": "Alice", "uuid": "u1"}, &b)
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
		"serverId": "deadbeef", "username": "Alice", "uuid": "u1",
	}, &out)
	if out.OK {
		t.Fatal("未 join 时应被拒绝")
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
