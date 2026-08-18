# 多版本适配设计（1.12.2 / 1.16.5 / 1.18.2 / 1.20.1）

2026-08-18 拍板。本文记录结构与决策及其依据；实现细节看各平台源码与 README。

## 目标与非目标

- 目标：Forge 1.12.2；1.16.5 / 1.18.2 / 1.20.1 各出 Forge + Fabric。
- 1.20.1 不单独做 NeoForge：该代 NeoForge 官方兼容 Forge mod，forge jar 直接可用。
  1.20.2+（网络 configuration 阶段重构、1.20.5 起 Transfer 包与 CustomPayload
  重写）是下一次适配的边界，本轮不碰。
- core 与 Go agent 零改动；平台间的差异全部收在各自的适配层里。

## 仓库结构

```
mod/platform/
  forge-1.7.10/     RFG 工作区（现状不动）
  forge-1.12.2/     RFG 工作区，1.7.10 的复刻（同插件同版本 1.4.9）
  modern/
    shared/         三个版本共用的源码目录（无自己的构建文件）
      common/       loader 无关：配置、agent 托管、嗅探核心、客户端桥（mojmap）
      fabric/       Fabric 接线（Fabric API 在 1.16.5–1.20.1 形态稳定，可共享）
    1.16.5/         独立 Gradle 工作区：common + forge + fabric 三子项目
    1.18.2/         同上
    1.20.1/         同上
```

每个 modern 工作区独立（不是一个大工程的三个子集），理由：

- **可以各自 pin Architectury Loom 版本**。现行 loom 1.17.x 对 1.16.5
  （尤其 Forge dev 环境）缺少现役验证、历史上有破损记录；独立工作区让
  1.16.5 可以在不拖累 1.18.2/1.20.1 的情况下降级 loom。
- 与仓库既有格局（每版本一个工作区）一致，不引入 Stonecutter/预处理器。
  本项目的版本差异被架构刻意收敛进少数薄适配类，摊不到需要逐行条件编译
  的程度；差异用「共享源码目录 + 每工作区的版本覆盖目录」表达即可。

## 关键决策

### 工具链

| 目标 | 构建 | mappings | 产物字节码 |
|---|---|---|---|
| forge-1.12.2 | RetroFuturaGradle 1.4.9（与 1.7.10 同款；官方 testmod1.12 与 Future-MC、CleanroomMC ForgeDevEnv 均实证支持） | RFG 内建默认（stable_39） | Java 8 |
| modern ×3 | Architectury Loom + architectury-plugin（只当工具链用） | **mojmap（含 1.16.5）** | 1.16.5 → Java 8；1.18.2 / 1.20.1 → Java 17 |

mojmap 是三版本共源的前提：1.16.5 Forge 默认的 MCP 名（ConnectingScreen /
MultiplayerScreen / unloadWorld）与 1.18.2+ 的 mojmap 名完全对不上。

### 不引入 Architectury API 运行期依赖

只用 Architectury 的 Loom/插件当构建工具，**不给 mod 增加 Architectury API
这个必装依赖**。依据：

- 它不覆盖本项目最难的两个触点（服务端 Netty pipeline 注入、连接拦截/
  入口覆盖），这两处横竖要写 Mixin；
- 它覆盖的四个触点（网络裸字节、玩家登录、client tick、screen 事件）在
  各 loader 的原生 API 上都只有一两百行胶水，换不来一个强依赖的分量
  （core 的既有原则：多一个库多一分冲突）；
- 1.16.5 上它还是另一个包名的 1.x（me.shedaniel.*），三版本共源反而要
  为它到处开条件块；
- 其网络层可能给 payload 加自己的封头，会破坏「Bukkit/Sponge 插件在同名
  频道下发凭证」的互操作承诺——凭证频道必须是**裸 vanilla custom payload**。

### 自定义频道名（Java↔Java 契约的唯一版本分叉）

- 1.7.10 / 1.12.2：`netherway`（线上 20 字符上限，9 字符无虞）。
- 1.13+（即 modern 全部）：**`netherway:main`**——ResourceLocation 强制
  namespace 且只允许小写。跨版本客户端与服务端本就不能互联，两代频道名
  不需要互通；但 Bukkit/Sponge 插件在 1.13+ 侧也要注册带 namespace 的名字。
- 载荷永远是 core 的裸字节编解码。Forge 侧用 EventNetworkChannel（不用
  SimpleChannel——它会加消息 index 前缀字节），Fabric 侧用
  ClientPlayNetworking/ServerPlayNetworking 的裸 PacketByteBuf。
- 「mod 不构成准入门槛」各版本的写法：1.12.2 `acceptableRemoteVersions="*"`；
  1.16.5+ Forge 频道版本谓词放行 ABSENT/ACCEPTVANILLA（用
  `NetworkRegistry.acceptMissingOr`，ABSENT 的类型在 1.20.1 变过，别手写比较）
  外加 DisplayTest = IGNORESERVERONLY；Fabric 天然可选。

### 配置：自研解析器，全版本同一份 netherway.cfg

Forge 的 `Configuration` 类在 1.13+ 被删（换 TOML 的 ForgeConfigSpec），
Fabric 没有内置配置。modern 平台改用 shared/common 里自研的 cfg 解析器：

- 与 Forge cfg 方言兼容（`B:`/`S:`/`I:` 前缀、类目花括号、`S:params <` 列表），
  服主在 1.7.10 到 1.20.1 之间迁移时**配置文件与文档完全不变**；
- 语义按 ModConfigSelfTest 钉死的行为逐条复刻：未知键保留、坏值回退默认、
  解析失败 fail closed 且绝不回写、注释按 general.language 首次生成后不追写、
  注释差异不触发回写；
- 与 core 的 `Json` 同一哲学：一百多行手写解析，零依赖，可脱离 MC 测试。

1.12.2 沿用真 Forge `Configuration`（类还在，API 与 1.7.10 一致），
ModConfigSelfTest 照旧用真实解析器回归。

### 嗅探器注入（一套核心、两种安装垫片）

Acceptor/Sniffer 本体是纯 Netty 4.0 API 子集代码（4.1.9→4.1.82 实测语义
不变），上提为共享代码。安装方式按代分两种：

- **1.12.2：反射**，与 1.7.10 同路线。`getNetworkSystem`（func_147137_ag）与
  `endpoints`（field_151274_e）的 SRG id 与 1.7.10 相同（已对 stable_39 的
  joined.srg/fields.csv 逐字核对）。比 1.7.10 多一条修正：endpoints 列表含
  开放局域网的本地端点，只钩 localAddress 是 InetSocketAddress 的监听端点。
- **modern（Forge+Fabric 同一个 Mixin）**：
  `@Mixin(targets="net.minecraft.server.network.ServerConnectionListener$1")`
  `@Inject(method="initChannel", at=@At("TAIL"))` 里 `pipeline.addFirst(...)`。
  这是 ViaFabric 从 1.16.5 用到 1.20.x 的同款注入点，三版本零差异，Mixin
  自动处理 mojmap/SRG/intermediary 三套运行时名，且天然只命中 TCP 端点。
  绝不 @Overwrite。

两条跨版本行为差异已吸收进共享核心：

- 拨会合点的 channel 类不再写死 NioSocketChannel，改用前端连接自己的
  channel 类（1.9+ Linux 服务端默认 epoll 原生 transport，Nio channel 注册
  不进 epoll 事件循环，写死会让转发静默全挂）；
- PROXY 剥头继续用 core 手写解析：1.19+ 原版运行时已不带 netty-codec-haproxy，
  自带解析反而是跨版本优势。

### 客户端入口覆盖：modern 改用 Mixin，不再替换整屏

1.7.10/1.12.2 的 RouteAwareGuiHandler（screen-open 事件替换 GuiMultiplayer）
在两个老版本保留。modern 三版本 Forge+Fabric 统一改为两个更小的注入点：

- **连接改写**：Mixin 到 ConnectScreen 的连接入口（1.16.5 构造器路径、
  1.18.2 `startConnecting(Screen,Minecraft,ServerAddress,ServerData)`、
  1.20.1 五参 + boolean——签名逐版不同，Mixin 放各版本覆盖目录），在
  ServerAddress 还没做 SRV/DNS 解析之前查路由表改写目标。比替换整屏
  覆盖面更大（其他 mod 自绘界面直接连服也走这里），且 Fabric 没有可取消
  的 screen 事件，本来就只有这条路。注意**不能**用「取消 ConnectScreen
  打开」来拦连接——各版本连接线程都在 screen-open 事件可见之前/之外启动。
- **延迟探测改写**：Mixin 到 `ServerStatusPinger.pingServer(ServerData,Runnable)`
  （mojmap 三版本同签名），命中路由的探测直接发往回环端口。真实条目对象
  全程不被改写地址，`servers.dat` 零风险，也不再需要 1.7.10 那套临时副本
  镜像。

### 各版本客户端事件/API 差异（写死在各版本覆盖目录里）

| 触点 | 1.16.5 | 1.18.2 | 1.20.1 |
|---|---|---|---|
| 程序化连接 | `new ConnectScreen(...)`（构造器即发起） | `ConnectScreen.startConnecting(4 参)` | `startConnecting(5 参)`，**ServerData 必须非 null**（quickPlayLog 无空判），boolean 传 false |
| 断开世界 | `clearLevel()`（连带清 currentServer） | 同左；startConnecting 自己会先 clear | `clearLevel` 不再管 currentServer（字段已移进连接对象） |
| 当前服务器 | `getCurrentServer()`，断开后 null | 同左 | 从连接对象取，**断开即 null**——switchOrigin 兜底在此版尤其不可省 |
| ServerAddress | `client.multiplayer.ServerAddress` | 移到 `.resolver` 包 | 同 1.18.2 |
| Forge 断开事件 | `ClientPlayerNetworkEvent.LoggedOut` | 同左 | 改名 `LoggingOut`，字段可空、单机启动也触发，需过滤 |
| Forge screen 事件 | `GuiOpenEvent` | `ScreenOpenEvent` | `ScreenEvent.Opening` |
| Forge 网络包 | `fml.network` 包 | `net.minecraftforge.network` | 同 1.18.2 |
| ServerList API | `load/save/add(ServerData)` | 同左 | `add(ServerData, boolean)`（1.19.3 起） |

Fabric 侧三版本形态一致：entrypoints、`ServerPlayConnectionEvents.JOIN`、
`ClientPlayConnectionEvents.JOIN/DISCONNECT`、`ServerLifecycleEvents`、
networking api v1。

### 行为级注意事项（从 1.7.10 实测继承，全版本必须保持）

- `switchOrigin`：切换前保存原服地址的机制在四个版本都必须保留；
  1.20.1 上「断开后还能读到当前服务器」彻底不成立。
- 断开事件区分「升级引发的重连」与「真退出」的 manager 身份判别照搬；
  1.16.5+ 事件触发时机是登录层而非 socket 层，30 秒 timeout 兜底不可删。
- 预热/升级互斥、GAVE_UP 语义、去重、stderr 消费等 core 侧契约与版本无关。
- 所有新增用户可见文案进 L10n 目录（core 目录两侧共用，平台层 key 尽量
  复用现有条目）。

## 构建与 CI

本机网络不可靠，**编译验证以 GitHub CI 为准**：workflow 对五个平台工作区
分别 build（modern 的三个各出 forge+fabric 两个 jar），natives 布局
`natives/<os>-<arch>/` 与 LICENSE/THIRD-PARTY-NOTICES 的打包规则全平台一致。
产物命名：`netherway-<modVersion>+mc<mcversion>-<loader>.jar`。
