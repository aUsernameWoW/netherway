package main

// backend 的唯一注册点：新增一种隧道方案 = 一个实现包 + 一个注册文件。
//
// 注册行按 backend 拆在带 build tag 的文件里（backends_frp.go /
// backends_gonc.go），发行变体经 -tags nofrp / nogonc 裁剪二进制——
// 注册表按名字查找，没编译进来的 backend 自然就不可选，serve/tunnel
// 对被裁掉的 backend 报「构建不含」（main.backendNotBuilt）。
// 默认构建（无 tag）包含全部 backend。
