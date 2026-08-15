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
转发到 Minecraft 端口，不需要自建 frps，也不需要修改这些值：

```
server {
    B:enabled=true
    B:runAgent=true
    B:rendezvous=true
    S:params <
        # 默认内嵌会合点所需参数，保持原样即可
        token=auto
        room=minecraft
        secret=auto
     >
}
```

`token=auto` 和 `secret=auto` 会在内存中生成本次启动使用的随机值，cfg 文件里
仍保持 `auto`；`room` 只是显示和命名用，想改名时只改它即可。高级鉴权用的
`server.tokenSigningKey` / `server.serveAuthToken` 默认不会生成，以免和
`server.params` 里的登录令牌混淆；需要时按下节手工加入。

自建 frps 时才把 `server.rendezvous` 改成 `false`，并把整个 `server.params`
列表替换为 README 顶层文档中的自建 frps 示例。

注意 cfg 的语法细节：键有类型前缀（`B:` 布尔、`S:` 字符串、`I:` 整数），
列表以 `S:params <` 开始、单独一行的 `>` 结束。配置只在启动时读取，
改动需重启。语法错误不会炸服：mod 会记录错误并在本次启动关闭服务端直连，
修正后重启即可。

`params` 是通用 key=value 列表：凭证本来就是「backend 标识 + 参数表」，
换隧道方案时这里跟着换键名即可，mod 代码零改动。键名契约与 Go 侧
backend 实现（如 `internal/backend/frpxtcp`）保持一致。

**客户端零配置即用**，什么都不用填。默认 `client.prewarm=true` 且 `client.prefetch=true`：
游戏启动时向 server.dat 里的候选并行预取，为每个成功应答的服务保留独立凭证。
打洞阶段严格串行，已建立的隧道可并存；一个服务的失败/退避不阻塞其他服务。
默认 `client.replaceServerEntries=true`：隧道 READY 后，点击原服务器条目会直接
使用本地隧道，但磁盘上的真实入口保持不变。若玩家在 READY 前已经从原入口进服，
预热成功后仍会立即切换。把该项设为 `false` 才会维护额外的直连条目；这时
`directEntryName` 控制其前缀。`prewarmPort` 可固定首选预热端口，其他时间参数也在
同一 cfg 的 `client` 类目中配置。

## 每玩家令牌（可选，frps 侧 authplugin）

不部署也一切照常（全局 token 分层的基础校验仍在）。部署后：泄露的全局 token
连 frps 都登不上，注册代理只认 serve 的静态令牌，玩家令牌绑定 UUID、30 天
过期、登录即续签。

> **开了 `rendezvous=true` 就不必读这一节。** 内嵌会合点会在回环上自带一个
> 只服务本进程的 authplugin 端点，填了 `tokenSigningKey` 即生效，签发密钥
> 不必再放到公网机器上，`serveAuthToken` 也不必填（未填时 serve 会本机生成
> 一个自用的）。下面的独立部署只对连公网 frps 的经典模式有意义。

frps 宿主机上运行（密钥经环境变量传入，避免出现在进程列表）：

```bash
NETHERWAY_AUTH_KEY=<签发密钥> ./netherway authplugin -static-token <serve静态令牌> -allow-legacy
```

frps.toml 加上（然后重启 frps）：

```toml
[[httpPlugins]]
name = "netherway-auth"
addr = "127.0.0.1:7200"
path = "/handler"
ops = ["Login", "NewProxy"]
```

服务端 cfg 填 `tokenSigningKey`（与 `-key` 同值）、`serveAuthToken`
（与 `-static-token` 同值）。两侧启动日志都会打印**签发密钥指纹**，
一致才说明密钥没配岔。

迁移节奏：先带 `-allow-legacy` 上线（老客户端、没配令牌的 serve 都照常）；
等玩家基本都经新版服务端登录过一轮（拿到了每玩家令牌），去掉
`-allow-legacy` 重启 authplugin 即完成收口。frps 调不到插件时会拒绝登录
（fail-closed），生产环境交给 systemd 并设自动重启。

## 排查

直连没生效时看日志，两侧都有料：

- **客户端游戏日志**（搜 `netherway`）：默认 `client.verboseLogging=true`，
  打洞全过程——收到的凭证键名、agent 启动命令（参数值已脱敏）、agent 的
  每个事件与诊断输出、以及 frp 自身 info 及以上的日志（比如
  `xtcp server for [xxx-p2p] doesn't exist`，意思是宿主机的 serve 没在
  运行）——都以 INFO 级别写进游戏日志。嫌吵可在 cfg 里关掉，
  这些内容会降为 DEBUG 级别。
- **agent 详细日志**：`.minecraft/netherway/tunnel.log`（进服后的升级流程）与
  `tunnel-warmup.log`（启动期预热），frp 的 debug 级输出，打洞握手的每一步
  都在里面，玩家报告问题时让他带上对应文件。
  （debug 级刻意不进游戏日志：隧道存活期间会持续刷屏。）
- **服务端日志**：启动时会打印生效的凭证配置（只列键名）；`server.params`
  里键名拼错（agent 按契约会静默忽略未知键）会有 WARN 指出来。
  每个玩家的直连结果也会回传记录在这里——成功一条 INFO（含延迟），
  失败一条 WARN（含原因），不用挨个找玩家要客户端日志。
- **常见失败**：`xtcp server for [房间-p2p] doesn't exist` 意思是 frps 上
  没有这个代理。默认 `server.runAgent=true` 时代理由 mod 内置的 serve
  注册（参数与凭证同源，日志里带 `[serve]` 前缀，出问题先看它们）；
  关掉 runAgent 的话代理注册靠宿主机上独立运行的 `netherway serve`，
  检查它是否在跑、`-room` 与 `-server` 是否与 `server.params` 一致
  （serve 不带 `-room` 时用的是构建期默认房间名）。两种方式**只能开一个**：
  同名代理在 frps 上会注册冲突。

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
所以 xtcp（上游尚未支持发头）、老 agent、直连预热的流量都不受影响；
只信来自回环的连接，防止 MC 端口同时暴露在局域网时被伪造头。剥完头把
真实来源写回 `NetworkManager.socketAddress`（非 final，反射带 MCP/SRG 双名）。
