# platform/forge-1.7.10 — GTNH 目标版本的适配层

core 的 Forge 1.7.10 接线。同一个 jar 同时装在服务端与客户端：

- **服务端半边**（`CredentialSender`）：玩家登录后把配置里的凭证编码成裸字节，
  经自定义频道 `xtcpinmc` 下发。走的是 Minecraft 原生 plugin channel，
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
`config/xtcpinmc.cfg`（跟 mods 目录平级的那个 config），改好占位符再启动，
一次到位。没写的键按默认值处理；语法写错也不会炸服——mod 会记一条
错误日志并按默认值运行（即不下发凭证），改好后重启生效。

```
# xtcpinmc 服务端配置。此文件含密钥，注意文件权限。

server {
    B:enabled=true
    S:backend=frp-xtcp

    # 随服务端启动内置 serve，用下面的 params 把本地端口注册为房间代理。
    # 已在宿主机单独运行 xtcpinmc serve、或托管环境禁止子进程时设为 false。
    B:runAgent=true
    # 内置 serve 发布的本地端口，0 表示用服务器实际监听的端口
    I:localPort=0

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
}
```

注意 cfg 的语法细节：键有类型前缀（`B:` 布尔、`S:` 字符串、`I:` 整数），
列表以 `S:params <` 开始、单独一行的 `>` 结束。配置只在启动时读取，
改动需重启。

`params` 是通用 key=value 列表：凭证本来就是「backend 标识 + 参数表」，
换隧道方案时这里跟着换键名即可，mod 代码零改动。键名契约与 Go 侧
backend 实现（如 `internal/backend/frpxtcp`）保持一致。

**客户端零配置即用**，什么都不用填。默认 `client.prewarm=true`：游戏启动时用
上次缓存的凭证预热隧道并维护服务器列表里的直连条目（首次进服仍需先经中转拿
凭证）。要关掉预热/直连条目、改条目名（`directEntryName`）、固定预热端口
（`prewarmPort`）或调时间参数，同一路径的 cfg 里写 `client` 类目
（键见 `ModConfig`），也是启动前手写即可。

## 排查

直连没生效时看日志，两侧都有料：

- **客户端游戏日志**（搜 `xtcpinmc`）：默认 `client.verboseLogging=true`，
  打洞全过程——收到的凭证键名、agent 启动命令（参数值已脱敏）、agent 的
  每个事件与诊断输出、以及 frp 自身 info 及以上的日志（比如
  `xtcp server for [xxx-p2p] doesn't exist`，意思是宿主机的 serve 没在
  运行）——都以 INFO 级别写进游戏日志。嫌吵可在 cfg 里关掉，
  这些内容会降为 DEBUG 级别。
- **agent 详细日志**：`.minecraft/xtcpinmc/tunnel.log`（进服后的升级流程）与
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
  关掉 runAgent 的话代理注册靠宿主机上独立运行的 `xtcpinmc serve`，
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
