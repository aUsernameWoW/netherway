# Netherway

让 Minecraft 玩家绕过中转节点，通过 [frp](https://github.com/fatedier/frp) 的
xtcp 打洞与服务器 P2P 直连。

为 GTNH（GregTech: New Horizons，Forge 1.7.10，跑在现代 JVM 上）而做，但架构
上不与它绑定：隧道方案经 `internal/backend` 接口抽象（frp xtcp 是当前实现），
mod 的核心层不含任何 Minecraft 类型，换游戏版本或加载器只需重写一层薄适配
（见 [mod/README.md](mod/README.md)）。

实测收益：P2P 直连的 Server List Ping 往返 **31–49 ms**，同一客户端经中转
节点为 156–214 ms。打洞成功后游戏流量不经过任何第三方机器——这一点做过网络层
验证（经隧道传 20 MB，frps 侧只看到心跳包），完整的端到端验证记录与踩坑见
[docs/field-notes.md](docs/field-notes.md)。

## 快速开始

前提：一台公网可达的机器上跑着 frp 服务端（frps），手里有它的地址与
`auth.token`。然后构建 mod jar（需要 Go 工具链与 JDK，Gradle 要 Java 21+；
jar 里打包的 agent 不含任何密钥）：

```bash
./mod/build-natives.sh
cd mod/platform/forge-1.7.10 && ./gradlew build   # 产物在 build/libs/
```

部署就两步：

1. **服务端**：jar 丢进 `mods/`，启动前存一份 `config/netherway.cfg`：

   ```
   server {
       B:enabled=true
       S:params <
           server=frps.example.com
           serverPort=7000
           token=换成frps的auth.token
           room=gtnh
           secret=auto
        >
   }
   ```

   完整模板与逐项说明（每玩家令牌、PROXY protocol 等）见
   [mod/platform/forge-1.7.10/README.md](mod/platform/forge-1.7.10/README.md)。

2. **客户端**：同一个 jar 丢进 `mods/`，零配置。

就这些。玩家先经既有的中转隧道正常进服，服务端在登录后下发 P2P 凭证，
客户端后台打洞，成功了自动切换连接，失败就安静地留在原线路上；没装 mod
的客户端照常进服，完全不受影响。从第二次启动起，缓存的凭证会在游戏加载
期间提前打洞，并在服务器列表里维护一个直连条目。

## 工作原理

服务器端口固定（专用服务器，非「对局域网开放」的单人世界），服务端不需要
动态发现端口，比一般的联机工具简单一截：

```
MC 服务器宿主机                  frps (203.0.113.10:7000)           玩家机器
127.0.0.1:25565                  仅做信令协调，不转发流量           frpc visitor
  └─ frpc (xtcp proxy) ──────────────── 控制连接 ────────────────── 127.0.0.1:空闲端口
        │                                                              │
        └────────── QUIC over UDP，打洞后直连，不经过 frps ────────────┘
                                                                       │
                                             mod 把游戏连接切换到该端口 ┘
```

frpc 以**库**的形式内嵌进 agent 而不是调用二进制：单文件分发、零配置文件、
Windows 上不弹黑窗。

**就绪判断靠主动探测。** frp 没有提供查询打洞状态的 API，所以 agent 用
Minecraft 自己的握手（Server List Ping，`internal/mcping`）判断隧道是否真的
可用——顺带确认了服务端进程在响应，而不只是端口被监听着。

**打洞失败不影响游玩，也刻意不做中转兜底。** mod 用的 `tunnel` 子命令建链
失败就退出——玩家此刻本来就连着中转，留在原连接上即可；而且有兜底通道的话
「隧道可用」的探测会永远成功，反而分不清到底打没打通。这条已是 backend
接口的契约。打洞的时机是每次启动（预热）与每次登录（凭证下发），每次都有
超时上限，失败后等下一个自然时机再试，不做后台无限重试。

**新增一种隧道方案**只需一个 Go 实现包加一行注册（`cmd/netherway/backends.go`）；
凭证是「backend 标识 + 参数表」，核心层不解释参数，原样转交 agent。

## 可选组件

以下都不是必需的——快速开始那两步就是完整部署。

### 独立 agent 二进制（build.sh）

只有两个场景用得到：托管环境禁止子进程时独立运行 serve（见下），以及
预拉取凭证的玩家侧 prefetch（见下节）。密钥经 `-ldflags` 在构建时注入，
不进源码仓库，产出的二进制零配置可用：

```bash
cp build.env.example build.env   # 填入你的 frps 地址等部署参数
TOKEN=<frps的auth.token> SECRET=<房间密钥> ./build.sh
```

产出 Windows / macOS / Linux 五个平台的二进制，各约 13–15 MB。

**发布纪律**：`build.sh` 的产物（`bin/`）内嵌 frps 令牌与房间密钥，只能经
私有渠道分发给本服玩家，**绝不能挂公开 Release 或 CI artifact**。公开渠道
只发 mod jar——它打包的 agent 由 `mod/build-natives.sh` 构建，刻意不注入
任何密钥。

### 独立运行 serve

默认不需要：服务端 mod 会内置启动 serve（cfg 的 `server.runAgent=true`，
参数与下发凭证同源）。托管环境禁止子进程时，把 cfg 改为 `runAgent=false`，
在宿主机上独立运行 `netherway serve`（普通前台进程，交给 systemd /
MCSManager 等托管即可；参数构建时已注入，临时覆盖用 `-server` `-room` 等）。
两种方式**只能开一个**——同名代理会在 frps 上注册冲突。

## 预拉取凭证（可选，尚未在生产部署）

已实现、有端到端测试（stub 皮肤站走通全流程），但**尚未在任何生产环境
部署过**——以下是设计与部署方式，供需要时取用。

默认流程里，玩家首次进服要先走中转、登录后拿凭证、打洞、重连切换——会看到
「进去几秒后自动退出重连」。预拉取把这个过程提前到启动器阶段：玩家点连接
服务器前，直连隧道已就绪。

安全模型复现 MC 原生进服验证的 hasJoined 撮合：accessToken 全程只在「玩家
本机 prefetch 程序 ↔ 皮肤站」之间，authbridge 碰不到 token。authbridge
无状态，serverId 是随机串，状态全在皮肤站。

```
① prefetch → authbridge /prefetch     领取随机 serverId（不带 token）
② prefetch → 皮肤站 /join             带 accessToken + serverId 报到（token 只到这步）
③ prefetch → authbridge /confirm      authbridge 调皮肤站 /hasJoined 查证
   ↳ 通过 → 签发玩家令牌 + 组装凭证 → base64 返回
④ prefetch 把凭证写进 .minecraft/netherway/credentials/
   ↳ 游戏启动时 mod 读缓存 → 预热打洞 → 首次进服即直连
```

**服务端**（与 authplugin 并列，独立进程）：

```bash
netherway authbridge \
  -listen 127.0.0.1:7201 \
  -key <签发密钥，与authplugin -key同值> \
  -authserver https://skin.example.com/api/yggdrasil \
  -server <frps地址> -room <房间名> -secret <房间密钥> \
  -token <frps全局token> -stun <STUN> -server-port <端口>
```

部署要点：

- 房间参数必须与 `serve` 同源，否则打洞时密钥不匹配。
- **必须经 TLS 反代暴露**：`/confirm` 的响应里带着完整凭证（房间密钥 +
  frps 全局 token），明文 HTTP 等于把它们交给路径上的任何人。
- 反代之后要加 `-trust-proxy-header`，限流与日志改用 `X-Forwarded-For`
  的首跳——否则所有请求都被算作来自反代自己；反之**直接暴露时绝不能开**，
  伪造的头能绕过限流。
- 自带面向公网的加固：HTTP 超时、4 KiB 请求体上限、参数形状校验、每来源
  IP 限流（`-rate-per-ip`）与 hasJoined 外呼并发上限。
- `secret=auto`（房间密钥随服务端重启轮换）场景下，服务端重启后要**随之
  重启 authbridge**，否则它下发的凭证一直打不通——玩家会退回中转进服后
  自愈，不致不可用，但预拉取就白做了。

**玩家端**（启动器 Pre-launch 调用，PrismLauncher 变量示例）：

```
netherway prefetch \
  -bridge https://authbridge.example.com \
  -authserver https://skin.example.com/api/yggdrasil \
  -token ${auth_access_token} \
  -uuid ${auth_uuid} \
  -username ${auth_player_name} \
  -cache-dir .minecraft/netherway/credentials
```

accessToken 也可经环境变量 `NETHERWAY_ACCESS_TOKEN` 传入（避免出现在进程
列表里）。`-bridge` 与 `-authserver` 强制 https（回环地址豁免，本机调试与
SSH 端口转发不受影响；确要明文需显式 `-insecure-http`）。两个地址可经
`build.sh` 的 `AUTHSERVER`/`AUTHBRIDGE` 注入为内置默认值，玩家侧命令即可
省去这两个旗标。

prefetch 失败（网络问题、token 过期等）不阻断游戏——玩家走原有中转进服
流程，体验退化为原状而非不可用。

## 安全

**凭证不随客户端分发。** mod 路径下，凭证由服务端在玩家通过既有正版验证 /
白名单登录后才下发——能拿到密钥的必然是有权进服的人，因此不需要另建一套
鉴权系统。凭证换来的隧道也只通向 MC 端口。

**frps 全局 token 的泄露面需要收口。** token 会随凭证下发给所有玩家，拿到
它的人可以在 frps 上开任意 tcp/udp 公网端口映射。按部署成本从低到高：

1. **`allowPorts` 白名单**（零代码）：在 frps.toml 里只放行实际在用的端口。
   xtcp / stcp 不占 `remotePort`，完全不受影响：

   ```toml
   allowPorts = [
     { start = 25565, end = 25565 },   # 按 frps 上实际在用的映射端口填写
   ]
   ```

2. **部署 authplugin**（frps 的 httpPlugins，`netherway authplugin` 子命令，
   配置见 [mod/platform/forge-1.7.10/README.md](mod/platform/forge-1.7.10/README.md)
   部署章节）：在全局 token 之上叠加每玩家令牌（绑定 UUID、带有效期、HMAC
   签名，服务端登录时签发）。迁移完成后关掉 `-allow-legacy`，光有全局 token
   连登录都过不了，注册代理只认 serve 的静态令牌，泄露的滥用面收敛到零。

## 已知限制

- 打洞成功率取决于两端 NAT 类型，对称 NAT 大概率失败——失败时玩家留在
  既有中转线路上，不影响游玩。
- Windows 首次运行有防火墙弹窗——预写规则需要签名安装器，暂未做。
- 吞吐受玩家家宽上行限制（实测约 616 KB/s 即为上行天花板）。联机绰绰有余，
  但不适合让玩家经隧道拉存档或资源包。

## 代码结构

```
cmd/netherway/       CLI 入口（serve / tunnel / authplugin / authbridge / prefetch）
internal/backend/    隧道方案的统一接口与注册表；frp xtcp 是首个实现
internal/tunnel/     以库的方式嵌入 frpc，无独立进程、无 toml
internal/authplugin/ frps 的 HTTP server plugin：每玩家令牌校验（authplugin 子命令）
internal/authbridge/ 预认证服务：hasJoined 撮合验证，提前签发令牌与凭证（authbridge 子命令）
internal/credfile/   凭证缓存文件编解码，与 Java 侧 CredentialCache 兼容（prefetch 子命令用）
internal/mcping/     Minecraft Server List Ping，用游戏握手判定隧道就绪
internal/stunpick/   启动前并行探测候选，挑一个当场验证过的 STUN
internal/config/     房间标识与构建期注入的默认值
mod/                 Minecraft mod 侧：Java core 驱动 agent 的 tunnel 子命令，
                     打洞成功后游戏内自动切换连接（详见 mod/README.md）
```

## 参考

- [实测记录：端到端验证与踩坑](docs/field-notes.md) — 本 README 所有实测
  数字的出处，含 STUN 选型、组播出接口、bindAddr 三个必踩的坑
- [XTCP | frp 官方文档](https://gofrp.org/zh-cn/docs/features/xtcp/)
- [frp client/service.go](https://github.com/fatedier/frp/blob/dev/client/service.go) — 嵌入 frpc 的 API
- [THIRD-PARTY-NOTICES](THIRD-PARTY-NOTICES.md)
