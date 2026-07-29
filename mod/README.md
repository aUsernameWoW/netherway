# mod — 凭证下发与连接升级

让玩家零配置享受 P2P：先通过既有的中转隧道正常连上服务器，服务端在登录后
下发 P2P 凭证，客户端在后台打洞，成功了才切过去，失败就安静地留在原线路上。

凭证不随客户端分发，而是玩家通过服务器既有的正版验证 / 白名单之后才拿到。
**能拿到密钥的必然是有权进服的人，于是不需要另建一套鉴权。**

## 为什么分成 core 和适配层

`core` 里没有任何 Minecraft 类型。真正碰游戏 API 的只有三件事——收发自定义
消息、玩家登录事件、触发重连——它们被收敛到 `ClientBridge` 这一个接口里。

于是换版本、换加载器时，只需重写那个薄薄的适配层，core 原样复用：

```
core/                      纯 Java 8，零第三方依赖，可脱离游戏测试
platform/forge-1.7.10/     当前目标
platform/forge-1.12.2/     以后
platform/fabric-1.16.5/
platform/sponge/           仅服务端（Sponge 没有客户端）
```

两个关键决策让这件事成立：

**凭证编解码用裸字节**（`Credentials` 里的 `DataOutputStream`），不碰任何加载器的
序列化机制。Forge 在 1.13 之后把网络 API 整个重写过，绑上去意味着每换一个版本
就要重写一遍编解码；而裸字节在所有加载器所有版本上都一样，适配层只需负责搬运
`byte[]`。凭证走的是 Minecraft 原生的自定义频道，这个机制 Bukkit/Spigot/Paper
插件、Sponge 插件、Forge/Fabric mod 甚至 BungeeCord/Velocity 代理都能收发，
所以服务端那半几乎能覆盖所有平台。凭证自 v2 起是「backend 标识 + 参数表」，
core 不解释参数、只原样转交 agent——将来把 frp 换成别的隧道方案，
core 与适配层同样零改动。

**core 零第三方依赖**，连 JSON 解析都是手写的（`Json`，约 150 行）。Minecraft
自带 Gson，但依赖它就等于依赖 Minecraft；而 1.7.10 的类路径上挤着几百个 mod，
多一个库就多一分冲突风险。

## 各类职责

| 类 | 职责 |
|---|---|
| `Platform` | 识别系统与架构，决定取哪个 agent 二进制 |
| `BinaryStore` | 从 jar 释放二进制，文件名带内容摘要 |
| `AgentProcess` | 启动 agent 子进程，读状态、管生命周期 |
| `AgentEvent` / `Json` | 解析 agent 的逐行 JSON 状态输出 |
| `Credentials` | 凭证（backend 标识 + 参数表）与跨版本安全的编解码 |
| `UpgradeController` | 状态机：何时升级、何时放弃 |
| `ClientBridge` | 唯一的平台适配接口 |
| `Timings` | 可调时间参数，默认值来自实测 |

## 三个不显然的实现细节

**必须消费 agent 的 stderr，且不能丢弃内容。** 管道缓冲区填满后子进程写日志会
永久阻塞，表现为隧道莫名卡住；而内容直接丢掉的话，agent 启动失败时排查就只剩
「进程退出了」这种毫无信息量的结论——开发时正是靠补上 stderr 尾部才定位到
一次「未知子命令」的问题。现在保留最近 8 行并附在失败原因里。

**必须识别重复下发的凭证。** 切换连接后玩家会重新登录一次，服务端会再下发一次
凭证；不加判断就会陷入「升级→重连→再升级」的死循环。`UpgradeController` 按
`Credentials.dedupKey()`（backend + 房间名）去重，且本次会话内放弃过的房间
不再重试。

**连接切换必须回到游戏主线程。** agent 的状态事件到达时在后台线程，在那里动
网络管理器会引发难以复现的崩溃，所以走 `ClientBridge.runOnGameThread`。

## 验证状态

`SelfTest` 共 87 项，覆盖平台识别、JSON 转义、事件解析容错、凭证往返（含中文与
任意 backend）、v1 兼容与前向兼容、命令行构造、时间参数回填、状态机去重。
刻意不依赖 JUnit，一条 javac + java 就能跑：

```bash
JAVA8=/path/to/jdk8
$JAVA8/bin/javac -encoding UTF-8 -d build/classes $(find core/src -name "*.java")
$JAVA8/bin/java -cp build/classes cn.ripplecraft.xtcpinmc.core.SelfTest
```

**兼容性已实测**：用 Java 8 编译的字节码在 **Java 8 / 17 / 21 / 25**
上均 87 项全过。这正是 GTNH 用 lwjgl3ify 让 1.7.10 跑在现代 JVM 上的场景。
代码只用 `ProcessBuilder`、`java.nio.file`、`java.net` 这类公共稳定 API，
不碰 `sun.misc.*` 和 JDK 内部反射（Java 16+ 的强封装会直接拒绝）。

**尚未完成**：Java 驱动的真实跨网络打洞。链路的每一环都单独验证过了——二进制
释放、进程启动、JSON 解析、错误传播、agent 自身建链（实测 31ms RTT）——但把它们
串起来跑通需要两个处于不同网络位置的机器，而开发机当时被全局 VPN 接管了 UDP，
同机测试又受 hairpin NAT 限制。在正常网络下按上面的方式跑 `IntegrationTest`
即可确认。

## 待办

- [ ] Forge 1.7.10 适配层：自定义频道注册、登录事件、重连触发
- [ ] 服务端凭证下发（可先做成 Forge mod，日后按需加 Sponge/Paper 实现）
- [ ] mod 配置文件，把 `Timings` 暴露给服主
- [ ] 构建脚本：把各平台 agent 二进制打进 jar 的 `natives/`
- [ ] 跨网络端到端验证
