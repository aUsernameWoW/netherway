# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

让 Minecraft 玩家通过 frp 的 xtcp 打洞 P2P 直连服务器，绕过中转节点。
目标服务器是 GTNH（GregTech New Horizons，Forge 1.7.10，跑在现代 JVM 上）。

实测收益：P2P 直连 SLP 往返 **31–49 ms**，对比中转节点 156–214 ms。

仓库包含两个独立但配套的部分：

- **Go agent**（仓库根目录）— 内嵌 frpc 作为库，负责打洞与隧道
- **Java mod core**（`mod/core`）— 供 Minecraft mod 使用，驱动 agent 并在打洞成功后切换连接

## 常用命令

### Go agent

```bash
go build ./... && go vet ./...
```

跨平台构建（Windows/macOS/Linux 五个目标），密钥经 `-ldflags` 注入而不进源码：

```bash
TOKEN=<frps的auth.token> SECRET=<房间密钥> ./build.sh
```

国内网络下拉依赖需要：`export GOPROXY=https://goproxy.cn,direct GOSUMDB=off`

### Java core

没有引入 Gradle 与 JUnit（理由见下）。用 Java 8 的 javac 编译，才能真正验证 Java 8 兼容性：

```bash
JAVA8=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
mkdir -p mod/build/classes   # javac -d 不会创建多级目录
$JAVA8/bin/javac -encoding UTF-8 -Xlint:all -d mod/build/classes $(find mod/core/src -name "*.java")
$JAVA8/bin/java -Dfile.encoding=UTF-8 -cp mod/build/classes cn.ripplecraft.xtcpinmc.core.SelfTest
```

源码含中文，`-encoding UTF-8` 与 `-Dfile.encoding=UTF-8` 都不能省。

`SelfTest` 是自包含的断言集（当前 87 项），无需任何依赖。跑单项测试的方式是在
`SelfTest.main` 里注释掉其余调用——刻意保持简单，没有测试框架的筛选机制。

端到端测试需要真实的 frps 与服务端 agent 在运行，且 classpath 里要有
`natives/<平台>/xtcpinmc`：

```bash
java -cp mod/build/classes:mod/build/testres \
  cn.ripplecraft.xtcpinmc.core.IntegrationTest <frps地址> <端口> <令牌> <stun> <房间> <密钥>
```

## 架构

### 两侧之间的契约

Go agent 与 Java mod 通过 **stdout 上的逐行 JSON** 通信，这是两者唯一的耦合点：

```json
{"event":"starting","backend":"frp-xtcp","port":63128}
{"event":"ready","port":63128,"elapsedMs":1792,"rttMs":31,"version":"1.7.10","online":1}
{"event":"failed","reason":"打洞超时"}
```

字段定义在 Go 侧 `cmd/xtcpinmc/modbridge.go` 的 `event` 结构与 Java 侧
`AgentEvent` 中，**改动必须两边同步**。同样必须两边同步的还有各 backend 的
参数键名：Go 侧实现包的常量（如 `internal/backend/frpxtcp`）↔ Java 侧
`Credentials` 的对应工厂方法（如 `Credentials.frpXtcp`）。

### backend 抽象

具体隧道方案经 `internal/backend` 的接口抽象：一个 backend 只承诺「在本机
指定地址开一个 TCP 端口，通向 MC 服务器」，就绪与否由调用方用 SLP 探测判定。
`tunnel` 子命令里选端口、起 backend、探测、输出 JSON 四件事都与方案无关。

新增一种隧道方案 = 一个 Go 实现包 + `cmd/xtcpinmc/backends.go` 里注册一行 +
Java 侧 `Credentials` 加一个工厂方法（可选，服务端也可直接构造参数表）。
core 与平台适配层不解释参数，无需改动。约束：**backend 实现不得自带中转
兜底**（否则就绪探测分不清打没打通）；**无法识别的参数键必须忽略**（服务端
可能比 agent 先更新）。

凭证 v2 起是「backendId + 参数表」，由服务端决定用哪个 backend；v1
（frp 专用布局）仍可解码，会被翻译成等价的 frp-xtcp 参数表。

### Go agent 的三种运行模式

| 子命令 | 用途 | 关键差异 |
|---|---|---|
| `serve` | 服务器宿主机 | 注册 xtcp + stcp 两个代理 |
| `join` | 独立运行的玩家侧 | 带 stcp 兜底，并做局域网广播 |
| `tunnel` | 供 mod 调用 | **经 backend 抽象、无兜底**，超时即退出，stdout 输出 JSON |

`tunnel` 刻意不带兜底：mod 场景下玩家此刻已通过既有中转隧道连着服务器，
建链失败就该留在那条连接上。更重要的是，有了兜底通道后「隧道可用」的探测会
永远成功，反而分不清到底有没有打通——这条已上升为 backend 接口的契约。
`serve`/`join` 是独立运行的 frp 工具，不走 backend 抽象。

### 就绪判断靠主动探测

frp 没有提供查询 visitor 打洞状态的 API（`StatusExporter` 只覆盖 proxy）。
`internal/mcping` 因此实现了 Minecraft 的 Server List Ping，用游戏自己的握手
判断隧道是否真的可用——顺带确认了服务端进程在响应，而不只是端口被监听着。

### Java core 的分层

`mod/core` 里**没有任何 Minecraft 类型**。碰游戏 API 的只剩三件事——收发自定义
消息、玩家登录事件、触发重连——全部收敛在 `ClientBridge` 接口里。换 Minecraft
版本或 mod 加载器时只需重写那一层（约一两百行），core 原样复用。

`UpgradeController` 是整个流程的状态机：`IDLE → PUNCHING → UPGRADED / GAVE_UP`。

## 关键约束

**core 必须零第三方依赖，且不得引用 Minecraft 类型。** 连 JSON 解析都是手写的
（`Json`，约 150 行）。Minecraft 自带 Gson，但依赖它就等于依赖 Minecraft；而
1.7.10 的类路径上挤着几百个 mod，多一个库就多一分冲突风险。

**Java 代码编译成 Java 8 字节码，但必须能在 Java 8–25 上运行。** GTNH 用
lwjgl3ify 让 1.7.10 跑在 Java 17+ 上，服务端实测用的是 GraalVM 25。只用
`ProcessBuilder`、`java.nio.file`、`java.net` 这类公共稳定 API；**绝不能碰
`sun.misc.*` 或反射访问 JDK 内部**，Java 16+ 的强封装会直接拒绝。

**凭证编解码必须用裸字节**（`Credentials` 中的 `DataOutputStream`），不得使用任何
mod 加载器的序列化机制。Forge 在 1.13 之后把网络 API 整个重写过，绑上去意味着
每换一个版本就要重写一遍编解码。平台适配层只负责搬运 `byte[]`。

**时间参数不得硬编码。** 集中在 Go 侧 `config.Timings` 与 Java 侧 `Timings`，
全部可经命令行/配置文件覆盖。默认值来自实测：建链约 1.8–5 秒，打洞超时默认 15 秒。

## 已知陷阱

这些都是实际踩过并修复的，改动相关代码时注意：

**frp 的 `Secretkey` 是小写 k**（`XTCPProxyConfig`），而 visitor 侧
`VisitorBaseConfig.SecretKey` 是大写 K。frp 自身命名不一致。

**必须手动调用 `frplog.InitLogger`**，`client.NewService` 不会读 Log 配置去建
日志器。不调这一步 frp 会一直往 stdout 打日志，而 `tunnel` 子命令的 stdout 是
留给 JSON 契约的。

**frp v0.70 的 `ServiceOptions` 不接收配置切片**，改为通过
`source.NewConfigSource()` + `ReplaceAll` + `NewAggregator` 注入。

**Java 注释里写出反斜杠加 u 的字面形式会导致编译失败。** Java 在词法分析之前就
处理 Unicode 转义，哪怕出现在注释里。描述这类内容时改用文字说明。

**必须消费 agent 的 stderr 且不能丢弃内容。** 管道缓冲区填满后子进程写日志会
永久阻塞；而丢掉内容的话，启动失败时排查就只剩「进程退出了」。`AgentProcess`
保留最近 8 行并附在失败原因里。

**必须识别重复下发的凭证。** 切换连接后玩家会重新登录，服务端会再下发一次凭证，
不去重就会陷入「升级→重连→再升级」的死循环。

**组播必须显式设置 `IP_MULTICAST_IF`。** 玩家机器上常有 VPN、VMware、WSL 等
虚拟网卡，不指定接口时包会走默认路由（往往是隧道接口），Minecraft 一个包都收不到。
`lanbeacon` 因此向所有候选网卡都发一遍。

**开启局域网广播时 visitor 必须绑 `0.0.0.0`。** Minecraft 用广播包的**源 IP**
（网卡地址，非 `127.0.0.1`）去连；绑回环会导致列表里出现但连不上。

**STUN 服务器必须返回至少 2 个映射地址**，frp 靠两次探测比对判断 NAT 映射行为。
`stun.chat.bilibili.com` 只返回 1 个，会让 frp 报 `need 2` 失败。`stunpick`
在启动前并行探测候选并按此标准筛选——单台 STUN 会间歇性超时（`stun.miwifi.com`
实测如此），表现为玩家「时好时坏」，所以默认值自带多个候选。

## 安全边界

`/bin/` 与 `/mod/build/` 已列入 `.gitignore`：两者下面的产物都内嵌了经 `-ldflags`
注入的 frps 令牌与房间密钥，**绝不能提交**。

跟踪的源码与文档中不含任何真实凭证，`server/` 与 `client/` 下的 toml 模板用的是
占位符。新增示例配置时保持这一点——凭证只经环境变量进入构建，不落盘到版本库。

mod 方案的核心安全价值在于：**凭证由服务端在玩家登录后下发**，而非随客户端分发。
能拿到密钥的必然是通过了服务器既有正版验证/白名单的玩家，因此不需要另建鉴权系统。
`Credentials.toString()` 刻意不输出任何参数值（token 与密钥都在其中），只列键名。
