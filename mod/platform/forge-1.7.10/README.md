# platform/forge-1.7.10 — GTNH 目标版本的适配层

core 的 Forge 1.7.10 接线。同一个 jar 同时装在服务端与客户端：

- **服务端半边**（`CredentialSender`）：玩家登录后把配置里的凭证编码成裸字节，
  经自定义频道 `netherway` 下发。走的是 Minecraft 原生 plugin channel，
  将来换 Bukkit/Sponge 插件下发也不用改客户端。
- **客户端半边**（`ClientProxy` 接线）：收到凭证交给 core 的 `UpgradeController`，
  打洞成功后经 `ForgeClientBridge` 切换连接。凭证同时落进本地缓存：下次启动时
  FML 加载期就用它预热隧道（core 的 `WarmupController`），并在服务器列表里
  维护一个直连条目（`DirectServerEntry`，默认名 `[P2P直连] <房间>`）——
  玩家可直接选它进服；就算故意走中转，进服后也会复用预热隧道切回直连。

没装 mod 的客户端照常进服（`acceptableRemoteVersions = "*"`），
凭证包会被它们静默忽略——本 mod 是纯增强，不构成准入门槛。

## 构建

1.7.10 的构建绕不开反混淆/重混淆，用的是 GTNH 自家的
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

**不必为了生成配置骨架先空跑一次服务端**：启动前直接把下面的内容存成
`config/netherway.cfg`（跟 mods 目录平级的那个 config），改好占位符再启动，
一次到位。没写的键按默认值处理；语法写错也不会炸服——mod 会记一条
错误日志并按默认值运行（即不下发凭证），改好后重启生效。

```
# netherway 服务端配置。此文件含密钥，注意文件权限。

server {
    B:enabled=true
    S:backend=frp-xtcp

    # 随服务端启动内置 serve，用下面的 params 把本地端口注册为房间代理。
    # 已在宿主机单独运行 netherway serve、或托管环境禁止子进程时设为 false。
    B:runAgent=true
    # 内置 serve 发布的本地端口，0 表示用服务器实际监听的端口
    I:localPort=0

    # 内嵌会合点：不再连公网 frps，改在本机起一个只监听回环的会合点，
    # 玩家的 frp 控制连接由本 mod 从 Minecraft 端口转发进去。开启后公网那台
    # 机器只需要把 TCP 转到 Minecraft 端口——不装插件、不必支持 xtcp、
    # 不必与本 mod 同版本，租来的隧道服务也能用。需要 runAgent=true。
    # 开启时下面 params 里的 server / serverPort 会被忽略（客户端自己推导）；
    # token 不必是真的（只有内嵌会合点会校验它），写 auto 即每次启动随机轮换。
    B:rendezvous=false

    # backend 参数，每行一个 key=value；# 开头的行会被忽略
    S:params <
        server=frps.example.com
        serverPort=7000
        token=换成frps的auth.token
        stun=stun.miwifi.com:3478,stun.easyvoip.com:3478,stun.qq.com:3478
        room=gtnh
        # 写具体值须与宿主机 serve 端一致；runAgent=true 时推荐写 auto——
        # 每次启动随机生成，玩家缓存的旧凭证随重启失效，走一次中转自动更新
        secret=auto
     >

    # 建议客户端使用的打洞超时秒数；0 表示由客户端自己配置
    I:punchTimeoutSeconds=0

    # ---- 以下三项配合 frps 侧的 authplugin（可选，见下节）----
    # 每玩家令牌的签发密钥，非空即启用；须与 authplugin 的 -key 一致
    S:tokenSigningKey=
    # 每玩家令牌的有效天数，每次登录自动续签
    I:tokenTtlDays=30
    # 内置 serve 向 authplugin 表明身份的静态令牌（-static-token 同值）
    S:serveAuthToken=

    # ---- PROXY protocol（可选）----
    # 让隧道进程连本地 MC 端口前先发 PROXY protocol 头（填 v1 或 v2，留空关闭）。
    # 开启后 mod 自动给服务端接入链装剥头组件，登录日志与封禁看到的是玩家
    # 真实来源地址而不是 127.0.0.1。当前 frp 只有 stcp 中转路径实际带头，
    # xtcp 的 P2P 流等上游支持（fatedier/frp#2748）后自动生效。
    # runAgent=false 时须给独立运行的 serve 手动加同值的 -proxy-protocol 旗标。
    S:proxyProtocol=
}
```

注意 cfg 的语法细节：键有类型前缀（`B:` 布尔、`S:` 字符串、`I:` 整数），
列表以 `S:params <` 开始、单独一行的 `>` 结束。配置只在启动时读取，
改动需重启。

`params` 是通用 key=value 列表：凭证本来就是「backend 标识 + 参数表」，
换隧道方案时这里跟着换键名即可，mod 代码零改动。键名契约与 Go 侧
backend 实现（如 `internal/backend/frpxtcp`）保持一致。

**客户端零配置即用**，什么都不用填。默认 `client.prewarm=true` 且 `client.prefetch=true`：
游戏启动时自动向服务器列表（server.dat）里的服务器预取凭证并后台打洞，
首次启动即直连——不必先经中转进服拿凭证。要关掉预热/直连条目、改条目名
（`directEntryName`）、固定预热端口（`prewarmPort`）或调时间参数，同一路径
的 cfg 里写 `client` 类目（键见 `ModConfig`），也是启动前手写即可。

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

**采认直连条目的连接要在 `shutdown()` 之后**（`ClientEvents.onConnected`）：
采认要求状态机在 IDLE。识别只认「回环地址 + 预热隧道端口」，单人游戏的
本地通道（非 `InetSocketAddress`）与玩家手动连的其他本地服都不会误判。
预热隧道本身不归 `UpgradeController` 管：它活到游戏进程结束（承载着服务器
列表里的直连条目），断开、换服都不停，退出由 shutdown hook 兜底。

**PROXY protocol 剥头挂在 accept 链上**（`ConnectionSniffer`，仅服务端、
仅 `server.proxyProtocol` 非空时；它同时也管预认证帧与内嵌会合点的中继，
三者抢的是同一批首字节，必须合成一个 handler）：在监听端点的 server channel pipeline 里
拦截 accept 出来的连接，抢在 MC 的 ChannelInitializer 之前往新连接头部塞
剥头 handler。解析是嗅探式的（core 的 `ProxyProtocol`）——无头流量原样放行，
所以 xtcp（上游尚未支持发头）、老 agent、直连预热的流量都不受影响；
只信来自回环的连接，防止 MC 端口同时暴露在局域网时被伪造头。剥完头把
真实来源写回 `NetworkManager.socketAddress`（非 final，反射带 MCP/SRG 双名）。
