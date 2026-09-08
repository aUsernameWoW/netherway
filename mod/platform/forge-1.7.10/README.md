# platform/forge-1.7.10 — Forge 1.7.10 适配层

core 的 Forge 1.7.10 接线。同一个 jar 同时装在服务端与客户端：

- **服务端半边**（`CredentialSender`）：玩家登录后把配置里的凭证编码成裸字节，
  经自定义频道 `netherway` 下发。走的是 Minecraft 原生 plugin channel，
  将来换 Bukkit/Sponge 插件下发也不用改客户端。
- **客户端半边**（`ClientProxy` 接线）：收到凭证交给 core 的 `UpgradeController`，
  打洞成功后经 `ForgeClientBridge` 切换连接。凭证按 Minecraft 入口分开缓存；
  FML 加载期并行预取所有候选，`WarmupController` 再串行打洞、同时守望
  已建立的多条隧道。默认由 `WarmupEntryRouter` 在运行期把玩家选中的原条目
  解析到 READY 隧道，不修改 `servers.dat`；关闭入口覆盖后才由
  `DirectServerEntry` 维护独立的 `[P2P直连] <房间> (<入口>)`。

没装 mod 的客户端照常进服（`acceptableRemoteVersions = "*"`），
凭证包会被它们静默忽略——本 mod 是纯增强，不构成准入门槛。

## 构建

1.7.10 的构建绕不开反混淆/重混淆，用的是
[RetroFuturaGradle](https://github.com/GTNewHorizons/RetroFuturaGradle)
（老 ForgeGradle 1.2 的下载源早已失效）。Gradle 进程需要 Java 21+，
编译产物经 toolchain 固定为 Java 8 字节码，两不相干。

```bash
../../build-natives.sh        # 先产出打进 jar 的 agent 二进制（不含密钥）
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./gradlew build
```

产物在 `build/libs/`：不带分类器的 jar 是重混淆后的发布版，`-dev` 是开发环境用的。

首次构建要下载 Forge userdev 并反编译 Minecraft，几分钟起步。国内网络下
大文件容易被掐断，而 Gradle 不会断点续传——对策是用 curl 先把大件补进
`~/.m2`（`settings.gradle` 里 `mavenLocal()` 排在最前就是为这个）：

```bash
DIR=~/.m2/repository/com/gtnewhorizons/retrofuturagradle/1.4.9
mkdir -p $DIR && cd $DIR
BASE=https://nexus.gtnewhorizons.com/repository/public/com/gtnewhorizons/retrofuturagradle/1.4.9
curl -L -C - -O $BASE/retrofuturagradle-1.4.9.jar -O $BASE/retrofuturagradle-1.4.9.pom
```

## 服务端配置

首次启动会生成 `config/netherway.cfg`（跟 mods 目录平级的那个 config）。
新配置默认开启内嵌会合点，关键部分如下；公网侧只需把玩家使用的 TCP 入口
转发到 Minecraft 端口，不需要架设任何其它服务，也不需要修改这些值：

```
server {
    B:enabled=true
    B:runAgent=true
    B:rendezvous=true
    S:backend=gonc-p2p
    S:params <
        # 默认内嵌会合点所需参数，保持原样即可
        sessionKey=auto
        room=minecraft
     >
}
```

`sessionKey=auto` 会在内存中生成本次启动使用的随机会话密钥，cfg 文件里
仍保持 `auto`，重启即轮换、旧凭证自动失效；`room` 只是显示和命名用，想改名
时只改它即可。`rendezvous=true` 表示信令 broker 内嵌在服务端进程里、只监听
回环，玩家的信令经 Minecraft 端口由嗅探器转入；凭证里因此只带 `brokers=origin`
占位符，由客户端换成它实际连接的入口。想改用公共或自建 MQTT broker 时把
`rendezvous` 设为 `false` 并在 `params` 里写 `brokers=tcp://host:1883,...`。

注意 cfg 的语法细节：键有类型前缀（`B:` 布尔、`S:` 字符串、`I:` 整数），
列表以 `S:params <` 开始、单独一行的 `>` 结束。配置只在启动时读取，
改动需重启。语法错误不会炸服：mod 会记录错误并在本次启动关闭服务端直连，
修正后重启即可。

`params` 是通用 key=value 列表：凭证本来就是「backend 标识 + 参数表」，
换隧道方案时这里跟着换键名即可，mod 代码零改动。键名契约与 Go 侧
backend 实现（`internal/backend/goncp2p`）保持一致。

**客户端零配置即用**，什么都不用填。默认 `client.prewarm=true` 且 `client.prefetch=true`：
游戏启动时向 server.dat 里的候选并行预取，为每个成功应答的服务保留独立凭证。
打洞阶段严格串行，已建立的隧道可并存；一个服务的失败/退避不阻塞其他服务。
默认 `client.replaceServerEntries=true`：隧道 READY 后，点击原服务器条目会直接
使用本地隧道，但磁盘上的真实入口保持不变。若玩家在 READY 前已经从原入口进服，
预热成功后仍会立即切换——即使进服后的那轮打洞已经超时放弃；游玩中的切换表现
为一次快速重连，每个服务本会话最多自动切换两次，不想被打断可关
`client.redirectOnWarmReady`。把该项设为 `false` 才会维护额外的直连条目；这时
`directEntryName` 控制其前缀。`prewarmPort` 可固定首选预热端口，其他时间参数也在
同一 cfg 的 `client` 类目中配置。

## 排查

直连没生效时看日志，两侧都有料：

- **客户端游戏日志**（搜 `netherway`）：默认 `client.verboseLogging=true`，
  打洞全过程——收到的凭证键名、agent 启动命令（参数值已脱敏）、agent 的
  每个事件与诊断输出、以及 agent 打洞过程的摘要日志——都以 INFO 级别
  写进游戏日志。嫌吵可在 cfg 里关掉，这些内容会降为 DEBUG 级别。
- **agent 详细日志**：`.minecraft/netherway/tunnel.log`（进服后的升级流程）与
  `tunnel-warmup.log`（启动期预热），打洞握手的每一步都在里面，玩家报告
  问题时让他带上对应文件。
  （debug 级刻意不进游戏日志：隧道存活期间会持续刷屏。）
- **服务端日志**：启动时会打印生效的凭证配置（只列键名）；`server.params`
  里键名拼错（agent 按契约会静默忽略未知键）会有 WARN 指出来。
  每个玩家的直连结果也会回传记录在这里——成功一条 INFO（含延迟），
  失败一条 WARN（含原因），不用挨个找玩家要客户端日志。
- **常见失败**：客户端日志里打洞一直等到超时、服务端却没有任何
  `[serve]` 输出，多半是服务端 serve 没起来。默认 `server.runAgent=true`
  时 serve 由 mod 内置启动（参数与凭证同源，日志里带 `[serve]` 前缀，
  就绪时有一行 `[serve-ready]`，告警行带 `[serve-warn]`，出问题先看它们）。
  关掉 runAgent 的话内嵌会合点随之失效（mod 会按 `rendezvous=false` 处理并
  告警：会合点起在内置 serve 进程里，独立运行的 serve 开不了它），凭证里
  也不再带 `brokers=origin`——这时必须在 `server.params` 里写死
  `sessionKey` 与外部 broker 列表 `brokers=tcp://host:1883,...`（`auto` 只对
  内置启动有效），再在宿主机上用完全相同的参数自己跑 `netherway serve
  -backend gonc-p2p -O sessionKey=… -O room=… -O brokers=… -port <MC端口>`
  （不加 `-rendezvous`）。

## 实现要点

三件 core 留给平台层的活，都在这层解决：

**主线程派发用 tick 队列**（`ForgeClientBridge.drainTasks`）。1.7.10 的
`Minecraft.func_152344_a` 还没有友好名字，tick 队列不依赖任何混淆名。

**断开事件要区分两种情况**（`ClientEvents.onDisconnected`）：升级引发的
断开不能停 agent（隧道正要承载新连接），真退出必须停（否则孤儿进程占着
端口）。靠 `connectTo` 里先立起的「重定向进行中」标志区分，新连接落地时
按回环地址与端口验明正身——只信布尔标志的话，重定向失败后玩家手动连别的
服务器会被误认。

**真退出时用 `shutdown()` 而不是 `onDisconnected()`**：后者在 UPGRADED
状态下会以为断开是升级自己造成的而放过 agent。

**采认经运行期覆盖或独立直连条目建立的连接要在 `shutdown()` 之后**
（`ClientEvents.onConnected`）：
采认要求状态机在 IDLE。识别只认「回环地址 + 预热隧道端口」，单人游戏的
本地通道（非 `InetSocketAddress`）与玩家手动连的其他本地服都不会误判。
预热隧道本身不归 `UpgradeController` 管：它活到游戏进程结束（承载着服务器
列表连接），断开、换服都不停，退出由 shutdown hook 兜底。

**入口覆盖只发生在内存里。** Forge 1.7.10 没有连接前事件，
`RouteAwareGuiHandler` 只接管原版 `GuiMultiplayer` 的最终选择动作与列表的
延迟探测，两者查同一张路由表：连接用临时 `ServerData` 副本，探测也发往临时
副本、结果逐 tick 镜像回真实条目（含 FML 兼容性元数据）。原列表对象的地址
从不改写，所以图标保存、编辑、排序、崩溃和移除 mod 都不会把 localhost 留进
`servers.dat`。其他 mod 自定义的多人界面不被替换，仍可在进服后走既有升级流程。

**路由感知 pinger 必须把网络管道委托给原版实例。** `GuiMultiplayer` 的
收包泵（`updateScreen`）与关屏取消（`onGuiClosed`）直接操作私有字段
`field_146797_f`、不经 `func_146789_i()` 这个 getter——覆写 getter 换上的
包装若自建 `OldServerPinger`，其发出的探测回包永远无人处理，条目会停在
"Pinging..."。包装只做路由判断与临时副本登记，真正的探测一律交回原版实例。

**PROXY protocol 剥头挂在 accept 链上**（`ConnectionSniffer`，仅服务端、
仅 `server.proxyProtocol` 非空时；它同时也管预认证帧与内嵌会合点的中继，
三者抢的是同一批首字节，必须合成一个 handler）：在监听端点的 server channel pipeline 里
拦截 accept 出来的连接，抢在 MC 的 ChannelInitializer 之前往新连接头部塞
剥头 handler。解析是嗅探式的（core 的 `ProxyProtocol`）——无头流量原样放行，
所以预认证帧、转入 broker 的信令连接、经端口转发进来的普通玩家都不受影响，
只有 serve 注入了头的隧道连接会被剥头；只信来自回环的连接，防止 MC 端口
同时暴露在局域网时被伪造头。剥完头把
真实来源写回 `NetworkManager.socketAddress`（非 final，反射带 MCP/SRG 双名）。
