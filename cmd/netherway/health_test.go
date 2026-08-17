package main

import (
	"bytes"
	"testing"
	"time"
)

// frp v0.70 keepTunnelOpenWorker 失败行的真实形状（teeWriter 只回显 info+）。
const brokenLine = "2026-08-18 12:00:00.000 [W] [visitor/xtcp.go:127] [7355608] " +
	"keepTunnelOpenWorker get tunnel connection error: session shutdown\n"

func newTestScanner(notify chan struct{}) (*tunnelHealthScanner, *bytes.Buffer, *time.Time) {
	var dst bytes.Buffer
	at := time.Date(2026, 8, 18, 12, 0, 0, 0, time.UTC)
	s := newTunnelHealthScanner(&dst, notify)
	s.now = func() time.Time { return at }
	return s, &dst, &at
}

func drained(notify chan struct{}) bool {
	select {
	case <-notify:
		return false
	default:
		return true
	}
}

func TestHealthScannerPassesThrough(t *testing.T) {
	notify := make(chan struct{}, 1)
	s, dst, _ := newTestScanner(notify)
	in := "2026-08-18 12:00:00.000 [I] [client/service.go:1] login to server success\n"
	if n, err := s.Write([]byte(in)); err != nil || n != len(in) {
		t.Fatalf("Write = (%d, %v)", n, err)
	}
	if dst.String() != in {
		t.Fatalf("透传内容被改写: %q", dst.String())
	}
}

func TestHealthScannerRequiresArm(t *testing.T) {
	notify := make(chan struct{}, 1)
	s, _, _ := newTestScanner(notify)
	for range 5 {
		s.Write([]byte(brokenLine))
	}
	if !drained(notify) {
		t.Fatal("未 arm 时不应产生通知：打洞期间的失败属正常过程")
	}
}

func TestHealthScannerSingleFailureIsQuiet(t *testing.T) {
	notify := make(chan struct{}, 1)
	s, _, _ := newTestScanner(notify)
	s.arm()
	s.Write([]byte(brokenLine))
	if !drained(notify) {
		t.Fatal("单次失败可能只是抖动，不应通知")
	}
}

func TestHealthScannerNotifiesOnConsecutiveFailures(t *testing.T) {
	notify := make(chan struct{}, 1)
	s, _, at := newTestScanner(notify)
	s.arm()
	s.Write([]byte(brokenLine))
	*at = at.Add(90 * time.Second)
	s.Write([]byte(brokenLine))
	select {
	case <-notify:
	default:
		t.Fatal("窗口内连续两次失败应当通知")
	}
	// 静默期内继续失败不再通知
	*at = at.Add(time.Minute)
	s.Write([]byte(brokenLine))
	*at = at.Add(time.Minute)
	s.Write([]byte(brokenLine))
	if !drained(notify) {
		t.Fatal("静默期内不应重复通知")
	}
	// 静默期过后需要重新凑满阈值，之后允许再次通知（老 mod 忽略
	// degraded 时,后续的上报是它仅有的重试机会）
	*at = at.Add(healthSuppressWindow)
	s.Write([]byte(brokenLine))
	*at = at.Add(90 * time.Second)
	s.Write([]byte(brokenLine))
	select {
	case <-notify:
	default:
		t.Fatal("静默期结束后再次连续失败应当再通知")
	}
}

func TestHealthScannerWindowExpiryResets(t *testing.T) {
	notify := make(chan struct{}, 1)
	s, _, at := newTestScanner(notify)
	s.arm()
	s.Write([]byte(brokenLine))
	*at = at.Add(healthFailWindow + time.Minute)
	s.Write([]byte(brokenLine))
	if !drained(notify) {
		t.Fatal("相隔超过窗口的两次失败是孤立抖动，不应通知")
	}
}

func TestHealthScannerAssemblesSplitLines(t *testing.T) {
	notify := make(chan struct{}, 1)
	s, _, at := newTestScanner(notify)
	s.arm()
	half := len(brokenLine) / 2
	s.Write([]byte(brokenLine[:half]))
	s.Write([]byte(brokenLine[half:]))
	*at = at.Add(90 * time.Second)
	s.Write([]byte(brokenLine))
	select {
	case <-notify:
	default:
		t.Fatal("跨 Write 的半行应被拼接后再匹配")
	}
}
