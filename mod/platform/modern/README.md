# platform/modern — 1.16.5 / 1.18.2 / 1.20.1（Forge + Fabric）

core 的现代版本适配，用 [Architectury Loom](https://github.com/architectury/architectury-loom)
当构建工具链、[mojmap](https://www.minecraft.net/) 映射，让**一套源码同时编
Forge 与 Fabric**。**不引入 Architectury API 运行期依赖**——只借它的 Loom/
插件做多 loader 构建，凭证频道保持裸 vanilla custom payload，agent 逻辑、
凭证编解码、状态机全部复用 core（零改动）。决策依据见
[`docs/multi-version.md`](../../../docs/multi-version.md)。

## 结构

```
shared/
  common/   三版本共用、loader 无关：配置(CfgFile/ModConfig)、agent 托管
            (ServerAgentHost/PreauthHost)、嗅探核心(SnifferCore)、服务端
            Mixin(ConnectionAccessor/ServerConnectionInit)、客户端桥
            (ModernClientBridge)、装配(ServerRuntime/ClientRuntime)、
            凭证/回执服务、路由表。用 mojmap 稳定 API，逐版差异经接口注入。
  fabric/   Fabric 接线(入口+网络)。Fabric API 在三版本形态稳定，共用。
1.16.5/  1.18.2/  1.20.1/    每版本一个独立 Architectury 工作区：
  common/   VersionInfo/VersionServer/VersionClient(逐版差异实现) +
            DirectServerEntry + 客户端 Mixin(ConnectScreen/ServerStatusPinger)
  forge/    Forge 入口(NetherwayForge/NetherwayForgeClient) + mods.toml
  fabric/   fabric.mod.json（入口源码来自 shared/fabric）
```

每版本独立工作区（而非一个大工程的三子集），是为了能各自 pin Loom 版本——
1.16.5 的 arch-loom 支持偏边缘，这样它出问题不拖累另两个版本。

## 逐版差异都收在哪

绝大部分逻辑在 `shared/`，只用 mojmap 下 1.16.5–1.20.1 稳定的 API。真正逐版
分叉的少数点，经各版本 `common` 里的 `Version*` 类与两个客户端 Mixin 隔离：

| 分叉点 | 载体 |
|---|---|
| 程序化连接（ConnectScreen 构造器/4 参/5 参 startConnecting） | `VersionClient.connect` + `ConnectScreenMixin` |
| 聊天文本（TextComponent / Component.literal） | `VersionClient.sendChat` |
| 服务端口（getServerPort/getPort） | `VersionServer.mcPort` |
| ServerData 构造与 ServerList.add（布尔/枚举/hidden 参） | `DirectServerEntry` |
| 延迟探测重定向（ServerAddress 包名差异） | `ServerStatusPingerMixin` |
| Forge 事件与网络包（fml.network / net.minecraftforge.network、LoggedOut/LoggingOut） | 各版本 `forge/NetherwayForge*` |

服务端 Netty 注入统一走一个 Mixin（`ServerConnectionListener$1#initChannel`
尾部 `addFirst` 嗅探 handler，ViaFabric 同款注入点，三版本零差异）。

## 构建

Gradle 进程需 Java 21；产物字节码按版本 toolchain 固定（1.16.5→Java 8，
1.18.2/1.20.1→Java 17）。

```bash
../../build-natives.sh                 # 打进 jar 的 agent 二进制（无密钥）
cd 1.20.1 && JAVA_HOME=<jdk21> ./gradlew build
```

`build/libs/` 下 `-forge`/`-fabric` 分类器的 jar 是各 loader 的发布版。
首次构建 arch-loom 要下载 MC 与映射；国内网络下用 `curl -L -C -` 把大件补进
`~/.m2`（`settings.gradle` 里 `mavenLocal()` 与 BMCLAPI 镜像已排前）。

CI 在 `.github/workflows/build.yml` 的 `mod-modern` 矩阵里构建全部六个产物
（三版本 × Forge/Fabric），`fail-fast: false` 让各版本独立报状态。
