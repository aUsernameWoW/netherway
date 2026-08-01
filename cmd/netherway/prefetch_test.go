package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/aUsernameWoW/netherway/internal/authbridge"
)

// infoStub 起一个只应答（或不应答）/info 的回环 HTTP 服务。
// 回环地址天然豁免 requireTLS，测试无需证书。
func infoStub(t *testing.T, service string) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/info" || service == "" {
			http.NotFound(w, r)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"service": service, "protocol": 1})
	}))
	t.Cleanup(srv.Close)
	return srv
}

func TestPickBridgeSingleSkipsProbe(t *testing.T) {
	// 单候选不探测：地址直接采用，兼容没有 /info 的旧版 authbridge
	got, err := pickBridge([]string{"http://127.0.0.1:1/no-such"}, time.Second, false)
	if err != nil {
		t.Fatal(err)
	}
	if got != "http://127.0.0.1:1/no-such" {
		t.Fatalf("选中 %q", got)
	}
}

func TestPickBridgeSingleStillRequiresTLS(t *testing.T) {
	if _, err := pickBridge([]string{"http://203.0.113.7/netherway"}, time.Second, false); err == nil {
		t.Fatal("非回环明文候选应被拒绝")
	}
}

func TestPickBridgeProbesCandidatesInOrder(t *testing.T) {
	wrong := infoStub(t, "some-other-service")
	silent := infoStub(t, "") // 该路径 404，模拟无关服务器
	right := infoStub(t, authbridge.InfoService)
	later := infoStub(t, authbridge.InfoService)

	got, err := pickBridge([]string{silent.URL, wrong.URL, right.URL, later.URL},
		2*time.Second, false)
	if err != nil {
		t.Fatal(err)
	}
	if got != right.URL {
		t.Fatalf("应选中第一个自报 authbridge 的候选 %q，实际 %q", right.URL, got)
	}
}

func TestPickBridgeAllFail(t *testing.T) {
	wrong := infoStub(t, "some-other-service")
	_, err := pickBridge([]string{wrong.URL, "http://127.0.0.1:1/dead"}, time.Second, false)
	if err == nil {
		t.Fatal("全部候选失败时应报错")
	}
	// 报错要能看出每个候选各自败在哪里——这是玩家日志里唯一的线索
	if !strings.Contains(err.Error(), wrong.URL) {
		t.Fatalf("错误信息未列出候选: %v", err)
	}
}

func TestPickBridgeSkipsPlaintextAmongCandidates(t *testing.T) {
	right := infoStub(t, authbridge.InfoService)
	// 多候选时明文非回环地址跳过而非中止：server.dat 里什么都可能有
	got, err := pickBridge([]string{"http://203.0.113.7/netherway", right.URL},
		2*time.Second, false)
	if err != nil {
		t.Fatal(err)
	}
	if got != right.URL {
		t.Fatalf("选中 %q", got)
	}
}
