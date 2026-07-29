// Package config 定义房间标识、构建期注入的默认值，以及可调的时间参数。
package config

import (
	"errors"
	"strconv"
	"time"
)

// Timings 集中所有时间参数。
//
// 刻意不把这些数值散落在各处硬编码：不同玩家的网络差异很大，
// mod 侧的配置文件需要能覆盖它们，再经命令行传给 agent。
// 默认值来自真机实测（顺利时约 4.8s 建链完成）。
type Timings struct {
	// PunchTimeout 打洞总超时，超时即放弃升级、留在原有中转连接上。
	PunchTimeout time.Duration
	// ProbeInterval 两次就绪探测之间的间隔。
	ProbeInterval time.Duration
	// ProbeTimeout 单次就绪探测的超时。
	ProbeTimeout time.Duration
	// FallbackTimeout 仅用于独立运行模式：打洞未成先走中转的等待时间。
	FallbackTimeout time.Duration
	// BeaconInterval 局域网广播间隔，原版 Minecraft 是 1.5s。
	BeaconInterval time.Duration
	// RetryMinInterval 打洞失败后的最小重试间隔。
	RetryMinInterval time.Duration
	// MaxRetriesAnHour 每小时打洞重试次数上限，防止频繁重试打爆 STUN。
	MaxRetriesAnHour int
}

func DefaultTimings() Timings {
	return Timings{
		PunchTimeout:     15 * time.Second,
		ProbeInterval:    250 * time.Millisecond,
		ProbeTimeout:     2 * time.Second,
		FallbackTimeout:  500 * time.Millisecond,
		BeaconInterval:   1500 * time.Millisecond,
		RetryMinInterval: 90 * time.Second,
		MaxRetriesAnHour: 8,
	}
}

// Normalize 把非正值回填为默认值，避免调用方传 0 导致死循环或空转。
func (t Timings) Normalize() Timings {
	d := DefaultTimings()
	if t.PunchTimeout <= 0 {
		t.PunchTimeout = d.PunchTimeout
	}
	if t.ProbeInterval <= 0 {
		t.ProbeInterval = d.ProbeInterval
	}
	if t.ProbeTimeout <= 0 {
		t.ProbeTimeout = d.ProbeTimeout
	}
	if t.FallbackTimeout <= 0 {
		t.FallbackTimeout = d.FallbackTimeout
	}
	if t.BeaconInterval <= 0 {
		t.BeaconInterval = d.BeaconInterval
	}
	if t.RetryMinInterval <= 0 {
		t.RetryMinInterval = d.RetryMinInterval
	}
	if t.MaxRetriesAnHour <= 0 {
		t.MaxRetriesAnHour = d.MaxRetriesAnHour
	}
	return t
}

// 这些值通过 -ldflags "-X github.com/ripplecraft/xtcpinmc/internal/config.XXX=..."
// 在构建时注入，目的是让玩家拿到的二进制零配置可用，同时密钥不进源码仓库。
// 具体命令见 build.sh。
var (
	DefaultServerAddr = ""
	DefaultServerPort = "7000"
	DefaultToken      = ""
	// 逗号分隔的候选列表。默认就带冗余：实测 stun.miwifi.com 会间歇性超时，
	// 只配一台时那次打洞就直接失败了。启动前会并行探测并选用当场验证过的一台。
	DefaultSTUNServer = "stun.miwifi.com:3478,stun.easyvoip.com:3478,stun.qq.com:3478"
	DefaultRoom       = "gtnh"
	DefaultSecretKey  = ""
	DefaultMOTD       = "Minecraft Server (P2P)"
)

// ServerPortDefault 把构建期注入的字符串端口转成 int，非法值回退到 7000。
func ServerPortDefault() int {
	p, err := strconv.Atoi(DefaultServerPort)
	if err != nil || p <= 0 || p > 65535 {
		return 7000
	}
	return p
}

// Room 标识一个联机房间。两端的 Name 和 SecretKey 必须一致，
// 各类代理/访问者名称都由 Name 派生，避免两端手写不一致。
type Room struct {
	Name      string
	SecretKey string
}

func (r Room) ProxyName() string        { return r.Name + "-p2p" }
func (r Room) RelayProxyName() string   { return r.Name + "-relay" }
func (r Room) VisitorName() string      { return r.Name + "-p2p-visitor" }
func (r Room) RelayVisitorName() string { return r.Name + "-relay-visitor" }

func (r Room) Validate() error {
	if r.Name == "" {
		return errors.New("房间名为空：用 -room 指定，或在构建时注入 DefaultRoom")
	}
	if r.SecretKey == "" {
		return errors.New("密钥为空：用 -secret 指定，或在构建时注入 DefaultSecretKey")
	}
	return nil
}
