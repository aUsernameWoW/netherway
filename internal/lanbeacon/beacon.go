// Package lanbeacon 伪造 Minecraft 的局域网广播，让隧道端口出现在
// 游戏的「多人游戏 → 局域网游戏」列表里，玩家无需手动输入地址。
//
// 协议见 https://github.com/tomsik68/mclauncher-api/wiki/LAN-Server-Discovery ：
// 客户端加入组播组 224.0.2.60:4445，把收到的包的**源 IP** 与 [AD] 里的端口
// 拼成服务器地址。这决定了两件事：
//   - visitor 必须监听 0.0.0.0，因为源 IP 是网卡地址而非 127.0.0.1
//   - 必须显式指定组播出接口，否则在有 VPN / 虚拟网卡的机器上会发错接口
package lanbeacon

import (
	"context"
	"fmt"
	"net"
	"strings"
	"time"

	"golang.org/x/net/ipv4"
)

const (
	groupAddr = "224.0.2.60"
	groupPort = 4445
)

type Beacon struct {
	// MOTD 显示在局域网列表里的名字，支持 § 颜色代码。
	MOTD string
	// Port 是玩家本机上隧道监听的端口。
	Port int
	// Interval 广播间隔，原版 Minecraft 是 1.5s。
	Interval time.Duration
	// LocalOnly 设为 true 时用 TTL=0，包不出本机，
	// 避免同网段其他人看到一个他们连不上的「世界」。
	LocalOnly bool
	// Logf 可为 nil。
	Logf func(format string, args ...any)
}

func (b *Beacon) logf(format string, args ...any) {
	if b.Logf != nil {
		b.Logf(format, args...)
	}
}

// payload 构造广播包。Minecraft 只认这四个标记，标记外的内容会被忽略。
func (b *Beacon) payload() []byte {
	// MOTD 里出现结束标记会截断解析，替换掉以免显示异常。
	motd := strings.ReplaceAll(b.MOTD, "[/MOTD]", "")
	return []byte(fmt.Sprintf("[MOTD]%s[/MOTD][AD]%d[/AD]", motd, b.Port))
}

// candidateInterfaces 返回所有可能承载组播的网卡。
//
// 不去猜哪张是「真正的」网卡：玩家机器上常有 VPN、VMware、Hyper-V、WSL，
// 而 Minecraft 加入组播组用的是 JVM 选的默认接口，无法从外部预知。
// 配合 TTL=0，向所有候选接口都发一遍是安全且最可靠的做法。
func candidateInterfaces() ([]net.Interface, error) {
	all, err := net.Interfaces()
	if err != nil {
		return nil, fmt.Errorf("枚举网卡: %w", err)
	}
	var out []net.Interface
	for _, ifi := range all {
		if ifi.Flags&net.FlagUp == 0 || ifi.Flags&net.FlagMulticast == 0 {
			continue
		}
		if ifi.Flags&net.FlagLoopback != 0 {
			continue
		}
		// 没有 IPv4 地址的接口发不出 IPv4 组播
		addrs, err := ifi.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			if ipnet, ok := a.(*net.IPNet); ok && ipnet.IP.To4() != nil {
				out = append(out, ifi)
				break
			}
		}
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("没有找到支持组播的网卡")
	}
	return out, nil
}

// Run 持续广播直到 ctx 取消。
func (b *Beacon) Run(ctx context.Context) error {
	if b.Interval <= 0 {
		b.Interval = 1500 * time.Millisecond
	}

	dst := &net.UDPAddr{IP: net.ParseIP(groupAddr), Port: groupPort}

	conn, err := net.ListenPacket("udp4", "0.0.0.0:0")
	if err != nil {
		return fmt.Errorf("创建组播发送套接字: %w", err)
	}
	defer conn.Close()

	pc := ipv4.NewPacketConn(conn)
	ttl := 1
	if b.LocalOnly {
		ttl = 0 // 包不离开本机，但 IP_MULTICAST_LOOP 仍会回环给本机的 Minecraft
	}
	if err := pc.SetMulticastTTL(ttl); err != nil {
		return fmt.Errorf("设置组播 TTL: %w", err)
	}
	if err := pc.SetMulticastLoopback(true); err != nil {
		return fmt.Errorf("开启组播回环: %w", err)
	}

	ticker := time.NewTicker(b.Interval)
	defer ticker.Stop()

	var lastReport string
	for {
		ifaces, err := candidateInterfaces()
		if err != nil {
			b.logf("局域网广播: %v（5 秒后重试）", err)
			select {
			case <-ctx.Done():
				return nil
			case <-time.After(5 * time.Second):
				continue
			}
		}

		msg := b.payload()
		var ok []string
		for _, ifi := range ifaces {
			// 关键：不设这个，包会走默认路由；机器上有 VPN 时默认路由
			// 往往是隧道接口，Minecraft 一个包都收不到。
			if err := pc.SetMulticastInterface(&ifi); err != nil {
				continue
			}
			if _, err := pc.WriteTo(msg, nil, dst); err != nil {
				continue
			}
			ok = append(ok, ifi.Name)
		}

		// 只在网卡集合变化时打日志，避免每 1.5 秒刷屏
		if report := strings.Join(ok, ","); report != lastReport {
			if len(ok) == 0 {
				b.logf("局域网广播: 所有网卡发送失败")
			} else {
				b.logf("局域网广播中: 端口 %d，网卡 [%s]", b.Port, report)
			}
			lastReport = report
		}

		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
		}
	}
}
