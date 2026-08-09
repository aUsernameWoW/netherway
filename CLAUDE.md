# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

让 Minecraft 玩家通过 frp 的 xtcp 打洞 P2P 直连服务器，绕过中转节点。
目标服务器是 GTNH（GregTech New Horizons，Forge 1.7.10，跑在现代 JVM 上）。

实测收益：P2P 直连 SLP 往返 **31–49 ms**，对比中转节点 156–214 ms。

命名已全量统一为 **netherway**（2026-08-01，原名 xtcpinmc）：二进制名、
Go/Java 包名、MC 自定义频道、缓存目录（`.minecraft/netherway/`）、cfg 文件名、
`NETHERWAY_*` 环境变量、modid 全部一致，版本库中不应再出现旧名。对已部署
环境这是破坏性迁移：旧 mod 客户端监听旧频道收不到凭证，退化为中转直至更新
mod；旧缓存目录成为孤儿（首次中转进服后在新目录自愈）；服主需同步改
`config/netherway.cfg`（原 xtcpinmc.cfg）、authplugin 的 `NETHERWAY_AUTH_KEY`
环境变量名、启动器 instance.cfg 里的二进制路径。

仓库包含两个独立但配套的部分：

- **Go agent**（仓库根目录）— 内嵌 frpc 作为库，负责打洞与隧道；
  开了内嵌会合点时还内嵌 frps（`internal/rendezvous`）
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
$JAVA8/bin/java -Dfile.encoding=UTF-8 -cp mod/build/classes cn.ripplecraft.netherway.core.SelfTest
```

源码含中文，`-encoding UTF-8` 与 `-Dfile.encoding=UTF-8` 都不能省。

`SelfTest` 是自包含的断言集（当前 370 项），无需任何依赖。跑单项测试的方式是在
`SelfTest.main` 里注释掉其余调用——刻意保持简单，没有测试框架的筛选机制。

端到端测试需要真实的 frps 与服务端 agent 在运行，且 classpath 里要有
`natives/<平台>/netherway`：

```bash
java -cp mod/build/classes:mod/build/testres \
  cn.ripplecraft.netherway.core.IntegrationTest <frps地址> <端口> <令牌> <stun> <房间> <密钥>
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

字段定义在 Go 侧 `cmd/netherway/modbridge.go` 的 `event` 结构与 Java 侧
`AgentEvent` 中，**改动必须两边同步**。同样必须两边同步的还有各 backend 的
参数键名：Go 侧实现包的常量（如 `internal/backend/frpxtcp`）↔ Java 侧
`Credentials` 的对应工厂方法（如 `Credentials.frpXtcp`）。以及每玩家令牌的
格式：Go 侧 `internal/authplugin`（校验）↔ Java 侧 `TokenIssuer`（签发）
必须逐字节一致——两侧各有一个用同一组常量的已知答案测试钉住这一点。

agent 的 stderr 是诊断通道：backend 的参数快照、被忽略的未知键、frp 自身
info 及以上的日志都会回显到这里，mod 逐行转进游戏日志（`bridge.debug`）。

Minecraft 自定义频道（`netherway`）上还有一条 Java↔Java 的契约：服务端下发
`Credentials`，客户端在升级结束后回传 `UpgradeReport`（失败立即发、成功等
切换落地后发），服务端记进日志。两者都是裸字节编解码、版本化、向前兼容。

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

### Go agent 的运行模式

| 子命令 | 用途 | 关键差异 |
|---|---|---|
| `serve` | 服务器宿主机 | 注册 xtcp 代理；通常由服务端 mod 内置启动（`server.runAgent`），参数与下发凭证同源，Java 侧命令组装在 `ServeCommand`；`-meta-token` 向 authplugin 表明身份；`-rendezvous` 启用内嵌会合点（见下节） |
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

### 内嵌会合点（`server.rendezvous`，默认关）

xtcp 打洞里 frps 只负责在两条控制连接之间转发信令：地址发现靠外部 STUN
（`internal/stunpick`），打通后的数据流根本不经过它。既然会合点只需要收发
TCP，就没有理由必须待在公网——`internal/rendezvous` 把 frps 作为库嵌进
serve 进程，**只监听回环**，玩家的控制连接由平台层的 `ConnectionSniffer`
从 Minecraft 端口转发进去（frp 控制通道是 TLS，首字节 `0x16 0x03`，判定在
core 的 `TlsRecord`）。

公网侧因此对本项目再无任何要求：不装插件、不必支持 xtcp、不必同版本，
只要能把 TCP 转到 Minecraft 端口。租来的隧道服务、nginx stream、一条 NAT
规则都可以，服主不必自建 frps。

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
  所以 rendezvous 模式下 token 仍是必填项，只是填什么都行。
  serve 自己那句「未指定即随机生成」只在手工跑 `netherway serve -rendezvous`
  时才够得着。
- 凭证因此**不带 `server`/`serverPort`**，见下节。

### 凭证里的会合点地址由客户端补

内嵌会合点就在这台服务器的 Minecraft 端口后面，客户端知道自己连的是哪；
服务端反而未必知道自己的公网入口（NAT 后、多入口、域名与实际入口不一致）。
所以 `rendezvous=true` 时 `ModConfig.serverCredentials` 会摘掉这两个键，
由客户端在交给 agent 之前用 `Credentials.rendezvousAt` 补齐。

补齐的优先级与 `withExtraParams` 刻意相反：`withDefaultParams` **只补空缺、
不覆盖**。前者是服务端往凭证里塞东西（该覆盖），后者是客户端补服务端没说的
部分（服务端说了就以服务端为准，服主仍可指定别的入口）。

三条消费路径的地址来源各不相同，改动时三处都要想到：

| 路径 | 地址来源 |
|---|---|
| 升级（`UpgradeController.onCredentials`） | `ClientBridge.currentServerAddress()`，补齐**发生在落盘之前** |
| 预取（`Prefetcher.refresh`） | 循环里的候选 `addr`——全流程唯一确切知道凭证来自哪台服务器的地方 |
| 预热（`WarmupController`） | 不推导，只使用；缓存里仍缺地址就硬拦下来并提示 |

**`currentServerAddress()` 必须返回玩家最初选中的那台服务器，不是当前 socket
的对端。** 升级成功后玩家会重连到本机直连条目，服务端此时还会再下发一次凭证
（重复分支），用对端地址补就会把回环写进缓存，下一轮预热便让 agent 去连自己
的回环。Forge 实现取 `Minecraft.currentServerData`，但**切换后它是 null**：
`connectTo` 里的 `loadWorld(null)` 走「退出世界」分支时会连带
`setServerData(null)`（曾以为「(host,port) 构造函数不碰 ServerData 所以切换后
仍在」，2026-08-09 实测证明是错的）——所以 `connectTo` 在清掉之前把地址存进
`switchOrigin`，推导失败时回退到它；该字段只在本次重定向的生命周期内有效，
新连接被识别为与切换无关时立即作废，绝不能拿 A 服的地址补 B 服的凭证。
仍额外挡掉回环，因为玩家也可能是从直连条目进服的。

**补不上地址的凭证绝不落盘**（`rememberAsync` 里拦截）：缓存按房间同文件
覆盖，残废版会把带地址的好凭证盖掉，下次启动预热直接瘫痪——2026-08-09
就是这么坏的。跳过没有代价：参数真轮换过的新凭证一定经真实服务器地址的
连接送达，那条路补得上。

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
当前唯一的适配层是 `mod/platform/forge-1.7.10`（服务端下发 + 客户端切换在
同一个 jar 里），要点见其 README：主线程派发走 tick 队列；断开事件必须区分
「升级引发的重连」与「真退出」，真退出用 `shutdown()` 而非 `onDisconnected()`。

`UpgradeController` 是整个流程的状态机：`IDLE → PUNCHING → UPGRADED / GAVE_UP`。

### 预热与凭证缓存

每次下发的凭证都会写进本地缓存（`CredentialCache`，`.minecraft/netherway/credentials/`）。
`WarmupController` 在 FML 加载期启动**无限重试的预热循环**（打不通就一直打，
指数退避见 `Timings.warmupRetryDelayMs`，就绪后守望 agent 进程、死了重打；
刻意无任何中转兜底），并经 `DirectServerEntry` 在服务器列表里维护一个直连
条目（agent 的 STARTING 事件一报出端口就更新地址，重试每轮都会再报）。

凭证来源除缓存外还有 mod 内建预取（`Prefetcher`，每轮预热前跑一次）：
平台层把游戏会话（`SessionIdentity`）与候选地址交给 core，候选由
`ServerCandidates` 组装——客户端 cfg 的 `client.prefetchServers` 优先，
其余来自服务器列表（server.dat，需开 `experimental.zeroConfigPrefetch`）。
全程在 JVM 内完成，不起子进程，accessToken 从不离开进程。首次启动即可
直连；密钥轮换后下一轮预取自动取到新密钥，无需先经中转。
玩家可三种方式进服，互为兜底：

- **直连条目**：进服后平台层按「回环地址 + 预热端口」识别（`ClientEvents.warmupMatch`），
  调 `adoptDirectConnection` 把状态机置为 UPGRADED——随后服务端照常下发的凭证
  命中重复分支并回执成功，零新协议。
- **中转进服**：既有升级流程，但 `runUpgrade` 先查预热隧道，就绪则直接复用
  （`reuseWarmTunnel`），不再对同一房间起第二个 agent。
- **预热失败/无缓存**：一切如旧。凭证轮换后优先由下一轮预取直接取回新
  密钥；没有可预取的地址时仍走「打洞失败→中转→新凭证覆盖」闭环恢复，
  玩家与服主都无需操作。

预热隧道生命周期是整个游戏进程（断开、回主菜单都不停，它承载着直连条目），
退出由 `AgentProcess` 的 shutdown hook 兜底。

### 预认证（在 MC 端口上换凭证）

玩家第一次启动、或密钥轮换之后本地没有任何可用凭证，而预热打洞需要凭证
才能开始。预认证解决这个先有鸡还是先有蛋：借皮肤站自己的 join/hasJoined，
在**不登录游戏**的前提下证明「这是个真实账号」，换回一份凭证。

**整个交换在 Minecraft 那一个端口上完成，服务器不多开任何监听端口。**
帧靠首字节与 MC 流量分叉：预认证帧以 `NWAY` 开头，而 MC 现代握手第 2 字节
是包 id `0x00`、legacy ping 以 `0xFE` 开头、PROXY protocol 以 `'P'` 或 `0x0D`
开头、frp 控制通道以 TLS 的 `0x16 0x03` 开头，最迟第 2 字节就分得开。
平台层的 `ConnectionSniffer` 是唯一的嗅探 handler——预认证、frp 控制通道转发
（内嵌会合点）与 PROXY 剥头**必须合成一个**，三者抢的是同一批首字节。

- 协议：core 的 `PreauthProtocol`（裸字节、版本化、有界），
  服务端 `PreauthService` ↔ 客户端 `PreauthClient`，两侧都是 Java。
- 流程：HELLO（自报身份，换 serverId 与皮肤站地址）→ 客户端拿
  accessToken 去皮肤站 `/join` → CONFIRM（服务端查 hasJoined，签令牌、
  下发凭证）。serverId 是**同一条连接**上签出并记住的，因此服务端零全局状态。
- `online-mode=false` 时没有会话服务器可查证，交换退化为 `MODE_OFFLINE`：
  跳过皮肤站那一跳，准入沿用服务器自己的名单（白名单开着就查白名单）。
  这不是本 mod 放松了标准，而是服务器本身就不验证身份。
- **帧不加密**，凭证以明文过网。这是刻意取舍，换取「只暴露一个端口」。
  accessToken 不在此列：它只在玩家本机与皮肤站之间走 HTTPS，从不进入
  任何一帧；且**皮肤站地址由客户端自己钉死**（`AuthlibInjector.detect()`），
  服务端在 HELLO 里说的一律不采信——否则被问到的服务器就能把令牌骗走。

信任边界与 PROXY 剥头刻意不同：**PROXY 头只信回环**（头谁都能伪造），
**预认证帧接受任何来源**（它自带身份证明，且玩家本就从公网经隧道过来）。

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

**预热与升级不得同时打洞。** 同一 NAT 上并发打两个洞会互相干扰（2026-08-09
实测：预热侧 QUIC 拨号超时、升级侧 15 秒才通，正常 1.8–5 秒）。谁后到谁等：
预热每轮打洞前看升级是否 PUNCHING（`UpgradeController` 构造时挂上的
`UpgradeGate`），升级起自己的 agent 前等预热的这轮出结果（`awaitWarmupAttempt`，
出来恰好就绪就直接复用）。两个方向都有界（一个 `outcomeWaitMs`），条件互斥
不会死锁。已就绪的隧道只是守望进程、不在打洞，不触发让路。

**Yggdrasil 的 Profile 必带 `properties` 嵌套数组**（textures 材质），
`Json.parseObject` 的扁平契约啃不动它——hasJoined 的解析要用
`Json.parseTopLevel`（顶层标量照收、嵌套值字符串感知地跳过）。2026-08-09
之前用的是严格版，预认证对任何真实皮肤站都 100% 失败。agent 事件仍走严格版，
嵌套即异常的契约不变。

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
地址、皮肤站域名）只存在于 gitignore 的 `build.env`。README 不写指向
具体机器的运维细节（宿主机上跑着什么、真实端口表）。提交用 GitHub noreply
邮箱（repo 本地 git config 已设）。内嵌密钥的 `build.sh` 产物绝不上公开
Release，公开渠道只发 mod jar（natives 无密钥）。

mod 方案的核心安全价值在于：**凭证由服务端在玩家登录后下发**，而非随客户端分发。
能拿到密钥的必然是通过了服务器既有正版验证/白名单的玩家，因此不需要另建鉴权系统。
`Credentials.toString()` 刻意不输出任何参数值（token 与密钥都在其中），只列键名。

开启预认证（`server.preauth`）后这条边界放宽为「皮肤站上任何有效账号 +
服务器自己的准入名单」：hasJoined 只证明账号真实，不证明是本服玩家，
所以还叠了一层 `PreauthService.Host.allowsPlayer`（白名单开着就查白名单）。
这是刻意取舍——谁能进服由 MC 服务端自己决定，不属于本 mod 的职责范围；
凭证换来的隧道也只通向 MC 端口。皮肤站换成公共站点前需重新评估这一点。

预认证的帧**明文**，凭证因此在网络路径上可见（见「预认证」一节）。这是为
「只暴露一个端口」付出的代价，已知且刻意。止损同样是服务端轮换密钥。

零配置预取（`experimental.zeroConfigPrefetch`，默认关）会让客户端去问
服务器列表里的每个地址，等于把玩家名/UUID 发给一批没打过交道的服务器，
且应答者未经验证。默认关就是因为这个；服务端可在玩家登录后随凭证下发
`POLICY_ZERO_CONFIG_PREFETCH` 把它打开并写回客户端 cfg——那条路径上的
应答者是玩家确实登录过的服务器，比盲扫可控得多。

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
