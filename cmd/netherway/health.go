package main

import (
	"bytes"
	"io"
	"sync"
	"time"
)

// tunnelHealthScanner 从回显到 stderr 的 frp 日志里提取隧道健康信号。
//
// visitor 侧开着 KeepTunnelOpen，frp 的 keepTunnelOpenWorker 会周期自检并
// 自愈维护中的隧道；打不通时以 Warn 级写日志。这是 READY 之后唯一稳定
// 出现的端到端健康信号——frp 没有导出 visitor 状态的 API（StatusExporter
// 只覆盖 proxy），日志行是仅有的出口。服务端重启换钥后 frp 只会拿旧凭证
// 无限重试（LoginFailExit=false），进程不死也不自愈，mod 侧无从察觉；
// 这里把「连续自检失败」翻译成一次 degraded 通知，由 modbridge 发进
// stdout 的 JSON 契约，提示 mod 立即刷新凭证并重建。
//
// 匹配的字面量钉在 go.mod 锁定的 frp 版本上（v0.70.x client/visitor/
// xtcp.go 的 keepTunnelOpenWorker），bump frp 时随 interop 测试一并核对。
// 匹配不上的代价有界：mod 侧的慢速对账预取仍会兜底发现凭证轮换。
const tunnelBrokenMarker = "keepTunnelOpenWorker get tunnel connection error"

const (
	// 单次失败可能只是网络抖动，frp 下一轮自检就会自愈；连续两次
	// （相隔至少一个自检周期）才值得让 mod 拆掉重建。
	healthFailThreshold = 2
	// 两次失败相隔超过这个窗口就当作不相关的孤立抖动，重新计数。
	// frp 失败重试受 MaxRetriesAnHour 限速（默认 8/小时，突发后约
	// 7.5 分钟一次），窗口必须比那个间隔宽。
	healthFailWindow = 10 * time.Minute
	// 发出通知后的静默期：mod 正常会很快拆掉本进程，静默只服务
	// 「老 mod 不认识 degraded」的场景，避免反复刷事件。
	healthSuppressWindow = 10 * time.Minute
)

type tunnelHealthScanner struct {
	dst    io.Writer       // 透传去向（stderr），扫描绝不改写内容
	notify chan<- struct{} // 容量 1；满了说明上一条还没被消费，直接丢

	mu            sync.Mutex
	line          bytes.Buffer
	armed         bool
	failCount     int
	firstFailAt   time.Time
	suppressUntil time.Time
	now           func() time.Time
}

func newTunnelHealthScanner(dst io.Writer, notify chan<- struct{}) *tunnelHealthScanner {
	return &tunnelHealthScanner{dst: dst, notify: notify, now: time.Now}
}

// arm 在隧道 READY 后开启扫描。打洞期间 worker 的失败属正常过程，
// 计入会把「还没打通」误报成「打通后又断了」。
func (s *tunnelHealthScanner) arm() {
	s.mu.Lock()
	s.armed = true
	s.mu.Unlock()
}

// Write 透传到 dst 并按行扫描。golib/log 每条日志恰好一次 Write，
// 但这里不依赖这一点，跨 Write 的半行会被拼起来。
func (s *tunnelHealthScanner) Write(p []byte) (int, error) {
	n, err := s.dst.Write(p)
	s.mu.Lock()
	defer s.mu.Unlock()
	rest := p
	for {
		i := bytes.IndexByte(rest, '\n')
		if i < 0 {
			break
		}
		s.line.Write(rest[:i])
		s.scanLine(s.line.Bytes())
		s.line.Reset()
		rest = rest[i+1:]
	}
	s.line.Write(rest)
	// 病态的无换行流不该无界攒内存；标记行远短于这个上限。
	if s.line.Len() > 16*1024 {
		s.line.Reset()
	}
	return n, err
}

func (s *tunnelHealthScanner) scanLine(line []byte) {
	if !s.armed || !bytes.Contains(line, []byte(tunnelBrokenMarker)) {
		return
	}
	t := s.now()
	if t.Before(s.suppressUntil) {
		return
	}
	if s.failCount == 0 || t.Sub(s.firstFailAt) > healthFailWindow {
		s.failCount = 1
		s.firstFailAt = t
		return
	}
	s.failCount++
	if s.failCount < healthFailThreshold {
		return
	}
	s.failCount = 0
	s.suppressUntil = t.Add(healthSuppressWindow)
	select {
	case s.notify <- struct{}{}:
	default:
	}
}
