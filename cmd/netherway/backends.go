package main

import (
	"github.com/aUsernameWoW/netherway/internal/backend"
	"github.com/aUsernameWoW/netherway/internal/backend/frpxtcp"
	"github.com/aUsernameWoW/netherway/internal/backend/goncp2p"
)

// 这里是 backend 的唯一注册点：新增一种隧道方案 = 一个实现包 + 这里一行。
//
// 将来引入体积大的 backend（比如内嵌 tsnet）、需要按发行版裁剪二进制时，
// 把对应注册行移进带 build tag 的文件即可——注册表按名字查找，
// 没编译进来的 backend 自然就不可选。
func init() {
	backend.Register(frpxtcp.New())
	backend.Register(goncp2p.New())
}
