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

命令行工具与内部标识（自定义频道、缓存目录、环境变量等）沿用项目旧名
`xtcpinmc`。

## 两种接入方式

**独立 agent**：玩家跑一个单文件可执行程序，打开游戏就能在「多人游戏 →
局域网游戏」里看到服务器，不用手动填地址。适合不装 mod 的玩家，可挂进
启动器实现全自动（见下文「启动器集成」）。

**Minecraft mod**（`mod/`）：玩家先经既有中转正常进服，服务端在登录后下发
P2P 凭证，客户端后台打洞，成功了自动切换连接，失败就安静地留在原线路上。
从第二次启动起，缓存的凭证会在游戏加载期间提前打洞，并在服务器列表里维护
一个直连条目——配合预拉取凭证（见下），首次进服就能直连。

## 工作原理

服务器端口固定（专用服务器，非「对局域网开放」的单人世界），服务端不需要
动态发现端口，比一般的联机工具简单一截：

```
MC 服务器宿主机                  frps (203.0.113.10:7000)           玩家机器
127.0.0.1:25565                  仅做信令协调，不转发流量           frpc visitor
  └─ frpc (xtcp proxy) ──────────────── 控制连接 ────────────────── 0.0.0.0:25565
        │                                                              │
        └────────── QUIC over UDP，打洞后直连，不经过 frps ────────────┘
                                                                       │
                                              组播 224.0.2.60:4445 ────┘
                                              → MC「局域网游戏」自动出现
```

frpc 以**库**的形式内嵌进 agent 而不是调用二进制：单文件分发、零配置文件、
Windows 上不弹黑窗。

**就绪判断靠主动探测。** frp 没有提供查询打洞状态的 API，所以 agent 用
Minecraft 自己的握手（Server List Ping，`internal/mcping`）判断隧道是否真的
可用——顺带确认了服务端进程在响应，而不只是端口被监听着。

**打洞失败的行为按场景区分。** 独立 agent（`join`）带 stcp 中转兜底，打不通
也能玩；mod 用的 `tunnel` 子命令**刻意无兜底**——玩家此刻本来就连着中转，
建链失败就该留在原连接上，而且有兜底的话就绪探测会永远成功，反而分不清到底
打没打通。

**新增一种隧道方案**只需一个 Go 实现包加一行注册（`cmd/xtcpinmc/backends.go`）；
凭证是「backend 标识 + 参数表」，核心层不解释参数，原样转交 agent。

## 构建

密钥经 `-ldflags` 在构建时注入，不进源码仓库，而产出的二进制零配置可用。
部署参数（frps 地址、房间名等）放 gitignore 的 `build.env`：

```bash
cp build.env.example build.env   # 填入你的 frps 地址等部署参数
TOKEN=<frps的auth.token> SECRET=<房间密钥> ./build.sh
```

产出 Windows / macOS / Linux 五个平台的二进制，各约 13–15 MB。

**发布纪律**：`build.sh` 的产物（`bin/`）内嵌 frps 令牌与房间密钥，只能经
私有渠道分发给本服玩家，**绝不能挂公开 Release 或 CI artifact**。公开渠道
只发 mod jar——它打包的 agent 由 `mod/build-natives.sh` 构建，刻意不注入
任何密钥。

## 使用

服务器宿主机（`serve` 是普通前台进程，交给 systemd / MCSManager 等任意进程
管理器托管即可）：

```bash
xtcpinmc serve
```

玩家：

```bash
xtcpinmc join
```

两者都不需要参数——构建时注入过了。需要临时覆盖时用 `-server` `-room`
`-port` 等，`xtcpinmc help` 有完整列表。

玩家侧启动后打开游戏，服务器会出现在「多人游戏 → 局域网游戏」里。若 25565
被本机占用，会自动改用空闲端口并把新端口写进广播包，玩家无感知。

### 启动器集成（PrismLauncher / MultiMC）

启动器支持实例级自定义命令，在实例设置 → Custom Commands 里填：

- **Pre-launch command**: `path/to/xtcpinmc start`
- **Post-exit command**: `path/to/xtcpinmc stop`

`start` 派生后台进程后立即返回，不会卡住游戏启动（Pre-launch 是阻塞等待的，
所以不能直接写 `join`）。把这两行预置进分发的整合包实例 `instance.cfg`，
玩家启动游戏自动连、退出自动断，全程无操作。

重复 `start` 会被 PID 文件拦下，不会起两个实例；PID 文件也会校验进程是否
真的存活，崩溃或重启留下的陈旧记录不会阻塞下次启动。

### 预拉取凭证（首次进服即直连）

mod 方式下，玩家首次进服要先走中转、登录后拿凭证、打洞、重连切换——会看到
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
④ prefetch 把凭证写进 .minecraft/xtcpinmc/credentials/
   ↳ 游戏启动时 mod 读缓存 → 预热打洞 → 首次进服即直连
```

**服务端**（与 authplugin 并列，独立进程）：

```bash
xtcpinmc authbridge \
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
xtcpinmc prefetch \
  -bridge https://authbridge.example.com \
  -authserver https://skin.example.com/api/yggdrasil \
  -token ${auth_access_token} \
  -uuid ${auth_uuid} \
  -username ${auth_player_name} \
  -cache-dir .minecraft/xtcpinmc/credentials
```

accessToken 也可经环境变量 `XTCPINMC_ACCESS_TOKEN` 传入（避免出现在进程
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

2. **部署 authplugin**（frps 的 httpPlugins，`xtcpinmc authplugin` 子命令，
   配置见 [mod/platform/forge-1.7.10/README.md](mod/platform/forge-1.7.10/README.md)
   部署章节）：在全局 token 之上叠加每玩家令牌（绑定 UUID、带有效期、HMAC
   签名，服务端登录时签发）。迁移完成后关掉 `-allow-legacy`，光有全局 token
   连登录都过不了，注册代理只认 serve 的静态令牌，泄露的滥用面收敛到零。

## 已知限制

- 打洞成功率取决于两端 NAT 类型，对称 NAT 大概率失败。mod 场景失败留在
  中转不受影响；独立 agent 走 stcp 中转兜底。
- Windows 首次运行有防火墙弹窗——预写规则需要签名安装器，暂未做。
- 吞吐受玩家家宽上行限制（实测约 616 KB/s 即为上行天花板）。联机绰绰有余，
  但不适合让玩家经隧道拉存档或资源包。

## 代码结构

```
cmd/xtcpinmc/        CLI 入口；daemon_{unix,windows}.go 处理平台差异
internal/backend/    隧道方案的统一接口与注册表；frp xtcp 是首个实现
internal/tunnel/     以库的方式嵌入 frpc，无独立进程、无 toml
internal/authplugin/ frps 的 HTTP server plugin：每玩家令牌校验（authplugin 子命令）
internal/authbridge/ 预认证服务：hasJoined 撮合验证，提前签发令牌与凭证（authbridge 子命令）
internal/credfile/   凭证缓存文件编解码，与 Java 侧 CredentialCache 兼容（prefetch 子命令用）
internal/mcping/     Minecraft Server List Ping，用游戏握手判定隧道就绪
internal/stunpick/   启动前并行探测候选，挑一个当场验证过的 STUN
internal/lanbeacon/  组播广播，含多网卡枚举
internal/config/     房间标识与构建期注入的默认值
mod/                 Minecraft mod 侧：Java core 驱动 agent 的 tunnel 子命令，
                     打洞成功后游戏内自动切换连接（详见 mod/README.md）
```

## 参考

- [实测记录：端到端验证与踩坑](docs/field-notes.md) — 本 README 所有实测
  数字的出处，含 STUN 选型、组播出接口、bindAddr 三个必踩的坑
- [XTCP | frp 官方文档](https://gofrp.org/zh-cn/docs/features/xtcp/)
- [frp client/service.go](https://github.com/fatedier/frp/blob/dev/client/service.go) — 嵌入 frpc 的 API
- [LAN Server Discovery](https://github.com/tomsik68/mclauncher-api/wiki/LAN-Server-Discovery) — 组播包格式
- [THIRD-PARTY-NOTICES](THIRD-PARTY-NOTICES.md)
