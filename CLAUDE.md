# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

让 Minecraft 玩家和服务器之间走 P2P 直连，游戏流量不经过中转节点。
产品定位是通用的建联 mod：只把服务凭证变成本地 TCP 入口，不做选服、
排名、游戏内容或服务器管理。项目不与任何具体打洞/隧道方案绑定：方案
经 backend 抽象接入（见「backend 抽象」）。项目早期因想运用 frp 的 xtcp
而生，2026-09-02 起唯一的 backend 换成 gonc-p2p、frp 相关代码整体删除；
backend 抽象原样保留，所以项目同样不 depend on gonc。对外文案（README、
mod 简介、商店页）的定位句只讲「P2P 直连」这个目的，不写机制名；运维语境
（flag 帮助、cfg 注释）照常使用准确名词。Forge 1.7.10 是第一个平台适配，
先前的大型整合环境只用于压力/兼容性验证，不是产品边界。

端到端测量数据见 `docs/field-notes.md`（frp 时代的记录，留档）。

命名已全量统一为 **netherway**（2026-08-01，原名 xtcpinmc）：二进制名、
Go/Java 包名、MC 自定义频道、缓存目录（`.minecraft/netherway/`）、cfg 文件名、
`NETHERWAY_*` 环境变量、modid 全部一致，版本库中不应再出现旧名。

仓库包含两个独立但配套的部分：

- **Go agent**（仓库根目录）— 负责打洞与隧道，方案经 backend 抽象
  可替换；当前的 gonc-p2p backend 把 gonc 的 `easyp2p` 作为库内嵌，
  服务端 serve 开着内嵌会合点时还内嵌一个 MQTT 信令 broker
  （`internal/signalbroker`）
- **Java mod core**（`mod/core`）— 供 Minecraft mod 使用，驱动 agent 并在打洞成功后切换连接

## 常用命令

### Go agent

```bash
go build ./... && go vet ./... && go test ./...
```

Go 测试覆盖 `internal/backend/goncp2p`（自有 smux 层 `TestMuxGlue`、
PROXY 头向量、serve 就绪探测）、`internal/signalbroker`（绑定范围、ACL、
gonc 的 hello/wait 真跑在内嵌 broker 上的 interop 测试）、`internal/i18n`
（en/zh 目录一致性）与 `cmd/netherway`（serve 标记常量等契约钉子）。

二进制不再内嵌任何部署参数：凭证全部由服务端在运行期下发，没有东西需要
经 `-ldflags` 注入。打进 jar 的跨平台 agent 由 `mod/build-natives.sh`
产出，这就是唯一的构建入口（根目录已无 `build.sh`/`build.env`）。

国内网络下拉依赖需要：`export GOPROXY=https://goproxy.cn,direct`

### Java core

没有引入 Gradle 与 JUnit（理由见下）。用 Java 8 的 javac 编译，才能真正验证 Java 8 兼容性：

```bash
JAVA8=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
mkdir -p mod/build/classes   # javac -d 不会创建多级目录
$JAVA8/bin/javac -encoding UTF-8 -Xlint:all -d mod/build/classes $(find mod/core/src -name "*.java")
$JAVA8/bin/java -Dfile.encoding=UTF-8 -cp mod/build/classes cn.ripplecraft.netherway.core.SelfTest
```

源码含中文，`-encoding UTF-8` 与 `-Dfile.encoding=UTF-8` 都不能省。

`SelfTest` 是自包含的断言集（数量以 `mod/README.md` 的「验证状态」一节为准），
无需任何依赖。跑单项测试的方式是在 `SelfTest.main` 里注释掉其余调用——刻意
保持简单，没有测试框架的筛选机制。

跨网络的端到端验证靠真机冒烟：一台开着服务端 mod 的 MC 服务器 + 一个装了
mod 的客户端，看游戏日志里的升级/预热事件即可，仓库里没有独立的 e2e harness。

### Forge 1.7.10 mod（`mod/platform/forge-1.7.10`）

1.7.10 需要反混淆/重混淆工作区，用 RetroFuturaGradle（老 ForgeGradle 1.2
的下载源已失效）。Gradle 进程需要 Java 21+，编译产物仍是 Java 8 字节码：

```bash
./mod/build-natives.sh   # 打进 jar 的 agent 二进制
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
{"event":"starting","backend":"gonc-p2p","port":63128}
{"event":"ready","port":63128,"elapsedMs":1792,"rttMs":31,"version":"1.7.10","online":1}
{"event":"failed","reason":"打洞超时"}
```

`degraded`（`{"event":"degraded","port":…}`）是契约里**保留**的一个建议性
事件：READY 之后进程不退出、但隧道自检认为已经废掉时发出，预热侧收到即
摘下重建并立即预取。当前没有任何 backend 发出它（它原是对 frp 日志文本的
翻译）；Java 侧的处理保留，供将来能自报退化的 backend 使用。gonc 下会话
死亡 = `Run` 返回错误 = agent 退出，mod 走「agent 没了就重建」路径。

字段定义在 Go 侧 `cmd/netherway/modbridge.go` 的 `event` 结构与 Java 侧
`AgentEvent` 中，**改动必须两边同步**。其它必须两边逐字一致的同步点：

- 遥测枚举：`nat`（easy/hard）的线上值在 Go 侧 `natprobe.go`、Java 侧
  `QualitySummary.Nat` 与 ingest 的 allowed 列表三处一致；
  `failureStage`/`failureCode` 与 backend 归一化枚举同理。
- backend 参数键名：Go 侧 `internal/backend/goncp2p` 的常量 ↔ Java 侧
  `Credentials.goncP2p` 工厂方法。
- `brokers` 占位符字面量 `origin`：Go `goncp2p.BrokerOrigin` ↔ Java
  `Credentials.BROKER_ORIGIN`，两侧测试各钉一次。
- serve 状态标记：Go `ServeReadyMarker`/`ServeWarnMarker`
  （`cmd/netherway/serve_gonc.go`）↔ Java `ServeTelemetry.GONC_READY_MARKER`
  /`GONC_WARN_MARKER`，由 Go `TestServeMarkers` 与 SelfTest 各钉一次。
- 嗅探器识别 MQTT CONNECT 的字节：core `MqttConnect` 认的 `0x10` + 剩余长度
  + 协议名 `MQTT`/`MQIsdp` ↔ `internal/signalbroker` 接受的协议版本
  3.1/3.1.1/5——嗅探器不认的连接到不了 broker，broker 不认的版本嗅探器也
  别放行。
- PROXY protocol 头：Go `TestProxyHeader` 与 Java SelfTest 的剥头向量钉住
  同一组字节。
- 邀请码格式（`InviteCode`：前缀 `nw1-`、backend/键编码表、字典）是
  服务端 mod ↔ 客户端 mod 之间的 Java↔Java 契约，append-only，由 SelfTest
  钉住往返与拒绝路径。

agent 的 stderr 是诊断通道：backend 的参数快照、被忽略的未知键、gonc 的
打洞过程日志都会回显到这里，mod 逐行转进游戏日志（`bridge.debug`）。

Minecraft 自定义频道（`netherway`）上还有一条 Java↔Java 的契约：服务端下发
`Credentials`，客户端在升级结束后回传 `UpgradeReport`（失败立即发、成功等
切换落地后发），服务端记进日志。两者都是裸字节编解码、版本化、向前兼容。

### 消息目录（i18n，2026-08-16 起）

所有面向用户的文本（游戏聊天提示、游戏/服务端日志、agent 控制台输出、
flag 帮助）都走代码内嵌的 en/zh 消息目录，**新增用户可见文案必须进目录，
不得再写裸中文/英文字符串**：

- Java 侧：core 的 `L10n`（`L10n.tr(key, args...)`，占位符 `{0}`–`{9}`），
  平台层同用。目录写在代码里而非资源文件——core 用裸 javac 编译、又以源码
  形式编进 forge jar，资源文件两条构建路径都要额外接线；en/zh 并排也不易漏翻。
  一致性由 SelfTest 钉住（key 双语齐全、占位符集合一致、en 无中文）。
  类名刻意叫 `L10n` 不叫 `I18n`：MC 客户端自带 `net.minecraft.client.resources.I18n`。
- Go 侧：`internal/i18n`（`T`/`Errorf`，fmt 风格，目录里的 `%w` 照常包装），
  一致性由包内测试钉住（en/zh 动词序列一致等）。
- 语言选择：cfg 的 `general.language`（auto/en/zh，默认 auto）。auto 时
  客户端跟随 MC 游戏语言（`ClientProxy` 精化）、服务端跟随系统 locale。
  agent 子进程经 `NETHERWAY_LANG` 环境变量继承 mod 的语言
  （`AgentProcess.applyLanguage`，tunnel/预热/内置 serve 三条启动路径都过它）；
  手工运行的 agent 按 `NETHERWAY_LANG` → `LC_ALL`/`LC_MESSAGES`/`LANG` → en 判定。
- **JSON 契约与遥测枚举不经目录**：`event`/`failureStage`/`failureCode`/`nat`
  等线上值永远是稳定枚举，只有 `reason` 这类自由文本才本地化。toString
  一类调试表示也刻意与语言无关。
- 测试的文案断言以 zh 目录为基准：SelfTest 开头 `L10n.use("zh")`，
  Go 侧相关测试文件 `init()` 里 `i18n.Use(i18n.ZH)`。改文案时两侧目录
  与这些断言一起改。
- cfg 注释也走目录（`cfg.*` 键，含 params 默认值里的 `#` 注释行）：按
  `general.language` 在 cfg 首次生成时写死，不随语言热切换。只改注释文案
  不会触发 Forge 回写已有 cfg——注释是裸赋值，`hasChanged` 只看值与新建
  键/类目，服主手改的文件不受影响（ModConfigSelfTest 钉住）；文件里的注释
  要等其它变更导致回写时才换语言。`general.language` 自身的注释保持双语、
  不进目录：语言还没选出来时它也得读得懂。
- 删除代码路径时把只有它引用过的 key 一并删掉，剩下的每个 key 都要有至少
  一处引用（两侧目录都如此）。
- 刻意不进目录的：`docs/`、测试的 check 标签、`backend 重复注册` 这类
  开发期 panic、「运行环境缺少 SHA-256」这类不可达断言。

### backend 抽象

具体隧道方案经 `internal/backend` 的接口抽象：一个 backend 只承诺「在本机
指定地址开一个 TCP 端口，通向 MC 服务器」，就绪与否由调用方用 SLP 探测判定。
`tunnel` 子命令里选端口、起 backend、探测、输出 JSON 四件事都与方案无关。

新增一种隧道方案 = 一个 Go 实现包 + `cmd/netherway/backends.go` 里注册一行 +
Java 侧 `Credentials` 加一个工厂方法（可选，服务端也可直接构造参数表）。
core 与平台适配层不解释参数，无需改动。约束：**backend 实现不得自带中转
兜底**（否则就绪探测分不清打没打通）；**无法识别的参数键必须忽略**（服务端
可能比 agent 先更新）。

凭证是「backendId + 参数表」（v2 布局），由服务端决定用哪个 backend；
`server.backend` 默认 `gonc-p2p`。v1 布局（frp 专用）已随 frp 一并删除，
不再解码。

### gonc-p2p backend（2026-08-19 起，当前唯一的 backend）

基于 gonc（threatexpert/gonc，MIT，go.mod 钉在 v2.6.9）：信令走 MQTT
broker（`easyp2p` 包直接 import，绕开其沉重的 `apps` 包），打洞后
TCP 走 TLS 1.3、UDP 走 DTLS+KCP（PSK 派生证书双向认证，镜像 gonc CLI 的
cs=tls 路径），其上跑 smux——每条 MC 连接一个 stream，两端都是本项目的
二进制，stream 层零自有协议。实现在 `internal/backend/goncp2p`（客户端
`Run` = hello 侧；服务端 `Serve` = wait 侧，打洞串行、已建会话并发）。

- **凭证不含任何服务器地址，会合点地址由客户端补**：broker 即会合点。
  `server.rendezvous`（默认开）下 ModConfig 给凭证注入 `brokers=origin`
  （服主已显式写 `brokers` 则不动），`origin` 是占位符：客户端
  `Credentials.rendezvousAt` 把它换成这份凭证来源的 Minecraft 入口
  `tcp://<host>:<port>`（IPv6 字面量加方括号），玩家的 MQTT CONNECT 于是
  经 Minecraft 端口进嗅探器转发到服务端进程内嵌的回环 broker；服务端
  serve 则把同一个占位符换成自己的 `tcp://127.0.0.1:<rendezvousPort>`
  （`goncp2p.ResolveOriginBroker`）。`needsRendezvousAddress` =
  「brokers 含 origin」。未解析的 `origin` 到达 backend 是错误
  （`parseParams` 拒绝，`goncp2p.originUnresolved`），绝不静默丢弃。
  公共 broker 是显式选择：关掉 rendezvous 或自己写 `brokers`。参数键
  `sessionKey`（一身三职：派生 topic、加密信令、派生证书）、`room`
  （仅展示/去重，Java 侧必填）、可选 `brokers`/`stunServers`
  （gonc 语法逗号列表）、`network`。
- 服务端跑 `serve -backend gonc-p2p -O k=v … -port <MC端口>`
  （`ServeCommand.build`），可选 `-proxy-protocol v1|v2` 与
  `-rendezvous <端口>`（= 在该回环端口起内嵌信令 broker，见「内嵌会合点」）。
  `sessionKey=auto` 由 ModConfig 生成、重启轮换（serve 与下发凭证同源，
  所以要求 `runAgent=true`）。服务端 mod 的三个内置启动器（forge
  ServerAgent ×2、modern `ServerAgentHost`，bukkit 复用后者）经
  `ServeCommand.supportsBackend` 放行，未知 id 才报 `serve.backendUnsupported`。
- **serve 状态标记契约**（2026-09-02 起）：serve 的输出是本地化文本，
  mod 不能靠匹配英文日志识别状态，所以 `cmd/netherway/serve_gonc.go` 给
  状态行加语言无关前缀：就绪行 `[serve-ready]`（`ServeReadyMarker`），
  告警行 `[serve-warn]`（`ServeWarnMarker`，`goncp2p.ServeOptions.Warnf`
  路由的 retry/PROXY 头降级等），普通 info 行不带前缀。Java 侧镜像在 core
  `ServeTelemetry`：前者翻 TUNNEL_READY，后者由三个启动器的 `pumpOutput`
  升到 WARN。这是 serve 输出的**唯一**契约，Java 不得再按日志级别文本分类。
  build.yml 的「serve gonc-p2p 冒烟」用 `-rendezvous` 的内嵌 broker 跑真实
  二进制断言正路（不出网、不装软件）、反路（死端口只见 `[serve-warn]`）与
  「`brokers=origin` 无 `-rendezvous` 立即报错退出」。
  `-rendezvous` 配上不含 `origin` 的显式 `brokers` 列表 = 服主选了外部
  broker：内嵌 broker **不启动**（`embeddedBrokerWanted`，info 行
  `serve.goncExternalBrokers` 说明），否则 `[serve-ready]` 会描述一个没人
  被告知的 broker。反向的错配（会合点关着却手写了 `origin`）由 ModConfig
  告警（`config.goncOriginNeedsRendezvous`），列表原样下发不改写。
  就绪定义 = 至少一个信令 broker 可达：`Serve` 在 wait 循环前用
  `easyp2p.NewMQTTSignalSession` 探测（`probeBrokers`，探测有自己的 deadline
  `brokerProbeTimeout`——paho 的 ConnectRetry 让构造函数对不可达 broker
  永不自行失败），失败经 Warnf 报 `serve.goncBrokerUnreachable` 并 2 秒后
  重试，首次成功调 `OnReady` 恰好一次（ctx 已取消时不再宣告）。
  `-rendezvous` 下探测打的是本进程的回环 broker，`[serve-ready]` 即「内嵌
  broker 已起」。探测只在启动时做一次：就绪之后 broker 全部失联，wait 循环
  会卡在 easyp2p 的 connect 重试里不出声、mod 侧遥测仍是 READY——对内嵌
  回环 broker 这不成立（它与 serve 同生共死），对服主自配的外部 broker 仍是
  已知缺口，留待后续。wait 一轮 30 分钟无人 hello 的例行重武装走 info
  （`serve.goncWaitIdle`，靠 `context.WithTimeoutCause` 的 `errWaitIdle`
  哨兵识别，easyp2p 会把调用方 ctx 的 cause 原样返回），只有真实失败才带
  `[serve-warn]`；空服过夜不该刷告警。
- 嗅探器按首字节分派：MQTT CONNECT 分支（core `MqttConnect`）是会合点
  中继的唯一触发条件，转发机制与预认证/PROXY 剥头共用一个 handler。
- **已知上游缺口：gonc 的 `decryptAES` 不校验 nonce 长度**，收到畸形
  nonce 直接在 paho 路由 goroutine 里 panic、无 recover——凡能往会话
  topic 发布的人都能让 serve 进程（以及订阅同一 topic 的玩家侧 agent）
  整个退出。内嵌 broker 的 ACL 把「能发布」收敛到「知道 topic = 持有
  会话密钥」，即持有凭证的玩家；公共 broker 下则任何人都行。尚未向
  threatexpert/gonc 报告，bump gonc 时核对该处是否已修。
- **gonc 无跨版本协议兼容承诺**：bump go.mod 里的 gonc 必须客户端/服务端
  两侧一起发布，并重跑 `goncp2p` 包的 glue 测试（`TestMuxGlue` 钉住我们
  自有的 smux 层）加一次真机冒烟。
- smux 陷阱：传输层 read error 只关未导出的错误通道，`IsClosed()`/
  `CloseChan()` 要等 keepalive 超时（30s）才翻转；硬错误的即时检测靠
  挂一个 `AcceptStream`（wait 侧永不开流，它只在会话死亡时返回）。
- PROXY protocol：`serve -proxy-protocol v1|v2` 时，serve 在每条拨向 MC
  端口的回环连接前注入头（src = 打洞对端公网地址——一会话一玩家，这就是
  玩家地址；dst = 与 src 同族的回环 + MC 端口，v1 禁止混族；打洞走 UDP 时
  头仍声明 TCP，描述的是交给 MC 的字节流）。头由 pires/go-proxyproto
  组装、每会话构建一次；对端地址解析失败则该会话不带头继续（降级安全，
  MC 侧嗅探剥头对无头流量本就安全）。
- NAT 遥测探测（`cmd/netherway/natprobe.go`）用 easyp2p 自己的 STUN
  分类器，取凭证的 `stunServers` 列表或 gonc 默认值；easyp2p 的
  `easy` → `easy`，`hard` 与 `symm` 都归一为 `hard`（对称 NAT 计作 hard
  是刻意简化，新增 `symm` 值要连 ingest 一起重部署，属后续项）。只探测
  一次、只喂遥测、绝不进打洞路径、有超时上限、不阻塞就绪判定。

### Go agent 的运行模式

| 子命令 | 用途 | 关键差异 |
|---|---|---|
| `serve` | 服务器宿主机 | 跑 gonc 的 wait 循环（`goncp2p.Serve`），通常由服务端 mod 内置启动（`server.runAgent`），参数与下发凭证同源，Java 侧命令组装在 `ServeCommand`；`-rendezvous <端口>` 在回环上起内嵌信令 broker；`-proxy-protocol` 给交给 MC 的连接注入真实玩家地址 |
| `tunnel` | 供 mod 调用 | **经 backend 抽象、无兜底**，`-backend`（默认 gonc-p2p）+ `-O key=value` 接参数，时间参数经 `-timeout`/`-probe-interval`/`-probe-timeout`/`-retry-interval`/`-max-retries-hour` 覆盖，超时即退出，stdout 输出 JSON |

凭证预取不在这张表里：它是 mod 与 MC 服务端之间在 Minecraft 端口上的一次
对话，不经 agent（见「预认证」）。

`tunnel` 刻意不带兜底：mod 场景下玩家此刻已通过既有中转隧道连着服务器，
建链失败就该留在那条连接上。更重要的是，有了兜底通道后「隧道可用」的探测会
永远成功，反而分不清到底有没有打通——这条已上升为 backend 接口的契约。
整个项目没有任何中转兜底（独立 join/start/stop 模式、stcp 兜底与
`lanbeacon` 组播广播于 2026-08 一并移除，实测留档在 docs/field-notes.md）。

### 内嵌会合点（`server.rendezvous`，默认开）

打洞里会合点只负责在两端之间转发信令：地址发现靠 STUN，打通后的数据流
根本不经过它。既然会合点只需要收发 TCP，就没有理由必须待在公网——serve
把 MQTT 信令 broker（`internal/signalbroker`，mochi-mqtt）作为库嵌进自己的
进程，**只监听回环**，玩家的控制连接由平台层的 `ConnectionSniffer` 从
Minecraft 端口转发进去：首字节是 MQTT CONNECT（`0x10` + 剩余长度 + 协议名
`MQTT`/`MQIsdp`，core `MqttConnect`）的连接被接管并中继到回环 broker。

公网侧因此对本项目再无任何要求：不装插件、不必同版本、不必依赖任何公共
MQTT broker，只要能把 TCP 转到 Minecraft 端口。租来的隧道服务、nginx
stream、一条 NAT 规则都可以。gonc 默认的公共 broker 会破坏「会合点归服务端
进程、凭证密钥只对它有意义」这个形状，所以公共 broker 只作显式选择。

信令模型完全不同的未来 backend 未必需要会合点，届时另行设计，不要往这套
嗅探/转发上硬套；但只要会合点是「收发 TCP」，就照这个先例：回环 + 首字节
分派。

几条必须记住的约束：

- **会合点只能绑回环**（`signalbroker.Options` 干脆没有绑定地址字段，回环
  写死）。绑到别的地址就等于多开一个公网口，而「服务器对外只剩那一个映射
  端口」是整个设计的立足点。这种回归从功能上察觉不到，所以
  `signalbroker_test.go` 用「同一端口在各非回环地址上还能否被自己绑上」来
  钉住——**不要改成拨号探测**，开发机上的透明代理会接受任意地址端口的
  连接，让这条测试假通过（frp 时代的第一版就是这么误报的）。
- **broker 能力收紧**：retain/共享订阅关、QoS 上限 1、包上限 64 KiB——
  easyp2p 只用 QoS 1 非 retain 的精确 topic（钉在 `TestGoncSignalingInterop`：
  gonc 的 hello/wait 真跑在我们的 broker 上，bump gonc 或改 broker 能力都
  得过它）。
- **broker 匿名准入，但 ACL 只放精确 topic**（`signalbroker.aclHook`）：
  topic 是会话密钥的哈希、载荷由 easyp2p 加密，broker 拿到的全是不透明
  字节——前提是外人猜不到、也列不出 topic。嗅探器会把任何人发到 Minecraft
  端口的 MQTT CONNECT 都转进来，而 mochi 的 `WildcardSubAvailable` 只是
  CONNACK 里的广告、并不强制，订阅 `#` 就能看到所有在谈会话的 topic，进而
  往里塞垃圾中断交换（畸形 nonce 甚至让 gonc 的解密路径 panic）。所以
  ACL 双向拒绝通配符（`#`/`+`）与 `$SYS` 树，钉在
  `TestStrangerCannotDiscoverTopics`（真 paho 客户端订 `#` 拿到 SUBACK
  0x80，且一次真实 hello/wait 期间收不到任何消息）。匿名可接受仅因为此。
  broker 自身的告警走 `[serve-warn]` 通道。
- **端口由平台层统一挑**（`Netherway.resolveRendezvousPort`），再分别传给嗅探器
  与 `ServeCommand`，两边必须是同一个数。
- 凭证因此**带 `brokers=origin` 占位符**，见下节。

### 凭证的会合点地址由客户端补

内嵌会合点就在这台服务器的 Minecraft 端口后面，客户端知道自己连的是哪；
服务端反而未必知道自己的公网入口（NAT 后、多入口、域名与实际入口不一致）。
所以 `rendezvous=true` 时 `ModConfig.serverCredentials` 给凭证注入
`brokers=origin` 占位符，由客户端在交给 agent 之前用 `Credentials.rendezvousAt`
把每个 `origin` 换成 `tcp://<host>:<port>`（IPv6 加方括号）。这一步是
**替换不是补缺**：`rendezvousAt` 只改写占位符，服主显式写下的其它 broker
地址原样保留（服务端说了就以服务端为准）。不论是否使用内嵌会合点，客户端
还会用 `Credentials.withOrigin` 附上这份凭证来自的 Minecraft 入口。该 origin
不传给 backend，只用于在本地分隔多服务缓存。

三条消费路径的地址来源各不相同，改动时三处都要想到：

| 路径 | 地址来源 |
|---|---|
| 升级（`UpgradeController.onCredentials`） | `ClientBridge.currentServerAddress()`，origin/会合点补齐**发生在落盘之前** |
| 预取（`Prefetcher.refresh`） | 每个候选 `addr`：全部并行请求，成功凭证全部附 origin 落盘 |
| 预热（`WarmupController`） | 不推导，只使用；缓存里仍缺地址就硬拦下来并提示 |

**`currentServerAddress()` 必须返回玩家最初选中的那台服务器，不是当前 socket
的对端。** 升级成功后玩家会重连到本机隧道，服务端此时还会再下发一次凭证
（重复分支），用对端地址补就会把回环写进缓存，下一轮预热便让 agent 去连自己
的回环。Forge 实现取 `Minecraft.currentServerData`，但**切换后它是 null**：
`connectTo` 里的 `loadWorld(null)` 走「退出世界」分支时会连带
`setServerData(null)`（2026-08-09 实测证明「(host,port) 构造函数不碰
ServerData 所以切换后仍在」是错的）——所以 `connectTo` 在清掉之前把地址存进
`switchOrigin`，推导失败时回退到它；该字段只在本次重定向的生命周期内有效，
新连接被识别为与切换无关时立即作废，绝不能拿 A 服的地址补 B 服的凭证。
仍额外挡掉回环，因为玩家也可能经运行期入口覆盖或独立直连条目进服；这两条路径
由 `adoptDirectConnection` 保存的完整凭证回补 origin。

**补不上 origin/会合点地址的凭证绝不落盘**（`rememberAsync` 里拦截）。
缓存文件按「backend + origin + room」命名；两台服务即使共用 backend/room
也不会覆盖。

### 邀请码（2026-09-08 起）

零入口场景：服务器没有任何公网 MC 入口时，凭证既到不了登录也到不了预认证。
`server.rendezvous=false` + 显式 `brokers`（公共或自建）的凭证已经自足
（sessionKey + brokers 即 agent 所需的一切），于是把它折成一段字符串交给
玩家，玩家把它当作「服务器地址」粘进原版服务器列表：

- **编码**：core `InviteCode`，`nw1-` + base64url（无 padding）的紧凑二进制
  （版本字节、backend 编码表、u16 秒级超时、参数表：键编码表 + 值；值可打包
  小写十六进制、或按 URL 片段字典编码）。**不复用 `Credentials.encode`**：
  原版地址栏 1.7.10–1.20.1 一律上限 128 字符，逐字 UTF-8 的两个 broker URL
  就超了。编码表与字典都是 append-only 的线上契约。默认参数约 50 字符，
  两个 broker + STUN 约 112。
- **服务端**只在凭证能自足时打印（`InviteCode.encode` 对带 `origin` 占位的
  凭证返回 null → debug 说明；超长抛异常 → warn 点名参数）。三处启动日志
  （forge ×2 `Netherway.logInviteCode`、modern `ServerRuntime`，bukkit 复用）。
  日志行含密钥，服务端日志归服主；`sessionKey=auto` 下每次重启换码，所以每次
  启动都打印。
- **客户端身份**：邀请码条目没有 host:port，凭证的 origin 是合成的
  `invite-<12 hex of SHA-256(码文)>` + 默认端口（`InviteCode.originOf`）。
  哈希让密钥不进路由日志与缓存文件名；路由表与「玩家正连着哪台服务器」的桥接
  都经 `ServerCandidates.parseEntry` 从条目文本推出同一个 origin，
  `ServerCandidates.parse` 则跳过邀请码（预取无处可问）。
- **来源不是缓存**：`WarmupController.CredentialSource`（平台层 `InviteEntries`
  三份：forge ×2 与 modern shared）每轮被管理线程轮询，与缓存合并成希望集合
  （同键来源覆盖缓存）；条目删掉下一轮即拆隧道。`InviteEntries.rescan` 读
  servers.dat，启动时扫一次，多人界面开着时每秒重扫（玩家刚粘贴的码立即
  开始打洞，不必重启）。经邀请码隧道进服后服务端照常下发的凭证被推导为同一
  origin、命中重复分支，且 `rememberAsync` 对 `isInviteOrigin` 的凭证跳过落盘
  ——缓存一份副本会让删掉条目后隧道仍活着。
- 刻意不做：直连（Direct Connect）按钮里粘邀请码（没预热就没隧道，走原版
  失败即可）、邀请码携带 MC 入口（有入口就直接填地址）。

### 就绪判断靠主动探测

backend 不自报打洞状态；`internal/mcping` 实现了 Minecraft 的 Server List
Ping，用游戏自己的握手判断隧道是否真的可用——顺带确认了服务端进程在响应，
而不只是端口被监听着。这也是「backend 不得自带中转兜底」的原因：有兜底
时探测永远成功。

### Java core 的分层

`mod/core` 里**没有任何 Minecraft 类型**。碰游戏 API 的只剩三件事——收发自定义
消息、玩家登录事件、触发重连——全部收敛在 `ClientBridge` 接口里。换 Minecraft
版本或 mod 加载器时只需重写那一层（约一两百行），core 原样复用。

适配层现有三套（选型与逐版差异见 [`docs/multi-version.md`](docs/multi-version.md)）：

- `mod/platform/forge-1.7.10` — 第一个平台，RetroFuturaGradle。
- `mod/platform/forge-1.12.2` — 与 1.7.10 逐类同构（`cpw.mods.fml` →
  `net.minecraftforge.fml` + 少量改名），同用 RFG。
- `mod/platform/modern` — 1.16.5/1.18.2/1.20.1 各出 Forge+Fabric，用
  Architectury Loom + mojmap，**一套源码同时编两 loader**，但不引入
  Architectury API 运行期依赖（凭证频道保持裸 vanilla custom payload）。
  绝大部分逻辑在 `modern/shared`，逐版差异经各版本 `Version*` 类与两个
  客户端 Mixin 隔离；服务端 Netty 注入统一走 `ServerConnectionListener$1`
  的一个 Mixin。1.13+ 频道名从裸 `netherway` 变为 `netherway:main`
  （ResourceLocation 强制 namespace）。
- `mod/platform/bukkit` — 仅服务端的 Spigot/Paper 插件（spigot-api 1.13+，
  单 jar），复用 modern 的 `ServerAgentHost`。

各适配层要点见其 README：主线程派发（1.7.10/1.12.2 走 tick 队列，modern 走
`Minecraft.execute`）；断开事件必须区分「升级引发的重连」与「真退出」，真退出用
`shutdown()` 而非 `onDisconnected()`。

`UpgradeController` 是整个流程的状态机：`IDLE → PUNCHING → UPGRADED / GAVE_UP`。

### 预热与凭证缓存

每次下发的凭证都会按 Minecraft 入口写进本地缓存
（`CredentialCache`，`.minecraft/netherway/credentials/`）。`WarmupController` 在 FML
加载期为所有凭证建立独立状态：**打洞严格串行，READY 隧道同时守望**。
每个服务有自己的退避/失败窗口与 agent 日志，一个服务打不通不阻塞其它服务。
默认 `WarmupEntryRouter` 只在内存中发布「真实 origin → READY 回环端口」映射；
`RouteAwareGuiHandler` 在玩家点击原版服务器列表时用临时 `ServerData` 副本连接，
绝不把回环写进 `servers.dat`。`client.replaceServerEntries=false` 时才由
`DirectServerEntry` 为每份凭证维护一个带 origin 的独立条目。

凭证来源除缓存外还有 mod 内建预取（`Prefetcher`）：
平台层把游戏会话（`SessionIdentity`）与候选地址交给 core，候选由
`ServerCandidates` 组装——客户端 cfg 的 `client.prefetchServers` 优先，
其余来自服务器列表（server.dat，`prefetchServers` 留空时自动扫描）。
所有候选用有界线程池并行请求，成功结果全部入缓存；预认证不打洞，
因此这种并行不干扰 NAT。密钥轮换后只重建对应服务的隧道。

密钥轮换的发现（2026-09-02 起）：gonc 没有 `degraded` 事件——服务端重启
换钥后，旧会话死亡 = agent 退出，预热侧走既有的「agent 没了就重建」路径，
下一轮打洞失败即触发预取拿新密钥；无凭证时预取按退避快速重试，已有服务
时退为慢速对账（`client.prefetchRefreshSeconds`，默认 600 秒）。对账结果
与缓存相同时只记 debug，参数真变了才打 info——稳态下预取应当安静。
`degraded` 仍是契约里的保留事件，Java 侧收到照旧摘下重建，只是今天没有
backend 会发它。

**agent 不认识的 backend 的缓存凭证必须被淘汰**：agent 对 `-backend` 未注册
的名字回 `failed`（stage `start`、code `backend_unknown`），预热/升级路径
收到这个 code 时把对应缓存文件删掉，而不是当普通失败退避重试——否则
frp 时代留下的缓存文件会永远占着串行打洞的槽位。

玩家可三种方式进服，互为兜底：

- **原条目运行期覆盖（默认）**：点击时目标隧道已经 READY，就把这一次连接直接
  解析到回环端口；持久列表仍保存真实入口。Forge 1.7.10 没有连接前事件，所以只
  替换原版 `GuiMultiplayer`，其他 mod 的自定义子类原样放行。列表的延迟探测走
  同一张路由表：隧道 READY 时显示的就是直连延迟（探测发往临时副本、结果逐
  tick 镜像回真实条目，真实条目的地址从不改写），没打通自然回落到中转读数，
  路由变化会触发条目重新探测。
- **独立直连条目（覆盖关闭）**：与默认覆盖一样，进服后平台层按「回环地址 +
  预热端口」识别（`ClientEvents.warmupMatch`），
  调 `adoptDirectConnection` 把状态机置为 UPGRADED——随后服务端照常下发的凭证
  命中重复分支并回执成功，零新协议。
- **中转进服**：既有升级流程，但 `runUpgrade` 先查预热隧道，就绪则直接复用
  （`reuseWarmTunnel`），不再对同一房间起第二个 agent；若玩家在预热 READY 前
  已进入服务器，READY 后仍立即切换——包括升级已 GAVE_UP 之后：预热 READY
  会回调 `rescueFromWarmTunnel`（`WarmupController.ReadyObserver`），只要
  `activeKey` 匹配就从 GAVE_UP 就地切换（`client.redirectOnWarmReady`，默认
  开）。打洞互斥保证该回调必然晚于同轮 giveUp 的状态提交，单触发点无竞态；
  每房间每会话最多自动切换 2 次，计数跨 shutdown 存活以免隧道不稳时反复
  打断玩家（典型时序 2026-08-16 CI 实测：升级 15 秒超时先败，让路的预热
  随后 5 秒打通，此前玩家只能手动重连才走上直连）。
- **预热失败/无缓存**：一切如旧。凭证轮换后优先由下一轮预取直接取回新
  密钥；没有可预取的地址时仍走「打洞失败→中转→新凭证覆盖」闭环恢复，
  玩家与服主都无需操作。

所有预热隧道的生命周期是整个游戏进程（断开、回主菜单都不停），
退出由 `AgentProcess` 的 shutdown hook 兜底。

### 预认证（在 MC 端口上换凭证）

玩家第一次启动、或密钥轮换之后本地没有任何可用凭证，而预热打洞需要凭证
才能开始。预认证解决这个先有鸡还是先有蛋：在**不登录游戏**的前提下向
MC 端口请求一份凭证。

**整个交换在 Minecraft 那一个端口上完成，服务器不多开任何监听端口。**
帧靠首字节与 MC 流量分叉：预认证帧以 `NWAY` 开头，而 MC 现代握手第 2 字节
是包 id `0x00`、legacy ping 以 `0xFE` 开头、PROXY protocol 以 `'P'` 或 `0x0D`
开头、信令的 MQTT CONNECT 以 `0x10`（CONNECT，flags 0）开头、随后是 1–4
字节的 MQTT 剩余长度（永不为 0，故与 MC 握手的包 id `0x00` 分得开）与协议名
`00 04 'M' 'Q' 'T' 'T'`（3.1.1/5）或 `00 06 'M' 'Q' 'I' 's' 'd' 'p'`（3.1），
最多 13 字节判定（core `MqttConnect`，三态：是/否/还要字节）。最迟第 2 字节
就分得开。平台层的 `ConnectionSniffer` 是唯一的嗅探 handler——预认证、
会合点控制通道转发（MQTT CONNECT）与 PROXY 剥头**必须合成一个**，三者抢的
是同一批首字节。

- 协议：core 的 `PreauthProtocol`（裸字节、版本化、有界），
  服务端 `PreauthService` ↔ 客户端 `PreauthClient`，两侧都是 Java。
- 流程：单步请求-响应。客户端发 `OP_REQUEST`（自报用户名/UUID），
  服务端直接回凭证（或拒绝原因）。不做任何身份验证——准入交给 MC 服务端
  自己的白名单与正版验证，本 mod 只管把凭证送出去。这是刻意的分工：
  鉴权是 MC 服务端自己的事，本 mod 不多管闲事。
- **帧不加密**，凭证以明文过网。这是刻意取舍，换取「只暴露一个端口」。

信任边界与 PROXY 剥头刻意不同：**PROXY 头只信回环**（头谁都能伪造），
**预认证帧接受任何来源**——预下发不做身份验证，准入交给 MC 服务端自己的
白名单与正版验证。

## 关键约束

**core 必须零第三方依赖，且不得引用 Minecraft 类型。** 连 JSON 解析都是手写的
（`Json`，约 150 行）。Minecraft 自带 Gson，但依赖它就等于依赖 Minecraft；而
1.7.10 的类路径上挤着几百个 mod，多一个库就多一分冲突风险。

**Java 代码编译成 Java 8 字节码，但必须能在 Java 8–25 上运行。**
Forge 1.7.10 玩家可能通过各种现代运行时方案使用 Java 17+。只用
`ProcessBuilder`、`java.nio.file`、`java.net` 这类公共稳定 API；**绝不能碰
`sun.misc.*` 或反射访问 JDK 内部**，Java 16+ 的强封装会直接拒绝。

**凭证编解码必须用裸字节**（`Credentials` 中的 `DataOutputStream`），不得使用任何
mod 加载器的序列化机制。Forge 在 1.13 之后把网络 API 整个重写过，绑上去意味着
每换一个版本就要重写一遍编解码。平台适配层只负责搬运 `byte[]`。
`FORMAT_VERSION` 与 v2 布局不变，本构建落盘的缓存凭证必须一直能解码。

**时间参数不得硬编码。** 集中在 Go 侧 `config.Timings` 与 Java 侧 `Timings`，
全部可经命令行/配置文件覆盖。默认值来自实测：建链约 1.8–5 秒，打洞超时默认 15 秒。

## 已知陷阱

这些都是实际踩过并修复的，改动相关代码时注意：

**Java 注释里写出反斜杠加 u 的字面形式会导致编译失败。** Java 在词法分析之前就
处理 Unicode 转义，哪怕出现在注释里。描述这类内容时改用文字说明。

**`tunnel` 子命令的 stdout 只留给 JSON 契约。** 任何库（gonc 的 easyp2p、
paho）往 stdout 写日志都会污染事件流，日志一律导向文件/stderr。

**必须消费 agent 的 stderr 且不能丢弃内容。** 管道缓冲区填满后子进程写日志会
永久阻塞；而丢掉内容的话，启动失败时排查就只剩「进程退出了」。`AgentProcess`
保留最近 8 行并附在失败原因里，同时经 Listener 逐行转发，由 mod 写进游戏日志。

**必须识别重复下发的凭证。** 切换连接后玩家会重新登录，服务端会再下发一次凭证，
不去重就会陷入「升级→重连→再升级」的死循环。

**预热失败绝不能进 `GAVE_UP`。** 那个状态的语义是「本会话不再主动打洞」，会把
玩家进服后的正常升级一并锁死——预热因此是独立的 `WarmupController`，失败当无事
发生。同理，平台层采认经入口覆盖或独立直连条目建立的连接前必须先
`controller.shutdown()` 复位到 IDLE。注意 GAVE_UP 锁的只是打洞：预热隧道
后续就绪时仍会经 `rescueFromWarmTunnel` 把连接就地切换过去（零打洞成本，
与「别反复折腾玩家网络」的本意不冲突，有次数上限）。

**预热与升级的 agent 各写各的日志文件**（`tunnel-warmup.log` / `tunnel.log`）。
预热未出结果时玩家就经中转进服的话，两个 agent 会同时在跑，共用文件会互相踩踏。

**等待 agent 终态的窗口必须与 `-timeout` 同源（凭证下发值优先）。**
服务端可随凭证下发 `punchTimeoutMs`（2026-08-10 实测下发过 1 小时），取值
收敛在 `Timings.punchTimeoutMs(long)` / `outcomeWaitMs(long)`；等自己起的
agent 若用本地配置的 `outcomeWaitMs()`，mod 会抢在 agent 自己的超时之前把它
掐掉——HardNAT 常态要两轮打洞，第二轮根本来不及开始。

**预热与升级不得同时打洞。** 同一 NAT 上并发打两个洞会互相干扰（2026-08-09
实测：预热侧拨号超时、升级侧 15 秒才通，正常 1.8–5 秒）。谁后到谁等：
预热每轮打洞前看升级是否 PUNCHING（`UpgradeController` 构造时挂上的
`UpgradeGate`），升级起自己的 agent 前等预热的这轮出结果（`awaitWarmupAttempt`，
出来恰好就绪就直接复用）。两个方向的让路等待都有界，但界不是本地配置——
取对方经 `punchWaitBoundMs` 公布的这轮实际预算（可能来自服务端下发的凭证，
远长于本地配置；拿本地配置猜会提前到点、恰好撞回并发打洞）。轮询在对方出
结果时提前退出，条件互斥不会死锁。已就绪的隧道只是守望进程、不在打洞，
不触发让路。

**独占连接后必须摘掉下游 handler。** MC 的接入链第一个是
`ReadTimeoutHandler(FMLNetworkHandler.READ_TIMEOUT)`（默认 30 秒），而嗅探器
`addFirst` 挂在它前面。一旦进入独占模式（预认证或中继）就不再 `fireChannelRead`，
那个 handler 收不到读事件就永远不重置计时，30 秒一到把连接掐掉——对承载整场
游戏的中继连接是致命的，对预认证则会让 40 秒宽限变成死代码。`Sniffer.takeover`
从 pipeline 尾部逐个摘到自己为止（不按固定名字列表，其它 mod 可能加了自己的
handler）。摘掉 `packet_handler` 后下游无人消化 IO 异常，所以独占模式还要自行
`exceptionCaught` 收场。

**中继的背压必须由对端可写性驱动。** Netty 官方 HexDumpProxy 那套
「`autoRead=false` + 写完成回调里再 `read()`」在 1.7.10 的 Netty 4.0.10 上会
死锁：回环上的写常常同步完成，那个 `read()` 正好落在读循环内部，被循环结尾的
`removeReadOp` 吞掉，实测几百 KB 即卡死。正确写法是 `writeAndFlush` 后判
`peer.isWritable()`，不可写就 `setAutoRead(false)`，在对端的
`channelWritabilityChanged` 里恢复。另外拨号会合点是异步的，这期间到达的字节
要继续攒进 `pending`（上限对中继单列，用预认证的帧长上限去卡会误杀正常连接），
接上后连同嗅探时吃掉的首字节一并补送——少送几个字节 MQTT 的 CONNECT 就断了头。

**PROXY protocol 头必须嗅探式解析，绝不能要求存在。** serve 的
`-proxy-protocol`（服务端 cfg `server.proxyProtocol`）开启后，只有经打洞隧道
进来的连接带头；预认证、中继进来的信令、其它 mod 或代理拨来的回环连接都
无头。MC 侧剥头（core `ProxyProtocol` + 平台层 `ConnectionSniffer`）按首字节
分叉嗅探，且只信来自回环的连接（serve 从本机拨入；局域网邻居可伪造头）。
这是纯 serve 侧配置，不进凭证参数表，客户端 mod 无需同步改动。

## 安全边界

`/bin/` 与 `/mod/build/` 已列入 `.gitignore`（构建产物）。跟踪的源码与文档中
不含任何真实凭证，示例配置一律用占位符。

**版本库为公开标准维护**：文档、模板与测试中的示例地址一律用文档专用段
（`203.0.113.x`，不可路由）与 `example.com`；README 不写指向具体机器的运维
细节（宿主机上跑着什么、真实端口表）。提交用 GitHub noreply 邮箱（repo 本地
git config 已设）。公开渠道只发 mod jar；agent 二进制不内嵌任何密钥。

mod 方案的核心安全价值在于：**凭证由服务端在玩家登录后下发**，而非随客户端分发。
能拿到密钥的必然是通过了服务器既有正版验证/白名单的玩家，因此不需要另建鉴权系统。
`Credentials.toString()` 刻意不输出任何参数值（密钥在其中），只列键名。

**`sessionKey` 就是全部准入故事。** 它一身三职（派生信令 topic、加密信令、
派生 TLS/DTLS 双向认证的证书），持有它 = 能与服务端建立隧道；隧道只通向 MC
端口，而那个端口本来就公网可达。`sessionKey=auto` 让它随服务端每次重启轮换，
旧凭证自然失效，玩家经缓存自愈闭环拿到新密钥。**每玩家身份刻意不是本 mod
的事**：不签发个人令牌、不做按玩家吊销——谁能进服由 MC 服务端的白名单与
正版验证决定，全局作废 = 重启（或改 `sessionKey`）。凭证里没有任何对第三方
机器有效的凭据：会合点归服务端进程，公网侧只是一条哑 TCP 隧道，租来的转发
服务看到的是不透明字节，既不需要支持任何协议，也无从观察打洞信令。

开启预认证（`server.preauth`，默认开）后这条边界进一步放宽：客户端进服前就能向
MC 端口请求凭证，服务端不做身份验证直接回——准入交给 MC 服务端自己的
白名单与正版验证。这是刻意取舍——谁能进服由 MC 服务端自己决定，不属于
本 mod 的职责范围；凭证换来的隧道也只通向 MC 端口。

预认证的帧**明文**，凭证因此在网络路径上可见（见「预认证」一节）。这是为
「只暴露一个端口」付出的代价，已知且刻意。止损同样是服务端轮换密钥。

预取默认扫描服务器列表（server.dat）里的所有条目：玩家自己加的服务器
地址就是要玩的服，向它们发用户名/UUID 不构成隐私问题——玩家迟早要进服，
UUID 本身也不是敏感信息。

客户端的凭证缓存（预热用）**刻意明文落盘、不加密**：解密密钥必须与密文同机，
加密对玩家本人只是混淆；凭证本就完整出现在其内存与 agent 命令行里，落盘未增加
暴露面。真正的止损是服务端轮换。

内嵌 broker 的安全前提是 ACL（见「内嵌会合点」）：匿名准入只因外人猜不到
也列不出会话 topic；放开通配符订阅就等于把所有在谈会话交给任何能连到 MC
端口的人。改 broker 能力时先过 `TestStrangerCannotDiscoverTopics`。
