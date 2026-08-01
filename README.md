# xtcpinmc — GTNH 服务器 P2P 直连

让玩家绕过中转节点，通过 frp xtcp 打洞直连 GTNH 服务器。两种接入方式：独立 agent（打开游戏就能在"局域网游戏"里看到服务器，不用手动填地址），以及 Minecraft mod（进服后后台打洞，成功了自动切换连接，见 `mod/`）。隧道方案经 `internal/backend` 抽象，frp xtcp 是当前实现。

## 实测结论（2026-07-29，真机端到端验证）

方案可行，且收益很大。测试用一台家用 Mac 当客户端，真实打洞连上了生产中的 GTNH 服务器。

**打洞成功。** 两端 NAT 类型都是 `EasyNAT` / `BehaviorNoChange`，frp 0.70 用 QUIC 建隧道，从发起到成功约 1 秒。

**延迟对比**（Minecraft Server List Ping 完整往返，各测 4 次取中位数）：

| 线路 | SLP 往返 |
|---|---|
| **P2P 直连 (xtcp)** | **49 ms** |
| 宿迁01 中转 | 156 ms |
| 宿迁00 中转 | 161 ms |
| 枣庄 中转 | 175 ms |
| 台州 中转 | 214 ms |

比最快的中转还快 3 倍以上。对 GTNH 这种交互密集的整合包，体感差异会非常明显。

**通路验证**：握手拿到 `{'name': '1.7.10', 'protocol': 5}`，MOTD 与在线人数正确返回。

### 数据确实没走 frps（网络层验证）

"日志说打洞成功"不等于数据真的走了那条路，所以单独验证了一次：通过隧道拉一个 20 MB 文件，同时观察 frps。

| 观测项 | 数值 |
|---|---|
| 隧道实际传输 | 20,971,520 字节，耗时 34 s |
| frps 网卡 RX 增量 | 约 1.46 MB |
| frps 网卡 TX 增量 | 约 1.62 MB |
| 传输期间经 frps:7000 的包 | 24 个（12 上行 / 12 下行，即心跳） |

若数据走中转，frps 必须先收 20 MB 再发 20 MB，两个方向都得有 20 MB 量级的增量。实际那 1.5 MB 左右是 rustdesk、headscale、nginx 和另外几个 frpc 在这 34 秒里的正常背景流量。

架构上还有一条更根本的理由：**xtcp 打洞失败时不会退化成经 frps 中转**，那是 stcp 才有的行为。验证用的配置里没有 `fallbackTo`、服务端也没注册 stcp，所以不存在"慢但能用"的中间态——打不通就是连接失败。

**吞吐实测 616 KB/s（约 4.9 Mbps）**，这是家宽上行的天花板而非隧道开销。GTNH 联机带宽需求很低，完全够用；但让玩家经隧道拉存档或资源包会卡在这里。

### agent 端到端验证

用注入过密钥的二进制，两端都**零参数**启动：

| 验证项 | 结果 |
|---|---|
| `xtcpinmc serve`（宿主机，零参数） | 注册 `gtnh-p2p` + `gtnh-relay` 成功 |
| `xtcpinmc join`（客户端，零参数） | 打洞成功，对端 `203.0.113.20` |
| 隧道过 Minecraft 协议 | `1.7.10 / protocol 5`，2 人在线，43–84 ms |
| 组播广播 | 4 张网卡全部发出，解析为「涟漪GT:New Horizons」→ `192.168.0.108:25565` |
| `start` / `stop` | 后台启停正常，重复 start 被拦，stop 后端口释放 |

注意广播里的地址是 `192.168.0.108`（网卡地址）而非回环地址，与坑 3 一致——这条真实路径也单独验证过能连通。

## 三个实测踩到的坑

这几个都是会直接导致"连不上"或"列表里看不到"的硬问题，实现时必须处理。

### 1. 默认 STUN 服务器不可用

frp 默认的 `stun.easyvoip.com:3478` 在服务器所在网络**实测超时**。测过的几个：

| STUN | 结果 |
|---|---|
| `stun.miwifi.com:3478` | ✅ 可用，返回 2 个地址 |
| `stun.easyvoip.com:3478`（默认） | ❌ 超时 |
| `stun.qq.com:3478` | ❌ 超时 |
| `turn.cloudflare.com:3478` | ❌ 超时 |
| `stun.chat.bilibili.com:3478` | ❌ 只返回 1 个地址，frp 要求至少 2 个 |

**两端都必须显式配 `natHoleStunServer`。** 只配一端，另一端会静默失败。
（此坑只影响手工 frpc 配置——agent 会自动注入选好的 STUN。）

frp 的 `natHoleStunServer` 只接受单个值，不支持备选列表，押在一台上就是单点。
agent 已在 `internal/stunpick` 里解决：默认自带多个候选，启动前并行探测、
按「至少返回 2 个映射地址」筛选，注入一台当场验证过的。残余风险是候选全部为
第三方公共服务（且实测 `stun.miwifi.com` 会间歇性超时），自建 STUN 加入候选池
是可选的加固手段（坑见下节）。

### 自建 coturn 的硬约束：需要两个公网 IP

frp 探测 NAT 的 mapping behavior，需要 STUN 从**两个不同地址**响应（RFC5780 的 OTHER-ADDRESS）。上表印证了这点：bilibili 那个只返回 1 个地址，frp 直接报 `need 2, got 1` 拒绝工作。

标准 RFC5780 要求两个不同的**公网 IP**，而 203.0.113.10 是单 IP。单 IP 配双端口（3478/3479）coturn 能跑起来、frp 大概率也不报错，但探测结果会**偏乐观**——只有目标端口变化、目标 IP 没变，Address-and-Port-Dependent 的 NAT 会被误判成 EasyNAT。结果是 frp 以为能打洞、实际打不通，比直接报错更难排查。

要做就做对：加一个阿里云 EIP（很便宜），coturn 绑两个 IP。

另外两点：
- 该机器 podman 里**并没有 coturn 镜像**，只有 rustdesk-server 和 headscale
- headscale 自带的 DERP STUN（`203.0.113.10:3478`）实测 frp 用不了，不能复用

### 2. 组播必须显式指定出接口

局域网广播注入依赖组播 `224.0.2.60:4445`。在测试机上，不设 `IP_MULTICAST_IF` 时**一个包都收不到**——因为默认路由走的是 VPN 虚拟网卡（utun4），组播包发去了错误的接口。显式绑定到真实网卡（en0）后立刻正常。

玩家电脑上装 VPN、VMware/VirtualBox、Hyper-V、WSL 的情况非常普遍，这个坑几乎必然遇到。实现时要枚举网卡，挑真实物理网卡发包，或者干脆在所有候选网卡上都发一遍。

### 3. bindAddr 不能只填 127.0.0.1

Minecraft 取的是**广播包的源 IP**加上 `[AD]` 里的端口。实测源 IP 是网卡地址（`192.168.0.108`），不是 `127.0.0.1`。所以一旦要做广播注入，visitor 的 `bindAddr` 必须是 `0.0.0.0`，否则 MC 会去连一个没人监听的地址。

TTL 的选择：实测 `TTL=0` 配合 `IP_MULTICAST_LOOP=1` 能正常回环到本机，且包不出网。默认用 TTL=0，这样不会污染玩家的真实局域网（否则同网段其他人会看到一个连不上的"世界"）。

## 架构

因为是专用服务器而非"对局域网开放"的单人世界，端口固定在 25565，服务端不需要动态发现端口，比一般的联机工具简单一截。

```
GTNH 宿主机                      frps (203.0.113.10:7000)         玩家机器
127.0.0.1:25565                  仅做信令协调，不转发流量           frpc visitor
  └─ frpc (xtcp proxy) ──────────────── 控制连接 ────────────────── 0.0.0.0:25565
        │                                                              │
        └────────── QUIC over UDP，打洞后直连，不经过 frps ────────────┘
                                                                       │
                                              组播 224.0.2.60:4445 ────┘
                                              → MC「局域网游戏」自动出现
```

打洞失败时 `fallbackTo` 走 stcp 经 frps 中转，同时后台继续打洞，成功后下一条连接自动升级。

## 代码结构

```
cmd/xtcpinmc/       CLI 入口；daemon_{unix,windows}.go 处理平台差异
internal/backend/   隧道方案的统一接口与注册表；frp xtcp 是首个实现
internal/tunnel/    以库的方式嵌入 frpc，无独立进程、无 toml
internal/authplugin/ frps 的 HTTP server plugin：每玩家令牌校验（authplugin 子命令）
internal/authbridge/ 预认证服务：hasJoined 撮合验证 accessToken，提前签发令牌与凭证（authbridge 子命令）
internal/credfile/   凭证缓存文件编解码，与 Java 侧 CredentialCache 兼容（prefetch 子命令用）
internal/mcping/    Minecraft Server List Ping，用游戏握手判定隧道就绪
internal/stunpick/  启动前并行探测候选，挑一个当场验证过的 STUN
internal/lanbeacon/ 组播广播，含多网卡枚举
internal/config/    房间标识与构建期注入的默认值
mod/                Minecraft mod 侧：Java core 驱动 agent 的 tunnel 子命令，
                    打洞成功后游戏内自动切换连接（详见 mod/README.md）
```

选择把 frpc 当**库**嵌入而不是调用二进制，是为了单文件分发、零配置文件，以及 Windows 上不弹黑窗。

## 构建

密钥通过 `-ldflags` 在构建时注入，因此不进源码仓库，而玩家拿到的二进制又是零配置可用的：

```bash
TOKEN=<frps的auth.token> SECRET=<房间密钥> ./build.sh
```

产出 Windows / macOS / Linux 五个平台的二进制，各约 13–15 MB。

## 使用

服务器宿主机：

```bash
xtcpinmc serve
```

玩家：

```bash
xtcpinmc join
```

两者都不需要参数——构建时注入过了。需要临时覆盖时用 `-server` `-room` `-port` 等，`xtcpinmc help` 有完整列表。

玩家侧启动后打开游戏，服务器会出现在「多人游戏 → 局域网游戏」里。若 25565 被本机占用，会自动改用空闲端口并把新端口写进广播包，玩家无感知。

### 接入 PrismLauncher / MultiMC

不用（或不想装）mod 的玩家可以走启动器：GTNH 玩家基本都用 Prism/MultiMC，它支持实例级自定义命令。在实例设置 → Custom Commands 里填：

- **Pre-launch command**: `path/to/xtcpinmc start`
- **Post-exit command**: `path/to/xtcpinmc stop`

`start` 派生后台进程后立即返回，不会卡住游戏启动（Pre-launch 是阻塞等待的，所以不能直接写 `join`）。把这两行预置进你分发的整合包实例 `instance.cfg`，玩家启动游戏自动连、退出自动断，全程无操作。

重复 `start` 会被 PID 文件拦下，不会起两个实例；PID 文件也会校验进程是否真的存活，崩溃或重启留下的陈旧记录不会阻塞下次启动。

### 预拉取凭证（首次进服即直连）

mod 方式下，玩家首次进服要先走中转、登录后拿凭证、后台打洞、成功后重连切换——玩家会看到"进去几秒后自动退出重连"。预拉取凭证把这个过程提前到启动器阶段：玩家点连接服务器前，直连隧道已就绪，第一次进服就是直连。

**安全模型**：复现 MC 原生进服验证的 hasJoined 撮合。accessToken 全程只在「玩家本机 prefetch 程序 ↔ 皮肤站」之间，authbridge 碰不到 token——与 MC 同款安全模型。authbridge 无状态，serverId 是随机串，状态全在皮肤站。

```
① prefetch → authbridge /prefetch     领取随机 serverId（不带 token）
② prefetch → 皮肤站 /join             带 accessToken + serverId 报到（token 只到这步）
③ prefetch → authbridge /confirm      authbridge 调皮肤站 /hasJoined 查证
   ↳ 通过 → 签发玩家令牌 + 组装凭证 → base64 返回
④ prefetch 把凭证写进 .minecraft/xtcpinmc/credentials/
   ↳ 游戏启动时 WarmupController 读缓存 → 预热打洞 → 首次进服即直连
```

**服务端部署**（与 authplugin 并列，手动跑一个进程）：

```bash
xtcpinmc authbridge \
  -listen 127.0.0.1:7201 \
  -key <签发密钥，与authplugin -key同值> \
  -authserver https://skin.example.com/api/yggdrasil \
  -server <frps地址> -room <房间名> -secret <房间密钥> \
  -token <frps全局token> -stun <STUN> -server-port <端口>
```

房间参数必须与 `serve` 同源，否则打洞时密钥不匹配。authbridge 需对玩家机器可达，
**必须经 TLS 反代暴露**：`/confirm` 的响应里带着完整凭证（房间密钥 + frps 全局
token），明文 HTTP 等于把它们交给路径上的任何人；serverId 被截获还可能让凭证被
抢领。令牌有效期用 `-token-ttl-days` 调（默认 30 天，与服务端 mod 的
`tokenTtlDays` 同语义）。

authbridge 自带面向公网的加固：HTTP 超时、4 KiB 请求体上限、username/uuid/
serverId 形状校验（顺带掐死日志注入）、每来源 IP 限流（默认 30 次/分钟，
`-rate-per-ip` 可调）与 hasJoined 外呼并发上限。部署在反代之后时加
`-trust-proxy-header`，限流与日志改用 `X-Forwarded-For` 的首跳——否则所有
请求在它眼里都来自反代自己，限流会把全体玩家算作同一个来源；反之**直接
暴露时绝不能开**，伪造的头能绕过限流。

`secret=auto` 场景注意：authbridge 是独立进程，密钥取自启动旗标。服务端重启换
密钥后要**随之重启 authbridge**，否则它下发的凭证一直打不通——玩家会退回中转
进服后自愈，不致不可用，但预拉取就白做了。

皮肤站与 authbridge 地址可经 `build.sh` 的 `AUTHSERVER`/`AUTHBRIDGE` 注入为
内置默认值，玩家侧命令即可省去 `-authserver`/`-bridge`。

**玩家端**（启动器 Pre-launch 调用，PrismLauncher 示例）：

```
xtcpinmc prefetch \
  -bridge https://authbridge.example.com \
  -authserver https://skin.example.com/api/yggdrasil \
  -token ${auth_access_token} \
  -uuid ${auth_uuid} \
  -username ${auth_player_name} \
  -cache-dir .minecraft/xtcpinmc/credentials
```

accessToken 也支持环境变量 `XTCPINMC_ACCESS_TOKEN` 传入（避免出现在进程列表里）。不同启动器的变量名需各自对照。

`-bridge` 与 `-authserver` 强制 https：这两条链路上分别走着凭证与 accessToken。
回环地址豁免（本机调试、SSH 端口转发都还好用）；确要明文 http 需显式加
`-insecure-http`。

prefetch 失败（网络问题、token 过期等）不阻断游戏——玩家走原有中转进服流程，进服后 mod 照常下发凭证、后台打洞，体验退化为原状而非不可用。

### 部署到 MCSManager

你现有的 6 个 frpc 都是以 MCSManager 实例管理的，新增的保持一致即可：工作目录放二进制，启动命令 `./xtcpinmc serve`。

## 待办

- [x] Go client agent：内嵌 frpc + 组播广播，单文件可执行
- [x] 多网卡枚举（见坑 2）
- [x] 后台模式与启动器集成
- [x] 预拉取凭证（authbridge + prefetch）：首次进服即直连，免重连
- [ ] Windows 首次运行的防火墙弹窗——需要签名安装器预写规则，否则"无感"会破功
- [ ] 真机验证 1.7.10 客户端能看到并连上局域网条目（协议层已验证，缺真实客户端）
- [ ] frps 加 `allowPorts` 白名单收口（见下）
- [ ] 可选：自建 RFC5780 STUN 加入 `stunpick` 候选池（当前候选全是第三方公共服务，见上）

## 关于 token 分发的风险

你说暂时不做鉴权，那 `auth.token` 会随客户端分发给所有玩家。拿到 token 的人可以在你的 frps 上开**任意 tcp/udp 公网端口映射**，白嫖阿里云的带宽和 IP。

不写任何代码的缓解办法：在 `/etc/frp/frps.toml` 加 `allowPorts` 白名单，只放行现在实际在用的端口。xtcp / stcp 不占 `remotePort`，完全不受影响。

```toml
allowPorts = [
  { start = 5901,  end = 5901  },   # vnc-desktop-tls
  { start = 10031, end = 10032 },   # Palworld
  { start = 23333, end = 23333 },   # MCSManager 面板
  { start = 24444, end = 24444 },   # MCSManager daemon
]
```

一行配置挡掉绝大部分滥用，且不影响本方案。改完需要重启 frps，会短暂断开现有映射，建议挑无人时段。

## 参考

- [XTCP | frp 官方文档](https://gofrp.org/zh-cn/docs/features/xtcp/)
- [frp client/service.go](https://github.com/fatedier/frp/blob/dev/client/service.go) — 嵌入 frpc 的 API
- [LAN Server Discovery](https://github.com/tomsik68/mclauncher-api/wiki/LAN-Server-Discovery) — 组播包格式
