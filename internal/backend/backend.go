// Package backend 定义隧道 backend 的统一接口与注册表。
//
// 一个 backend 把某种隧道方案抽象成一件事：在本机指定地址上开一个
// TCP 端口，通向 Minecraft 服务器。frp xtcp 打洞是第一个实现；将来的
// 方案（headscale、Hysteria 之类）只要能归约到「本地端口」这个形状，
// 就能在不动 mod 侧与 tunnel 子命令的前提下接进来。
//
// 就绪与否刻意不由 backend 自己上报，而是由调用方用 Minecraft 握手
// （Server List Ping）探测判定——这条判据对任何隧道方案都成立，
// 还顺带确认了服务端进程在响应，而不只是端口被监听着。
package backend

import (
	"context"
	"sort"

	"github.com/ripplecraft/xtcpinmc/internal/config"
)

// Options 是调用方传给 backend 的通用运行参数，与具体隧道方案无关。
type Options struct {
	// BindAddr/BindPort 是 backend 必须在本机开出的 TCP 监听，
	// 连上它就等于连上了 Minecraft 服务器。
	BindAddr string
	BindPort int
	// Timings 由调用方归一化后传入。
	Timings config.Timings
	// LogLevel/LogTo 控制 backend 自身日志的去向。tunnel 模式下
	// stdout 留给逐行 JSON 状态契约，backend 日志必须写文件。
	LogLevel string
	LogTo    string
}

// Backend 是一种隧道方案的实现。
//
// 契约：
//   - Run 阻塞运行直到 ctx 取消或隧道不可恢复地失败；返回前必须
//     释放所有资源。参数校验也在 Run 里做，失败尽早返回错误。
//   - 实现不得自带中转兜底。tunnel 模式靠「探测通 = 打通」判断结果，
//     兜底通道会让探测永远成功，分不清隧道到底建没建成。
//   - params 里无法识别的键必须忽略：服务端可能比 agent 先更新，
//     多下发的参数不该让老 agent 直接失败。
type Backend interface {
	// Name 是凭证与命令行中使用的标识，如 "frp-xtcp"。
	Name() string
	// Run 建立隧道并保持，直到 ctx 取消。params 的键名是各实现
	// 自己的契约，与 Java 侧 Credentials 的对应工厂方法保持一致。
	Run(ctx context.Context, params map[string]string, opts Options) error
}

var registry = map[string]Backend{}

// Register 注册一个 backend，通常在 cmd 侧的注册点统一调用。
// 名字冲突说明构建配置出错，直接 panic 让问题在启动时暴露。
func Register(b Backend) {
	name := b.Name()
	if _, dup := registry[name]; dup {
		panic("backend 重复注册: " + name)
	}
	registry[name] = b
}

// Lookup 按名字查找已注册的 backend。
func Lookup(name string) (Backend, bool) {
	b, ok := registry[name]
	return b, ok
}

// Names 返回所有已注册的 backend 名，按字典序，用于错误提示。
func Names() []string {
	out := make([]string, 0, len(registry))
	for name := range registry {
		out = append(out, name)
	}
	sort.Strings(out)
	return out
}
