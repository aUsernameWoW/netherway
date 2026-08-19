# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

让 Minecraft 玩家和服务器之间走 P2P 直连，游戏流量不经过中转节点。
产品定位是通用的建联 mod：只把服务凭证变成本地 TCP 入口，不做选服、
排名、游戏内容或服务器管理。项目不与任何具体打洞/隧道方案绑定：方案
经 backend 抽象接入（见「backend 抽象」），frp 的 xtcp 只是第一个、
当前默认的 backend，属实现细节而非产品定义——项目早期因想运用 xtcp
而生，但从不 depend on 它。对外文案（README、mod 简介、商店页）的
定位句只讲「P2P 直连」这个目的，不写机制名；运维语境（flag 帮助、
frps 配置文档）照常使用准确名词。Forge 1.7.10 是当前第一个平台适配，
先前的大型整合环境只用于压力/兼容性验证，不是产品边界。

端到端测量数据见 `docs/field-notes.md`。

命名已全量统一为 **netherway**（2026-08-01，原名 xtcpinmc）：二进制名、
Go/Java 包名、MC 自定义频道、缓存目录（`.minecraft/netherway/`）、cfg 文件名、
`NETHERWAY_*` 环境变量、modid 全部一致，版本库中不应再出现旧名。对已部署
环境这是破坏性迁移：旧 mod 客户端监听旧频道收不到凭证，退化为中转直至更新
mod；旧缓存目录成为孤儿（首次中转进服后在新目录自愈）；服主需同步改
`config/netherway.cfg`（原 xtcpinmc.cfg）、authplugin 的 `NETHERWAY_AUTH_KEY`
环境变量名、启动器 instance.cfg 里的二进制路径。

仓库包含两个独立但配套的部分：

- **Go agent**（仓库根目录）— 负责打洞与隧道，方案经 backend 抽象
  可替换；当前的 frp-xtcp backend 内嵌 frpc 作为库，开了内嵌会合点时
  还内嵌 frps（`internal/rendezvous`）
- **Java mod core**（`mod/core`）— 供 Minecraft mod 使用，驱动 agent 并在打洞成功后切换连接

## 常用命令

### Go agent

```bash
go build ./... && go vet ./... && go test ./...
```

Go 测试覆盖 `internal/authplugin`（含与 Java 侧的跨语言已知答案向量）与
`internal/rendezvous`（绑定范围、令牌生成，以及用 frp client 库真连内嵌
会合点的登录/注册 interop 测试——frp 自动 bump 的行为级防线；这些测试
不能开 `-race`，frp 自身关停路径带数据竞争，见 interop_test.go 头注）。
预认证与凭证预取已整体搬进 mod（见「预认证」一节），agent 不再参与，
原先的 `internal/authbridge` 与 `internal/credfile` 已删除。

跨平台构建（Windows/macOS/Linux 五个目标），密钥经 `-ldflags` 注入而不进源码；
真实部署参数（frps 地址等）放 gitignore 的 `build.env`（模板
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
$JAVA8/bin/java -Dfile.encoding=UTF-8 -cp mod/build/classes cn.ripplecraft.netherway.core.SelfTest
```

源码含中文，`-encoding UTF-8` 与 `-Dfile.encoding=UTF-8` 都不能省。

`SelfTest` 是自包含的断言集（当前 552 项），无需任何依赖。跑单项测试的方式是在
`SelfTest.main` 里注释掉其余调用——刻意保持简单，没有测试框架的筛选机制。

端到端测试需要真实的 frps 与服务端 agent 在运行，且 classpath 里要有
`natives/<平台>/netherway`：

```bash
java -cp mod/build/classes:mod/build/testres \
  cn.ripplecraft.netherway.core.IntegrationTest <frps地址> <端口> <令牌> <stun> <房间> <密钥>
```

### Forge 1.7.10 mod（`mod/platform/forge-1.7.10`）

1.7.10 需要反混淆/重混淆工作区，用 RetroFuturaGradle（老 ForgeGradle 1.2
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
{"event":"degraded","port":63128}
```

`degraded` 是 READY 之后的建议性事件（进程不退出）：frp 的
keepTunnelOpenWorker 自检在窗口内连续失败时发出（典型原因是服务端重启
换钥后 frp 拿旧凭证无限重试），预热侧收到即摘下重建并立即预取，正承载
玩家连接的房间刻意不动。frp 日志文本的匹配钉在 Go 侧
`cmd/netherway/health.go`，bump frp 版本时随 interop 测试一并核对。

字段定义在 Go 侧 `cmd/netherway/modbridge.go` 的 `event` 结构与 Java 侧
`AgentEvent` 中，**改动必须两边同步**。遥测的固定枚举再多一处同步点：
`nat`（easy/hard）的线上值在 Go 侧 `natprobe.go`、Java 侧
`QualitySummary.Nat` 与 ingest 的 allowed 列表三处逐字一致，
`failureStage`/`failureCode` 与 backend 归一化枚举同理。同样必须两边同步的还有各 backend 的
参数键名：Go 侧实现包的常量（如 `internal/backend/frpxtcp`）↔ Java 侧
`Credentials` 的对应工厂方法（如 `Credentials.frpXtcp`）。以及每玩家令牌的
格式：Go 侧 `internal/authplugin`（校验）↔ Java 侧 `TokenIssuer`（签发）
必须逐字节一致——两侧各有一个用同一组常量的已知答案测试钉住这一点。

agent 的 stderr 是诊断通道：backend 的参数快照、被忽略的未知键、frp 自身
info 及以上的日志都会回显到这里，mod 逐行转进游戏日志（`bridge.debug`）。

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

凭证 v2 起是「backendId + 参数表」，由服务端决定用哪个 backend；v1
（frp 专用布局）仍可解码，会被翻译成等价的 frp-xtcp 参数表。

### gonc-p2p backend（2026-08-19 起，第二个 backend）

基于 gonc（threatexpert/gonc，MIT，go.mod 钉在 v2.6.9）：信令走公共/自配
MQTT broker（`easyp2p` 包直接 import，绕开其沉重的 `apps` 包），打洞后
TCP 走 TLS 1.3、UDP 走 DTLS+KCP（PSK 派生证书双向认证，镜像 gonc CLI 的
cs=tls 路径），其上跑 smux——每条 MC 连接一个 stream，两端都是本项目的
二进制，stream 层零自有协议。实现在 `internal/backend/goncp2p`（客户端
`Run` = hello 侧；服务端 `Serve` = wait 侧，打洞串行、已建会话并发）。

- **凭证不含任何服务器地址**：broker 即会合点，`rendezvousAt`/
  `needsRendezvousAddress` 对它是无操作/false。参数键 `sessionKey`（一身
  三职：派生 topic、加密信令、派生证书）、`room`（仅展示/去重，Java 侧
  全 backend 必填）、可选 `brokers`/`stunServers`（gonc 语法逗号列表；
  `stunServers` 刻意不叫 `stun`——那个键喂给 modbridge 的 NAT 遥测探测，
  格式不同）、`network`。同步点：Go `goncp2p` 常量 ↔ Java
  `Credentials.goncP2p`。
- 服务端跑 `serve -backend gonc-p2p -O k=v … -port <MC端口>`；
  `ServeCommand.build` 按 backendId 分岔，frp 专属选项（meta-token/
  rendezvous/signing-key）静默忽略，`-proxy-protocol` 两种 backend 都
  转发。`sessionKey=auto` 与 `secret=auto` 同构（ModConfig 生成、重启
  轮换）。
- **frp 专属机制整组不适用**，ModConfig 强制关闭并告警/提示：
  `rendezvous` 按关闭处理（info）、`tokenSigningKey` 置空（warn，两条
  下发路径都不再附 user/userToken）；嗅探器的 TLS 转发分支自然不触发。
  没有 `degraded` 事件（那是 frp 日志文本的翻译）：会话死亡 = `Run`
  返回错误 = agent 退出，mod 走既有的「agent 没了就重建」路径。
- **gonc 无跨版本协议兼容承诺**（对比 frp ±8 小版本窗口）：bump go.mod
  里的 gonc 必须客户端/服务端两侧一起发布，并重跑 `goncp2p` 包的
  glue 测试（`TestMuxGlue` 钉住我们自有的 smux 层）加一次真机冒烟。
- smux 陷阱：传输层 read error 只关未导出的错误通道，`IsClosed()`/
  `CloseChan()` 要等 keepalive 超时（30s）才翻转；硬错误的即时检测靠
  挂一个 `AcceptStream`（wait 侧永不开流，它只在会话死亡时返回）。
- PROXY protocol 已接通（gonc 模式独有的能力）：`serve -backend gonc-p2p
  -proxy-protocol v1|v2` 时，serve 在每条拨向 MC 端口的回环连接前注入
  头（src = 打洞对端公网地址——一会话一玩家，这就是玩家地址；dst = 与
  src 同族的回环 + MC 端口，v1 禁止混族；打洞走 UDP 时头仍声明 TCP，
  描述的是交给 MC 的字节流）。头由 pires/go-proxyproto（frp 同款库）
  组装、每会话构建一次；对端地址解析失败则该会话不带头继续（降级安全，
  MC 侧嗅探剥头对无头流量本就安全）。frp xtcp 至今给不了真实玩家 IP
  （fatedier/frp#2748）。Go 侧 `TestProxyHeader` 与 Java 侧 SelfTest 的
  剥头向量钉住同一组字节。

### Go agent 的运行模式

| 子命令 | 用途 | 关键差异 |
|---|---|---|
| `serve` | 服务器宿主机 | 默认注册 xtcp 代理；通常由服务端 mod 内置启动（`server.runAgent`），参数与下发凭证同源，Java 侧命令组装在 `ServeCommand`；`-meta-token` 向 authplugin 表明身份；`-rendezvous` 启用内嵌会合点（见下节）；`-backend gonc-p2p` 时改跑 gonc 的 wait 循环（`goncp2p.Serve`），frp 旗标不适用（`-proxy-protocol` 例外，两种 backend 都支持） |
| `tunnel` | 供 mod 调用 | **经 backend 抽象、无兜底**，超时即退出，stdout 输出 JSON |
| `authplugin` | frps 宿主机 | frps 的 HTTP server plugin：Login 校验每玩家令牌，NewProxy 只放行静态令牌（serve）；`-allow-legacy` 是迁移开关。内嵌会合点模式下不必独立部署，`internal/rendezvous` 会在回环上自带一份 |

凭证预取不在这张表里：它是 mod 与 MC 服务端之间在 Minecraft 端口上的一次
对话，不经 agent（见下节）。

`tunnel` 刻意不带兜底：mod 场景下玩家此刻已通过既有中转隧道连着服务器，
建链失败就该留在那条连接上。更重要的是，有了兜底通道后「隧道可用」的探测会
永远成功，反而分不清到底有没有打通——这条已上升为 backend 接口的契约。
整个项目已无 stcp 兜底（独立 join/start/stop 模式与 relay 代理、`lanbeacon`
组播广播于 2026-08 一并移除，实测留档在 docs/field-notes.md）。
`serve` 是独立运行的 frp 工具，不走 backend 抽象。

### 内嵌会合点（`server.rendezvous`，默认开）

xtcp 打洞里 frps 只负责在两条控制连接之间转发信令：地址发现靠外部 STUN
（`internal/stunpick`），打通后的数据流根本不经过它。既然会合点只需要收发
TCP，就没有理由必须待在公网——`internal/rendezvous` 把 frps 作为库嵌进
serve 进程，**只监听回环**，玩家的控制连接由平台层的 `ConnectionSniffer`
从 Minecraft 端口转发进去（frp 控制通道是 TLS，首字节 `0x16 0x03`，判定在
core 的 `TlsRecord`）。

公网侧因此对本项目再无任何要求：不装插件、不必支持 xtcp、不必同版本，
只要能把 TCP 转到 Minecraft 端口。租来的隧道服务、nginx stream、一条 NAT
规则都可以，服主不必自建 frps。

整节机制专属于 frp-xtcp backend：嗅探识别的是 frp 的 TLS 控制通道、
内嵌的是 frps。信令模型不同的未来 backend 未必需要会合点，届时另行
设计，不要往这套嗅探/转发上硬套。

几条必须记住的约束：

- **会合点只能绑回环**（`Options.Validate` 强制）。绑到别的地址就等于多开一个
  公网口，而「服务器对外只剩那一个映射端口」是整个设计的立足点。这种回归从
  功能上察觉不到，所以 `rendezvous_test.go` 用「同一端口在各非回环地址上还能
  否被自己绑上」来钉住——**不要改成拨号探测**，开发机上的透明代理会接受任意
  地址端口的连接，让这条测试假通过（第一版就是这么误报的）。
- **监听面显式归零**：kcp/quic/vhost/dashboard/ssh 全部写成 0。零值本来就不开
  监听，写出来是防 frp 改默认值。
- **端口由平台层统一挑**（`Netherway.resolveRendezvousPort`），再分别传给嗅探器
  与 `ServeCommand`，两边必须是同一个数。
- **开了每玩家令牌校验时 serve 自己也得过这一关**：它未指定静态令牌时会本机
  生成一个（`serveEmbedded`），否则会被自己的插件拒登、代理都注册不上。
- **`token=auto` 才会轮换**，与 `secret=auto` 同构、同样在 `ModConfig` 里生成
  （serve 与下发凭证同源）。写死的 token 就是钉死的；而 params 里完全不写
  token 会让凭证缺令牌、客户端回落到构建期默认值（jar 里为空）导致全员失败——
  所以 rendezvous 模式下 token 仍是必填项；新配置默认生成的 params 已带
  `token=auto`、`room=minecraft`、`secret=auto`，无需服主手工补。
  serve 自己那句「未指定即随机生成」只在手工跑 `netherway serve -rendezvous`
  时才够得着。
- 凭证因此**不带 `server`/`serverPort`**，见下节。

### 凭证的服务入口与会合点地址由客户端补

内嵌会合点就在这台服务器的 Minecraft 端口后面，客户端知道自己连的是哪；
服务端反而未必知道自己的公网入口（NAT 后、多入口、域名与实际入口不一致）。
所以 `rendezvous=true` 时 `ModConfig.serverCredentials` 会摘掉这两个键，
由客端在交给 agent 之前用 `Credentials.rendezvousAt` 补齐。不论是否
使用内嵌会合点，客户端还会用 `Credentials.withOrigin` 附上这份凭证
来自的 Minecraft 入口。该 origin 不传给 backend，只用于在本地分隔多服务缓存。

补齐的优先级与 `withExtraParams` 刻意相反：`withDefaultParams` **只补空缺、
不覆盖**。前者是服务端往凭证里塞东西（该覆盖），后者是客户端补服务端没说的
部分（服务端说了就以服务端为准，服主仍可指定别的入口）。

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
`setServerData(null)`（曾以为「(host,port) 构造函数不碰 ServerData 所以切换后
仍在」，2026-08-09 实测证明是错的）——所以 `connectTo` 在清掉之前把地址存进
`switchOrigin`，推导失败时回退到它；该字段只在本次重定向的生命周期内有效，
新连接被识别为与切换无关时立即作废，绝不能拿 A 服的地址补 B 服的凭证。
仍额外挡掉回环，因为玩家也可能经运行期入口覆盖或独立直连条目进服；这两条路径
由 `adoptDirectConnection` 保存的完整凭证回补 origin。

**补不上 origin/会合点地址的凭证绝不落盘**（`rememberAsync` 里拦截）。
缓存文件按「backend + origin + room」命名；两台服务即使共用 backend/room
也不会覆盖。v1.0 之前的旧缓存只按 backend/room，第一份带 origin 的
新凭证落盘时会自动清理对应旧文件。

`server` 与 `serverPort` **必须一起补**：Go 侧缺 `server` 会响亮报错，而缺
`serverPort` 会静默落到 frp 的默认 7000，只补一个的失败查不出所以然。

### 就绪判断靠主动探测

frp 没有提供查询 visitor 打洞状态的 API（`StatusExporter` 只覆盖 proxy）。
`internal/mcping` 因此实现了 Minecraft 的 Server List Ping，用游戏自己的握手
判断隧道是否真的可用——顺带确认了服务端进程在响应，而不只是端口被监听着。

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

密钥轮换的发现是事件驱动为主、对账兜底（2026-08-18 起）：主路径是
agent 的 `degraded` 事件——就绪隧道被轮换废掉后进程不死也不自愈
（`LoginFailExit=false`），frp 自检的连续失败经 `health.go` 翻译成事件，
预热侧立即摘除重建并提前预取；无凭证时预取仍按退避快速重试，已有服务
时退为慢速对账（`client.prefetchRefreshSeconds`，默认 600 秒），只兜
「事件没来」的底（如 frp 升级后日志文本变了）。对账结果与缓存相同时
只记 debug，参数真变了才打 info——稳态下预取应当安静。
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
开头、frp 控制通道以 TLS 的 `0x16 0x03` 开头，最迟第 2 字节就分得开。
平台层的 `ConnectionSniffer` 是唯一的嗅探 handler——预认证、frp 控制通道转发
（内嵌会合点）与 PROXY 剥头**必须合成一个**，三者抢的是同一批首字节。

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

迁移路径：未带令牌的登录（老 mod agent、未配 `serveAuthToken` 的 serve）
走 `-allow-legacy` 开关；老 agent 收到含 `user`/`userToken` 的凭证会
按契约忽略未知键、以 legacy 身份登录，因此服务端可以先于客户端升级。
吊销刻意无状态：全局作废 = 换签发密钥；按玩家即刻吊销不做（白名单已挡住
MC 登录，隧道只通向 MC 端口）。

内嵌会合点模式下这一层原样保留，只是换了个落点：frps 的插件机制只有 HTTP
一种形态（`server.Service` 没有导出进程内注册插件的口子），所以
`internal/rendezvous` 在回环上起一个只服务本进程的 HTTP 端点承载同一个
`authplugin.Handler`。**签发密钥因此不必再放到公网机器上**，经 serve 的
`-signing-key` 从服务端 cfg 直接传入。

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
实测：预热侧 QUIC 拨号超时、升级侧 15 秒才通，正常 1.8–5 秒）。谁后到谁等：
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
接上后连同嗅探时吃掉的首字节一并补送——少送几个字节 frp 的握手就断了头。

**PROXY protocol 头必须嗅探式解析，绝不能要求存在。** serve 的
`-proxy-protocol`（服务端 cfg `server.proxyProtocol`）开启后，当前 frp
（v0.70.0）下 xtcp 的 P2P 流没有 SrcAddr，配了也静默无头，等上游支持
（fatedier/frp#2748，PR #5122 已 stale 关闭且其 visitor 侧 meta 帧的线上
格式不可依赖）；frp 里只有 stcp 中转路径真的带头，而本项目的 stcp 兜底已
随独立 join 模式移除——因此现阶段所有流量都无头。MC 侧剥头
（core `ProxyProtocol` + 平台层 `ConnectionSniffer`）按首字节分叉嗅探，
且只信来自回环的连接（frp 从本机拨入；局域网邻居可伪造头）。这是纯 serve
侧配置，不进凭证参数表，客户端 mod 无需同步改动。

**STUN 服务器必须返回至少 2 个映射地址**，frp 靠两次探测比对判断 NAT 映射行为。
`stun.chat.bilibili.com` 只返回 1 个，会让 frp 报 `need 2` 失败。`stunpick`
在启动前并行探测候选并按此标准筛选——单台 STUN 会间歇性超时（`stun.miwifi.com`
实测如此），表现为玩家「时好时坏」，所以默认值自带多个候选。

## 安全边界

`/bin/` 与 `/mod/build/` 已列入 `.gitignore`：两者下面的产物都内嵌了经 `-ldflags`
注入的 frps 令牌与房间密钥，**绝不能提交**。

跟踪的源码与文档中不含任何真实凭证，示例配置一律用占位符。新增示例配置时
保持这一点——凭证只经环境变量进入构建，不落盘到版本库。

**版本库为「可随时转公开」标准维护**：文档、模板与测试中的示例地址一律用
文档专用段（`203.0.113.x`，不可路由）与 `example.com`；真实部署参数（frps
地址等）只存在于 gitignore 的 `build.env`。README 不写指向
具体机器的运维细节（宿主机上跑着什么、真实端口表）。提交用 GitHub noreply
邮箱（repo 本地 git config 已设）。内嵌密钥的 `build.sh` 产物绝不上公开
Release，公开渠道只发 mod jar（natives 无密钥）。

mod 方案的核心安全价值在于：**凭证由服务端在玩家登录后下发**，而非随客户端分发。
能拿到密钥的必然是通过了服务器既有正版验证/白名单的玩家，因此不需要另建鉴权系统。
`Credentials.toString()` 刻意不输出任何参数值（token 与密钥都在其中），只列键名。

开启预认证（`server.preauth`）后这条边界进一步放宽：客户端进服前就能向
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
暴露面。真正的止损是服务端轮换——`secret=auto` 让房间密钥随服务端每次重启更换，
旧缓存自然失效，玩家走一次中转即自动拿到新密钥。

frp 的全局 token 泄露的滥用面由 authplugin 收敛（见「每玩家令牌」一节）：
部署它并关掉 `-allow-legacy` 后，光有全局 token 连登录都过不了，注册代理更
只认 serve 的静态令牌。全局 token 轮换仍靠手动改 frps 与服务端配置并重启
（客户端侧经缓存自愈闭环恢复，无需操作）。签发密钥与静态令牌都不落盘到
版本库，走服务端 cfg 与 authplugin 的旗标/环境变量；两侧启动日志打印
**密钥指纹**（SHA-256 前 4 字节）供核对，绝不打印密钥本身。

**内嵌会合点把这条边界整个改写了。** 经典模式下凭证里的 `token` 就是公网
frps 的全局准入令牌——分发给几十个玩家的东西正是那台机器的门禁，authplugin
存在的意义就是替它兜底。开了 `server.rendezvous` 后，凭证里的 token 只对
服务端进程内那个会合点有意义，拿到公网机器上什么都打不开，填 `token=auto`
还能随每次重启轮换；服主与隧道提供商之间的凭据从此不经玩家的手。签发密钥也不再
需要放到公网机器上（authplugin 改由服务端在回环上自带）。于是租用他人的
隧道服务成为可行选项：提供商只看到一条普通 TCP 隧道里的不透明字节，
既不需要支持 xtcp，也无从观察打洞信令。

注意这不改变预认证那条边界：帧仍然明文，凭证在网络路径上仍可见。只是可见
的东西贬值了——泄露的隧道凭证换来的仍旧只是一条通向 MC 端口的路，而那个
端口本来就公网可达。
