# platform/forge-1.7.10 — GTNH 目标版本的适配层

core 的 Forge 1.7.10 接线。同一个 jar 同时装在服务端与客户端：

- **服务端半边**（`CredentialSender`）：玩家登录后把配置里的凭证编码成裸字节，
  经自定义频道 `xtcpinmc` 下发。走的是 Minecraft 原生 plugin channel，
  将来换 Bukkit/Sponge 插件下发也不用改客户端。
- **客户端半边**（`ClientProxy` 接线）：收到凭证交给 core 的 `UpgradeController`，
  打洞成功后经 `ForgeClientBridge` 切换连接。

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

    # backend 参数，每行一个 key=value；# 开头的行会被忽略
    S:params <
        server=frps.example.com
        serverPort=7000
        token=换成frps的auth.token
        stun=stun.miwifi.com:3478,stun.easyvoip.com:3478,stun.qq.com:3478
        room=gtnh
        secret=换成房间密钥与宿主机serve端一致
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

**客户端零配置即用**，什么都不用填。如需关掉功能或调时间参数，
同一路径的 cfg 里写 `client` 类目（键见 `ModConfig`），也是启动前手写即可。

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
