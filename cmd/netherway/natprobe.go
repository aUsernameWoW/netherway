package main

import (
	"time"

	"github.com/fatedier/frp/pkg/nathole"

	"github.com/aUsernameWoW/netherway/internal/config"
	"github.com/aUsernameWoW/netherway/internal/i18n"
	"github.com/aUsernameWoW/netherway/internal/stunpick"
)

// NAT 形态的事件线上值。Java 侧 QualitySummary.Nat 与 ingest 的 allowed
// 列表用同一组字符串，改动必须三处同步。
const (
	natEasy = "easy"
	natHard = "hard"
)

// probeNat 用 frp 自家的 STUN 探测把本机 NAT 归类为 easy/hard。
//
// 打洞时 frp 内部还会做一次完整探测，这里的结果只作遥测维度，不参与任何
// 打洞决策；全部候选都失败时返回空串（事件里省略该字段，按未知处理）。
// 单个 STUN 服务器就够：frp 的 Discover 对同一服务器发两次绑定请求
// （利用 OTHER-ADDRESS），返回的两个映射地址足以做 Easy/Hard 判定——
// 这与 stunpick「必须返回至少 2 个映射地址」的筛选标准是同一件事。
func probeNat(stunSpec string, diagf func(format string, args ...any)) string {
	if stunSpec == "" {
		stunSpec = config.DefaultSTUNServer
	}
	candidates := stunpick.Parse(stunSpec)
	if len(candidates) == 0 {
		candidates = stunpick.Defaults
	}
	localIPs, _ := nathole.ListLocalIPsForNatHole(10)
	for _, server := range candidates {
		started := time.Now()
		addrs, _, err := nathole.Discover([]string{server}, "")
		if err != nil || len(addrs) < 2 {
			diagf("%s", i18n.T("nat.probeFailed", server, err, len(addrs)))
			continue
		}
		feature, err := nathole.ClassifyNATFeature(addrs, localIPs)
		if err != nil {
			diagf("%s", i18n.T("nat.classifyFailed", server, err))
			continue
		}
		wire := natWire(feature.NatType)
		diagf("%s", i18n.T("nat.classified",
			wire, feature.Behavior, feature.PublicNetwork, server,
			time.Since(started).Milliseconds()))
		return wire
	}
	return ""
}

// natWire 把 frp 的 NatType 常量映射为事件线上值；未知映射为空（省略字段）。
func natWire(natType string) string {
	switch natType {
	case nathole.EasyNAT:
		return natEasy
	case nathole.HardNAT:
		return natHard
	default:
		return ""
	}
}
