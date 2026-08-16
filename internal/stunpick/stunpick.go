// Package stunpick 在建立隧道前挑出一个真正可用的 STUN 服务器。
//
// frp 的 natHoleStunServer 只接受单个地址，一旦那台服务器抖动或限流，
// 打洞就会直接失败。实测中 stun.miwifi.com 会间歇性超时——不是彻底不可用，
// 而是偶发，这种故障对玩家表现为「有时候连得上有时候连不上」，最难排查。
//
// 这里在启动 frp 之前先并行探测一批候选，选一个当场验证过的传进去。
package stunpick

import (
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/fatedier/frp/pkg/nathole"

	"github.com/aUsernameWoW/netherway/internal/i18n"
)

// Defaults 是内置候选。排在前面的优先，但实际由谁先返回合格结果决定。
var Defaults = []string{
	"stun.miwifi.com:3478",
	"stun.chat.bilibili.com:3478",
	"stun.easyvoip.com:3478",
	"stun.qq.com:3478",
}

// Parse 把逗号分隔的配置拆成候选列表，空项会被丢弃。
func Parse(s string) []string {
	var out []string
	for _, part := range strings.Split(s, ",") {
		if p := strings.TrimSpace(part); p != "" {
			out = append(out, p)
		}
	}
	return out
}

// Resolve 把配置值（可以是逗号分隔的多个候选）解析成一个当场验证过的地址；
// 空值时使用内置候选。report 用于把选择结果告知用户，可为 nil。
//
// 只有一个候选时不做探测直接返回：省一次往返，让 frp 自己去连、
// 自己报错，错误信息还更准确。
func Resolve(spec string, report func(format string, args ...any)) (string, error) {
	candidates := Parse(spec)
	if len(candidates) == 1 {
		return candidates[0], nil
	}
	if len(candidates) == 0 {
		candidates = Defaults
	}
	picked, err := Pick(candidates, 8*time.Second)
	if err != nil {
		return "", err
	}
	if report != nil {
		report("%s", i18n.T("stun.picked", len(candidates), picked))
	}
	return picked, nil
}

type result struct {
	server string
	err    error
}

// Pick 并行探测候选，返回第一个通过验证的地址。
//
// 判定标准不只是「有响应」，还要求返回至少 2 个映射地址：frp 靠两次探测的
// 结果比对来判断 NAT 的映射行为，只给 1 个地址的服务器（实测
// stun.chat.bilibili.com 就是）会让 frp 直接报 need 2 而失败。提前筛掉，
// 免得选中一个看似能用实则不合格的。
func Pick(candidates []string, timeout time.Duration) (string, error) {
	if len(candidates) == 0 {
		candidates = Defaults
	}
	if len(candidates) == 1 {
		// 只有一个候选就不必探测了，省下一次往返，
		// 让 frp 自己去连、自己报错，错误信息还更准确。
		return candidates[0], nil
	}

	results := make(chan result, len(candidates))
	var wg sync.WaitGroup
	for _, s := range candidates {
		wg.Add(1)
		go func(server string) {
			defer wg.Done()
			addrs, _, err := nathole.Discover([]string{server}, "")
			if err != nil {
				results <- result{server, err}
				return
			}
			if len(addrs) < 2 {
				results <- result{server, i18n.Errorf("stun.tooFew", len(addrs))}
				return
			}
			results <- result{server, nil}
		}(s)
	}
	go func() {
		wg.Wait()
		close(results)
	}()

	deadline := time.After(timeout)
	var failures []string
	for i := 0; i < len(candidates); i++ {
		select {
		case r, ok := <-results:
			if !ok {
				break
			}
			if r.err == nil {
				return r.server, nil
			}
			failures = append(failures, fmt.Sprintf("%s(%v)", r.server, r.err))
		case <-deadline:
			return "", i18n.Errorf("stun.timeout", strings.Join(failures, ", "))
		}
	}
	return "", i18n.Errorf("stun.noneUsable", strings.Join(failures, ", "))
}
