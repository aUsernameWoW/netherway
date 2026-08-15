# Netherway 中文文档

[返回项目主页](../../README.md) | [English](../en/README.md)

## 前提

服务端需要一个能被外网访问的地址，两种方式：

- **自建 frps**：一台有公网 IP 的机器跑 frps
- **（推荐）内嵌会合点 + 端口转发**：使用樱花 frp、花生壳等任何第三方 TCP 转发，让 MC 服本身有一个能被公网访问的地址即可。服主不必自建 frps，配置最简单

不管哪种方式，打洞成功后游戏流量都不经过第三方。

## 怎么工作

```text
游戏服务器                    frps / 端口转发               玩家
  │                               │                        │
  ├─ frpc(xtcp) ── 控制连接 ───────┼────────────── frpc ────┤
  │                               │                        │
  └──────── UDP 直连，不经过第三方 ──────────────────────────┘
```

frpc 以库的形式内嵌在 mod 自带的 agent 里，由 mod 自动启停，玩家无需安装或运行任何额外程序。

## 安装

下载与你的 Minecraft 版本和平台匹配的 Netherway：

- Forge / Fabric 版：客户端和服务端分别放入对应实例的 `mods/` 文件夹。
- Sponge 版：放入服务端的 `mods/` 文件夹。
- Bukkit 版：放入服务端的 `plugins/` 文件夹。

目前仅提供 Forge 1.7.10 版，其他平台版本尚未发布。

首次启动会自动生成 `<MC服务端根目录>/config/netherway.cfg`。当前默认配置采用下面的方式二，通常不需要修改；如有需要，可按对应方式手动修改配置，重启 MC 服务端后生效。

## 方式一：自建 frps

> 该方式需要一定的 Linux 操作基础。如果不会或没有公网 IP 的机器，建议使用方式二。

frps 跑在有公网 IP 的机器上。`config/netherway.cfg` 示例：

```text
server {
    B:enabled=true
    B:rendezvous=false
    S:params <
        server=frps.example.com
        serverPort=7000
        token=1234abcd
        room=testroom
        secret=auto
     >
}
```

- `server` / `serverPort`：你自建的 frps 地址和端口，不是 MC 端口
- `token`：你自建的 frps 的 `auth.token`，玩家需要它连接 frps
- `secret=auto`：每次重启随机生成房间密钥，旧凭证自动失效
- `room`：xtcp 房间名，使用不包含空格的英文名称即可

frps 侧需要部署 authplugin，防止玩家拿到 frps token 后滥用它建立其他代理或开放端口。authplugin 是一个独立 HTTP 服务，运行在 frps 所在机器上，frps 通过 `httpPlugins` 配置调用它。

### 步骤 1：复制 agent

把 `build-natives.sh` 编译出的 `netherway` 二进制程序复制到 frps 所在机器，放在哪个目录都可以。

### 步骤 2：启动 authplugin

签发密钥和静态令牌是两个由你自行生成的非空随机字符串，建议使用足够复杂的值：

```bash
# 该命令需要在 frps 所在机器上运行
NETHERWAY_AUTH_KEY=<签发密钥> ./netherway authplugin \
  -static-token <静态令牌> -allow-legacy
```

authplugin 必须常驻运行；它停止后，frps 会拒绝所有登录。生产环境建议使用 systemd 等守护进程管理：

```ini
# 示例：/etc/systemd/system/netherway-auth.service
# 请根据实际安装路径和参数修改，不要直接照抄
[Unit]
Description=Netherway authplugin for frps
After=network.target

[Service]
Environment=NETHERWAY_AUTH_KEY=<签发密钥>
ExecStart=/path/to/netherway authplugin -static-token <静态令牌> -allow-legacy
Restart=always

[Install]
WantedBy=multi-user.target
```

### 步骤 3：配置 frps

在 `frps.toml` 中加入以下配置并重启 frps。`addr` 是 frps 调用 authplugin 的地址；两者在同一台机器上，因此只监听回环地址即可：

```toml
[[httpPlugins]]
name = "netherway-auth"
addr = "127.0.0.1:7200"
path = "/handler"
ops = ["Login", "NewProxy"]
```

如果 7200 端口被占用，请同时修改这里的端口，并在步骤 2 的启动命令中加入 `-listen 127.0.0.1:<新端口>`。

### 步骤 4：配置 MC 服务端

在 MC 服务端的 `config/netherway.cfg` 中加入与步骤 2 相同的值：

```text
server {
    S:tokenSigningKey=<签发密钥>
    S:serveAuthToken=<静态令牌>
}
```

两侧启动日志都会打印签发密钥指纹；指纹一致才表示配置正确。

authplugin 会检查 `Login`，并通过 `NewProxy` 只允许服务端的 serve 注册代理。玩家只能建立 visitor，不能建立 proxy。`-allow-legacy` 是迁移开关；可以先带着它上线，等玩家都登录过一轮后再移除。

## 方式二：内嵌会合点 + 端口转发（推荐、默认）

开启 `rendezvous=true` 后，frps 会内嵌在 MC 服务端进程中。你只需要使用任意第三方 TCP 端口转发，保证 MC 服务端本身能被公网访问。服主不必自建 frps，也不必单独部署 authplugin。

新生成的默认配置已经采用该方式，关键部分如下：

```text
server {
    B:enabled=true
    B:rendezvous=true
    S:params <
        token=auto
        room=minecraft
        secret=auto
     >
}
```

- `token=auto`：内嵌 frps 的 token 随服务端重启自动轮换，保持默认即可
- `secret=auto`：房间密钥随服务端重启自动轮换，保持默认即可
- `room=minecraft`：xtcp 房间名；如需修改，使用不包含空格的英文名称

同一台机器可以运行多个 Netherway 服务端。只要每个服务器拥有不同且稳定的公网 `host:port` 入口，它们可以继续使用相同的默认房间名；客户端会按服务器入口分别保存凭证和预热状态。

## 客户端

客户端安装对应平台的 Netherway 后无需配置。

客户端启动时会向服务器列表里的所有候选并行预取凭证，再为每个成功应答的服务串行打洞。串行打洞可以避免同一 NAT 上的多次尝试互相干扰；已经建立的多条隧道可以同时存活。

默认情况下，预热成功后玩家点击原服务器条目就会直接使用本地 P2P 隧道；`servers.dat` 中的真实地址不会被修改，所以下次启动、预取失败或移除 mod 后仍可使用原线路。若玩家在预热完成前已经进入服务器，预热成功后会立即切换。某个服务打不通时，它会独立退避并重试，不影响其他服务。

将 `client.replaceServerEntries` 设为 `false` 可改为额外维护 `[P2P直连] <房间> (<入口>)` 条目，让原入口和直连入口同时显示。

## 可选功能

### PROXY protocol（`server.proxyProtocol`）

让经 xtcp 隧道进入的连接携带真实来源地址，使服务端日志和封禁看到玩家真实 IP，而不是 `127.0.0.1`。它只对直连隧道的连接生效。

当前 frp 的 xtcp P2P 流尚未携带来源地址，上游问题见 [fatedier/frp#2748](https://github.com/fatedier/frp/issues/2748)。因此该选项配置后暂不生效；上游支持后无需再次修改配置。

## 已知限制

- 打洞成功率取决于两端 NAT 类型；对称 NAT 大概率失败。失败时仍保留原有中转线路，不影响游玩。
- Windows 首次运行可能弹出防火墙提示。
- 吞吐受连接双方上传带宽限制，家宽通常限制较多，因此无论是否直连都不适合大流量传输场景。

## 从源码构建

```bash
./mod/build-natives.sh
cd mod/platform/forge-1.7.10 && ./gradlew build
```

产物位于 `build/libs/`。Gradle 需要 Java 21+，编译产物为 Java 8 字节码。
