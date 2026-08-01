# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

让 Minecraft 玩家通过 frp 的 xtcp 打洞 P2P 直连服务器，绕过中转节点。
目标服务器是 GTNH（GregTech New Horizons，Forge 1.7.10，跑在现代 JVM 上）。

实测收益：P2P 直连 SLP 往返 **31–49 ms**，对比中转节点 156–214 ms。

项目对外名为 **Netherway**；`xtcpinmc` 保留为内部技术标识——二进制名、
Go/Java 包名、MC 自定义频道、缓存目录、cfg 文件名、`XTCPINMC_*` 环境变量、
modid 都不随品牌改名，不要「顺手」统一成新名。

仓库包含两个独立但配套的部分：

- **Go agent**（仓库根目录）— 内嵌 frpc 作为库，负责打洞与隧道
- **Java mod core**（`mod/core`）— 供 Minecraft mod 使用，驱动 agent 并在打洞成功后切换连接

## 常用命令

### Go agent

```bash
go build ./... && go vet ./... && go test ./...
```

Go 测试目前有 `internal/authplugin`（含与 Java 侧的跨语言已知答案向量）、
`internal/authbridge`（stub 皮肤站走通预认证全流程）与 `internal/credfile`
（凭证字节布局黄金向量）。

跨平台构建（Windows/macOS/Linux 五个目标），密钥经 `-ldflags` 注入而不进源码；
真实部署参数（frps 地址、皮肤站等）放 gitignore 的 `build.env`（模板
`build.env.example`），`SERVER_ADDR` 必填：

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

`SelfTest` 是自包含的断言集（当前 212 项），无需任何依赖。跑单项测试的方式是在
`SelfTest.main` 里注释掉其余调用——刻意保持简单，没有测试框架的筛选机制。

端到端测试需要真实的 frps 与服务端 agent 在运行，且 classpath 里要有
`natives/<平台>/xtcpinmc`：

```bash
java -cp mod/build/classes:mod/build/testres \
  cn.ripplecraft.xtcpinmc.core.IntegrationTest <frps地址> <端口> <令牌> <stun> <房间> <密钥>
```

### Forge 1.7.10 mod（`mod/platform/forge-1.7.10`）

1.7.10 需要反混淆/重混淆工作区，用 GTNH 的 RetroFuturaGradle（老 ForgeGradle 1.2
的下载源已失效）。Gradle 进程需要 Java 21+，编译产物仍是 Java 8 字节码：

```bash
./mod/build-natives.sh   # 打进 jar 的 agent 二进制；刻意不注入 TOKEN/SECRET
cd mod/platform/forge-1.7.10
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./gradlew build
```

产物在 `build/libs/`，不带分类器的 jar 是重混淆后的发布版。国内网络下 Gradle
下载大文件常被掐断且不会断点续传：先用 `curl -L -C -` 把大件补进 `~/.m2`
对应路径（`settings.gradle` 里 `mavenLocal()` 排最前就是为这个）。

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
`Credentials` 的对应工厂方法（如 `Credentials.frpXtcp`）。以及每玩家令牌的
格式：Go 侧 `internal/authplugin`（校验）↔ Java 侧 `TokenIssuer`（签发）
必须逐字节一致——两侧各有一个用同一组常量的已知答案测试钉住这一点。
凭证的字节布局与缓存文件名派生也是跨语言契约：Go 侧 `internal/credfile`
（prefetch 落盘）↔ Java 侧 `Credentials.encode()` + `CredentialCache`
（读取预热），改动必须两边同步。

agent 的 stderr 是诊断通道：backend 的参数快照、被忽略的未知键、frp 自身
info 及以上的日志都会回显到这里，mod 逐行转进游戏日志（`bridge.debug`）。

Minecraft 自定义频道（`xtcpinmc`）上还有一条 Java↔Java 的契约：服务端下发
`Credentials`，客户端在升级结束后回传 `UpgradeReport`（失败立即发、成功等
切换落地后发），服务端记进日志。两者都是裸字节编解码、版本化、向前兼容。

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

### Go agent 的运行模式

| 子命令 | 用途 | 关键差异 |
|---|---|---|
| `serve` | 服务器宿主机 | 注册 xtcp + stcp 两个代理；通常由服务端 mod 内置启动（`server.runAgent`），参数与下发凭证同源，Java 侧命令组装在 `ServeCommand`；`-meta-token` 向 authplugin 表明身份 |
| `join` | 独立运行的玩家侧 | 带 stcp 兜底，并做局域网广播；无每玩家令牌（legacy 路径） |
| `tunnel` | 供 mod 调用 | **经 backend 抽象、无兜底**，超时即退出，stdout 输出 JSON |
| `authplugin` | frps 宿主机 | frps 的 HTTP server plugin：Login 校验每玩家令牌，NewProxy 只放行静态令牌（serve）；`-allow-legacy` 是迁移开关 |
| `authbridge` | 服务端宿主机 | 预认证 HTTP 服务：hasJoined 撮合验证 accessToken 后提前签发令牌与凭证；须经 TLS 反代暴露；`secret=auto` 时要随服务端一起重启 |
| `prefetch` | 玩家侧（启动器 Pre-launch） | 领 serverId → 皮肤站 join → authbridge confirm → 凭证写进 mod 缓存目录；失败不阻断游戏，退回既有升级流程 |

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
当前唯一的适配层是 `mod/platform/forge-1.7.10`（服务端下发 + 客户端切换在
同一个 jar 里），要点见其 README：主线程派发走 tick 队列；断开事件必须区分
「升级引发的重连」与「真退出」，真退出用 `shutdown()` 而非 `onDisconnected()`。

`UpgradeController` 是整个流程的状态机：`IDLE → PUNCHING → UPGRADED / GAVE_UP`。

### 预热与凭证缓存

每次下发的凭证都会写进本地缓存（`CredentialCache`，`.minecraft/xtcpinmc/credentials/`）；
下次启动时 `WarmupController` 在 FML 加载期就用缓存凭证后台打洞，并经
`DirectServerEntry` 在服务器列表里维护一个直连条目（agent 的 STARTING 事件
一报出端口就更新地址）。玩家可三种方式进服，互为兜底：

- **直连条目**：进服后平台层按「回环地址 + 预热端口」识别（`ClientEvents.warmupMatch`），
  调 `adoptDirectConnection` 把状态机置为 UPGRADED——随后服务端照常下发的凭证
  命中重复分支并回执成功，零新协议。
- **中转进服**：既有升级流程，但 `runUpgrade` 先查预热隧道，就绪则直接复用
  （`reuseWarmTunnel`），不再对同一房间起第二个 agent。
- **预热失败/无缓存**：一切如旧。凭证轮换后缓存自动经「打洞失败→中转→
  新凭证覆盖」闭环恢复，玩家与服主都无需操作。

预热隧道生命周期是整个游戏进程（断开、回主菜单都不停，它承载着直连条目），
退出由 `AgentProcess` 的 shutdown hook 兜底。

### 每玩家令牌（分层鉴权）

frps 自身的 `auth.token` 保留作基础校验，`authplugin`（frps 的 httpPlugins）
在其上叠加身份层。服务端 mod 配置 `tokenSigningKey` 后，每次登录都为该玩家
签发绑定其 UUID、带有效期（默认 30 天，登录即续签）的令牌，作为凭证参数
`user`/`userToken` 下发；agent 把它们放进 frp 的 `metadatas` 随登录发出，
authplugin 无状态校验（HMAC-SHA256，过期时间明文在令牌里）。

身份**刻意放 metas 而不用 frp 的 `User` 字段**：`User` 会给 visitor 的目标
代理名加 `user.` 前缀（frp 的 `naming.BuildTargetServerProxyName`），一旦用了
就要求两端同步改名；metas 对命名零影响。frps 侧插件在 token 校验**之前**执行
（`server/service.go` 先 `pluginManager.Login` 再 `VerifyLogin`），两层都过
才放行。`NewProxy` 只放行静态令牌（serve 经 `-meta-token` 携带）——玩家侧
只有 visitor，任何注册代理的企图都不是正常流量。

迁移路径：未带令牌的登录（老 mod agent、独立 join、未配 `serveAuthToken` 的
serve）走 `-allow-legacy` 开关；老 agent 收到含 `user`/`userToken` 的凭证会
按契约忽略未知键、以 legacy 身份登录，因此服务端可以先于客户端升级。
吊销刻意无状态：全局作废 = 换签发密钥；按玩家即刻吊销不做（白名单已挡住
MC 登录，隧道只通向 MC 端口）。

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

**必须手动初始化 frp 日志器**，`client.NewService` 不会读 Log 配置去建
日志器。不初始化 frp 会一直往 stdout 打日志，而 `tunnel` 子命令的 stdout 是
留给 JSON 契约的。文件模式不走 `frplog.InitLogger`（它只支持单一去向），
`internal/tunnel` 的 `initFrpLogger` 自行组装 writer：全量进文件，
info 及以上回显到 `LogOptions.Echo`（tunnel 模式即 stderr → 游戏日志）。

**frp v0.70 的 `ServiceOptions` 不接收配置切片**，改为通过
`source.NewConfigSource()` + `ReplaceAll` + `NewAggregator` 注入。

**Java 注释里写出反斜杠加 u 的字面形式会导致编译失败。** Java 在词法分析之前就
处理 Unicode 转义，哪怕出现在注释里。描述这类内容时改用文字说明。

**必须消费 agent 的 stderr 且不能丢弃内容。** 管道缓冲区填满后子进程写日志会
永久阻塞；而丢掉内容的话，启动失败时排查就只剩「进程退出了」。`AgentProcess`
保留最近 8 行并附在失败原因里，同时经 Listener 逐行转发，由 mod 写进游戏日志。

**必须识别重复下发的凭证。** 切换连接后玩家会重新登录，服务端会再下发一次凭证，
不去重就会陷入「升级→重连→再升级」的死循环。

**预热失败绝不能进 `GAVE_UP`。** 那个状态的语义是「本会话不再重试」，会把玩家
进服后的正常升级一并锁死——预热因此是独立的 `WarmupController`，失败当无事发生。
同理，平台层采认直连条目的连接前必须先 `controller.shutdown()` 复位到 IDLE。

**预热与升级的 agent 各写各的日志文件**（`tunnel-warmup.log` / `tunnel.log`）。
预热未出结果时玩家就经中转进服的话，两个 agent 会同时在跑，共用文件会互相踩踏。

**组播必须显式设置 `IP_MULTICAST_IF`。** 玩家机器上常有 VPN、VMware、WSL 等
虚拟网卡，不指定接口时包会走默认路由（往往是隧道接口），Minecraft 一个包都收不到。
`lanbeacon` 因此向所有候选网卡都发一遍。

**开启局域网广播时 visitor 必须绑 `0.0.0.0`。** Minecraft 用广播包的**源 IP**
（网卡地址，非 `127.0.0.1`）去连；绑回环会导致列表里出现但连不上。

**PROXY protocol 头必须嗅探式解析，绝不能要求存在。** serve 的
`-proxy-protocol`（服务端 cfg `server.proxyProtocol`）开启后，当前 frp
（v0.70.0）只有 stcp 中转路径真的带头——frps 把 visitor 连接的公网地址填进
`StartWorkConn.SrcAddr`；xtcp 的 P2P 流没有 SrcAddr，配了也静默无头，等
上游支持（fatedier/frp#2748，PR #5122 已 stale 关闭且其 visitor 侧 meta 帧
的线上格式不可依赖）。老 agent 与直连预热流量也永远无头。因此 MC 侧剥头
（core `ProxyProtocol` + 平台层 `ProxyProtocolInjector`）按首字节分叉嗅探，
且只信来自回环的连接（frp 从本机拨入；局域网邻居可伪造头）。这是纯 serve
侧配置，不进凭证参数表，客户端 mod 无需同步改动。

**STUN 服务器必须返回至少 2 个映射地址**，frp 靠两次探测比对判断 NAT 映射行为。
`stun.chat.bilibili.com` 只返回 1 个，会让 frp 报 `need 2` 失败。`stunpick`
在启动前并行探测候选并按此标准筛选——单台 STUN 会间歇性超时（`stun.miwifi.com`
实测如此），表现为玩家「时好时坏」，所以默认值自带多个候选。

## 安全边界

`/bin/` 与 `/mod/build/` 已列入 `.gitignore`：两者下面的产物都内嵌了经 `-ldflags`
注入的 frps 令牌与房间密钥，**绝不能提交**。

跟踪的源码与文档中不含任何真实凭证，`server/` 与 `client/` 下的 toml 模板用的是
占位符。新增示例配置时保持这一点——凭证只经环境变量进入构建，不落盘到版本库。

**版本库为「可随时转公开」标准维护**：文档、模板与测试中的示例地址一律用
文档专用段（`203.0.113.x`，不可路由）与 `example.com`；真实部署参数（frps
地址、皮肤站域名、MOTD）只存在于 gitignore 的 `build.env`。README 不写指向
具体机器的运维细节（宿主机上跑着什么、真实端口表）。提交用 GitHub noreply
邮箱（repo 本地 git config 已设）。内嵌密钥的 `build.sh` 产物绝不上公开
Release，公开渠道只发 mod jar（natives 无密钥）。

mod 方案的核心安全价值在于：**凭证由服务端在玩家登录后下发**，而非随客户端分发。
能拿到密钥的必然是通过了服务器既有正版验证/白名单的玩家，因此不需要另建鉴权系统。
`Credentials.toString()` 刻意不输出任何参数值（token 与密钥都在其中），只列键名。

部署 `authbridge`（预拉取凭证）后这条边界有意放宽为「皮肤站上任何有效账号」：
hasJoined 只证明账号真实，不证明是本服玩家。这是刻意取舍——谁能进服由 MC
服务端自己的验证决定，不属于本 mod 的职责范围；凭证换来的隧道也只通向 MC
端口。皮肤站换成公共站点前需重新评估这一点。

客户端的凭证缓存（预热用）**刻意明文落盘、不加密**：解密密钥必须与密文同机，
加密对玩家本人只是混淆；凭证本就完整出现在其内存与 agent 命令行里，落盘未增加
暴露面。真正的止损是服务端轮换——`secret=auto` 让房间密钥随服务端每次重启更换，
旧缓存自然失效，玩家走一次中转即自动拿到新密钥。

frp 的全局 token 泄露的滥用面由 authplugin 收敛（见「每玩家令牌」一节）：
部署它并关掉 `-allow-legacy` 后，光有全局 token 连登录都过不了，注册代理更
只认 serve 的静态令牌。全局 token 轮换仍靠手动改 frps 与服务端配置并重启
（客户端侧经缓存自愈闭环恢复，无需操作）。签发密钥与静态令牌都不落盘到
版本库，走服务端 cfg 与 authplugin 的旗标/环境变量；两侧启动日志打印
**密钥指纹**（SHA-256 前 4 字节）供核对，绝不打印密钥本身。
