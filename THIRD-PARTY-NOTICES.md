# 第三方组件声明

本项目的 agent 把下列组件静态编译进可执行文件一并分发。分发编译产物时
（例如把 agent 打进 mod jar 交给玩家），需要随附本声明。

## frp

- 项目：https://github.com/fatedier/frp
- 版本：v0.70.0
- 版权：Copyright fatedier
- 许可证：Apache License 2.0

frp 是本项目的核心：agent 以库的形式嵌入其客户端（`github.com/fatedier/frp/client`），
用于 xtcp 打洞与隧道建立，而非调用其命令行程序。

Apache-2.0 要求在分发衍生作品或包含作品时保留版权声明并附上许可证副本。
许可证全文见 https://www.apache.org/licenses/LICENSE-2.0 ，
也可从上述项目仓库的 `LICENSE` 获取。

## 其他直接依赖

| 组件 | 版本 | 许可证 |
|---|---|---|
| github.com/samber/lo | v1.47.0 | MIT |
| golang.org/x/net | v0.52.0 | BSD-3-Clause |
| golang.org/x/sync | v0.20.0 | BSD-3-Clause |

上述依赖各自的间接依赖（yamux、quic-go、pion/stun、go-toml 等）随 frp 一并引入，
均为 MIT / BSD / Apache-2.0 等宽松许可。完整清单可用如下命令生成：

```bash
go install github.com/google/go-licenses@latest
go-licenses report ./... > licenses.csv
```

## Java 部分

`mod/core` 不使用任何第三方库，仅依赖 JDK 标准库——这是刻意为之，
详见 [mod/README.md](mod/README.md)。
