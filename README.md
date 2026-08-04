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
`auth.token`。（也可以让服务端自带会合点，那样公网侧只需要一条能到达
Minecraft 端口的普通 TCP 隧道，连 frps 都不必是——见
[内嵌会合点](#内嵌会合点)。）然后构建 mod jar（需要 Go 工具链与 JDK，
Gradle 要 Java 21+；jar 里打包的 agent 不含任何密钥）：

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
接口的契约。登录触发的升级每次都有超时上限，失败即放弃本次会话的该房间，
不反复折腾玩家的网络；启动期的预热则相反——它按退避周期持续重试（封顶后
仍继续），因为它承载着服务器列表里的直连条目。

**新增一种隧道方案**只需一个 Go 实现包加一行注册（`cmd/netherway/backends.go`）；
凭证是「backend 标识 + 参数表」，核心层不解释参数，原样转交 agent。

## 内嵌会合点

上面那张图里，frps 只干一件事：在两条控制连接之间转发打洞信令。地址发现靠
外部 STUN，打通后的数据流根本不经过它。**一个只需要收发 TCP 的会合点，没有
理由必须待在公网。**

打开 `server.rendezvous` 后，会合点变成服务端进程里的一个内嵌 frps，只监听
回环；玩家的 frp 控制连接由 mod 从 Minecraft 端口转发进去（frp 的控制通道是
TLS，首字节 `0x16 0x03`，与 MC 握手、预认证帧、legacy ping、PROXY 头最迟第
2 字节就分得开，共用一个嗅探器）。

```
MC 服务器宿主机                        公网入口                  玩家机器
127.0.0.1:25565  ← Minecraft 端口 ← 任意 TCP 转发 ← 25565 ─── frpc visitor
  └─ 内嵌 frps（仅回环）  ↑                                        │
       ↑ 嗅探器按首字节把 frp 控制连接转发进来                     │
       └────────── QUIC over UDP，打洞后直连，不经过任何中间机器 ──┘
```

于是公网那台机器对本项目**再无任何要求**：不装插件、不必支持 xtcp、不必与
本项目同版本，只要能把 TCP 转到 Minecraft 端口——而这本来就是玩家能进服的
前提。它可以是 frps 的普通 tcp 代理、nginx stream，甚至一条 NAT 规则，
**也可以是租来的隧道服务**，不必自建。

两项连带的好处：

- **玩家手里的凭证不再是公网机器的门钥匙。** 经典模式下凭证里的 `token` 就是
  frps 的全局准入令牌，分发给几十个玩家的东西正是那台机器的门禁；内嵌会合点
  的令牌只在服务端进程内有意义，填 `token=auto` 还能随每次重启轮换。你与隧道
  提供商之间的凭据从此不经玩家的手。
- **每玩家令牌的签发密钥不必再放到公网机器上。** authplugin 改由服务端在回环
  上自带（见[安全](#安全)）。

凭证也因此不再携带 `server`/`serverPort`：会合点就在这台服务器的 Minecraft
端口后面，客户端知道自己连的是哪，而服务端未必知道自己的公网入口（NAT 后、
多入口、域名与实际入口不一致都很常见）。

```
server {
    B:enabled=true
    B:rendezvous=true
    S:params <
        token=auto
        room=gtnh
        secret=auto
     >
}
```

需要 `runAgent=true`（会合点起在内置 serve 进程里）；配错成 `runAgent=false`
时会按未启用处理并告警，不会下发半成品凭证。**当前默认关闭**：会合点的绑定
范围与首字节判定有单元测试覆盖（`internal/rendezvous`、core 的 `TlsRecord`），
但还没在真实服务器上跑过一轮，等实战验证后再考虑翻默认值。

## 可选组件

以下都不是必需的——快速开始那两步就是完整部署。

### 独立 agent 二进制（build.sh）

只有一个场景用得到：托管环境禁止子进程时独立运行 serve（见下）。
密钥经 `-ldflags` 在构建时注入，不进源码仓库，产出的二进制零配置可用：

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

[内嵌会合点](#内嵌会合点)与这条互斥：会合点起在内置 serve 进程里，
独立运行的 serve 不会开它，所以 `rendezvous=true` 需要 `runAgent=true`。

## 预取凭证（预认证，可选）

不开预认证时，玩家首次进服要先走中转、登录后拿凭证、打洞、重连切换——会
看到「进去几秒后自动退出重连」；之后的启动靠缓存凭证预热直连。开启后，
mod 在游戏加载期就自动预取凭证并后台打洞（打不通按退避周期一直重试，
就绪后守望隧道、断了再打），首次启动、密钥轮换后都无需先经中转：玩家点开
服务器列表时直连条目已经就绪，中转条目只是列表里备用的另一行。

**服务器不需要多开任何端口。** 交换就在 Minecraft 自己那一个端口上完成，
靠首字节与游戏流量分辨（预认证帧以 `NWAY` 开头，MC 握手第 2 字节是包 id
`0x00`）。没开预认证的服务器会把这个帧当坏包直接断开，客户端跳过即可。

服务端 cfg：

```
server {
    B:preauth=true
    S:authServer=https://skin.example.com/api/yggdrasil
}
```

`authServer` 留空时会自动从 authlib-injector 的 `-javaagent` 参数里读出来，
所以挂了 injector 的服务端通常什么都不用填。`online-mode=false` 的服务器
没有会话服务器可查证，此项无用——那种情况下准入沿用服务器自己的名单
（白名单开着就查白名单，没开就一律放行）。

客户端 cfg 里写明要问哪些服务器：

```
client {
    S:prefetchServers <
        play.example.com:25565
     >
}
```

整合包预置这一行，玩家就零配置。留空时默认**不**预取；要让它自动扫描
服务器列表，得开 `experimental.zeroConfigPrefetch`（默认关，风险见 cfg
里那一项的注释），或者由服务端在玩家登录后下发策略替他打开
（`experimental.grantZeroConfigPrefetch`）——后者的应答者是玩家确实登录过
的服务器，比盲扫整个列表可控得多。

流程复现 MC 原生进服验证的 hasJoined 撮合。accessToken 全程只在「玩家本机
↔ 皮肤站」之间，服务端碰不到它；serverId 是随机串，且只在**那一条连接**上
有效，所以服务端不存任何状态：

```
① 客户端 → MC 端口   HELLO：自报玩家名/UUID，换回 serverId
② 客户端 → 皮肤站    /join 带 accessToken + serverId 报到（token 只到这步）
③ 客户端 → MC 端口   CONFIRM：服务端调皮肤站 /hasJoined 查证
   ↳ 通过 → 签发玩家令牌 + 组装凭证 → 随响应返回
④ 凭证写进 .minecraft/netherway/credentials/
   ↳ mod 的预热循环随即用它打洞 → 首次进服即直连
```

预取失败（网络问题、token 过期、服务器没开预认证等）不阻断游戏——玩家走
原有中转进服流程，体验退化为原状而非不可用。

## 安全

**凭证不随客户端分发。** mod 路径下，凭证由服务端在玩家通过既有正版验证 /
白名单登录后才下发——能拿到密钥的必然是有权进服的人，因此不需要另建一套
鉴权系统。凭证换来的隧道也只通向 MC 端口。

**frps 全局 token 的泄露面需要收口。** 经典模式下 token 会随凭证下发给所有
玩家，拿到它的人可以在 frps 上开任意 tcp/udp 公网端口映射。按部署成本从低
到高：

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

3. **开[内嵌会合点](#内嵌会合点)**：这个问题从根上不再存在——玩家拿到的
   token 只对服务端进程内那个会合点有意义，公网机器的凭据从不经玩家的手。
   每玩家令牌照旧可用：配了 `tokenSigningKey` 时，服务端会在回环上自带一个
   只服务本进程的 authplugin 端点，**签发密钥因此不必再放到公网机器上**。
   开了这条，前两条就都不必做了。

## 已知限制

- 打洞成功率取决于两端 NAT 类型，对称 NAT 大概率失败——失败时玩家留在
  既有中转线路上，不影响游玩。
- Windows 首次运行有防火墙弹窗——预写规则需要签名安装器，暂未做。
- 吞吐受玩家家宽上行限制（实测约 616 KB/s 即为上行天花板）。联机绰绰有余，
  但不适合让玩家经隧道拉存档或资源包。

## 代码结构

```
cmd/netherway/       CLI 入口（serve / tunnel / authplugin）
internal/backend/    隧道方案的统一接口与注册表；frp xtcp 是首个实现
internal/tunnel/     以库的方式嵌入 frpc，无独立进程、无 toml
internal/rendezvous/ 以库的方式嵌入 frps，只监听回环：内嵌会合点
internal/authplugin/ 每玩家令牌校验；既可作 frps 的 httpPlugins 独立部署，
                     也由内嵌会合点在回环上自带
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
