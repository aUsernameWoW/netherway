# platform/forge-1.12.2 — Forge 1.12.2 适配层

core 的 Forge 1.12.2 接线，与 [`forge-1.7.10`](../forge-1.7.10) 逐类同构——
1.12.2 与 1.7.10 的差异主要是包名（`cpw.mods.fml` → `net.minecraftforge.fml`）
与少量 API 改名，产品逻辑一字未改。同一个 jar 同时装在服务端与客户端。

与 1.7.10 的实际改动点：

- 包名 `cpw.mods.fml.*` → `net.minecraftforge.fml.*`；
- 事件总线合并：1.12.2 的 FML 总线已并入 `MinecraftForge.EVENT_BUS`，
  不再区分两条 bus；
- `MinecraftServer.getServer()`（静态，1.8 起移除）→
  `FMLCommonHandler.instance().getMinecraftServerInstance()`；
- 客户端 API 改名：`theWorld`/`thePlayer` → `world`/`player`、
  `ChatComponentText` → `TextComponentString`、`addChatMessage` → `sendMessage`、
  `GuiOpenEvent` 的公共字段 `gui` → `getGui()`/`setGui()`、
  `CommandBase` 的 `processCommand` → `execute`、
  `ServerData` 构造多了 `isLan` 布尔参数；
- `FMLProxyPacket` 的 payload 要包一层 `PacketBuffer`；
- `RouteAwareGuiHandler`/`ConnectionSniffer` 反射用的 SRG 字段/方法号按
  1.12.2 的 `mcp_stable` 映射重新核对（多数与 1.7.10 相同，`getServerData`、
  `connectToSelected`、`getOldServerPinger` 等一致）。

没装 mod 的客户端照常进服（`acceptableRemoteVersions = "*"`）。

## 构建

同样用 [RetroFuturaGradle](https://github.com/GTNewHorizons/RetroFuturaGradle)
（它官方同时支持 1.7.10 与 1.12.2；`mcp_stable` 映射默认即可，无需显式指定）。
Gradle 进程需 Java 21+，产物经 toolchain 固定为 Java 8 字节码。

```bash
../../build-natives.sh        # 先产出打进 jar 的 agent 二进制（不含密钥）
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./gradlew build
```

产物在 `build/libs/`：不带分类器的 jar 是重混淆后的发布版，`-dev` 是开发环境用的。
`build` 会先跑 `configSelfTest`——不启动游戏，用 Forge 1.12.2 真实的
`Configuration` 解析器回归 `ModConfig`（cfg 语义与 1.7.10 同一套断言）。

## 服务端配置

与 1.7.10 完全相同的 `config/netherway.cfg`：键名、语义、默认值、注释语言
一字不差。服主从 1.7.10 迁到 1.12.2 无需改配置。详见 [`forge-1.7.10/README.md`](../forge-1.7.10/README.md)。
