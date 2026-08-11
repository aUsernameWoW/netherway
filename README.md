# Netherway

让 Minecraft 玩家和服务器之间走 P2P 直连，游戏流量不经过中转节点。类似 HMCL、PCL 启动器自带的联机功能，只不过面向的是有 正经服务端 的场景。

## 解决什么问题

国内很多 MC 服务器跑在"家里云"上——家里宽带没有公网 IP，服主靠樱花 frp 之类的第三方端口转发让玩家能连进来。所有游戏流量都经第三方转发，延迟高，许多按量付费的转发服务花销大。

Netherway 让这类服务器也能 P2P 直连：玩家和服务器打洞成功后，游戏流量直接走 UDP 互连，第三方只负责最初的信令交换，不再转发游戏数据，降低中转延迟的同时节约第三方流量费用。

## 前提

服务端需要一个能被外网访问的地址，两种方式：

- **自建 frps**：一台有公网 IP 的机器跑 frps
- **（推荐）内嵌会合点 + 端口转发**：mod 配置文件里开启 `rendezvous=true`，然后用樱花 frp、花生壳 等任何第三方 TCP 转发让MC服本身有一个能被公网访问的链接地址即可，适用于本身就在用樱花 frp 之类工具的服主，不用自建 frps，最简单的配置

不管哪种方式，打洞成功后游戏流量都不经过第三方。

## 怎么工作

```
游戏服务器                    frps / 端口转发               玩家
  │                               │                        │
  ├─ frpc(xtcp) ── 控制连接 ───────┼────────────── frpc ────┤
  │                               │                        │
  └──────── UDP 直连，不经过第三方 ──────────────────────────┘
```

frpc 以库的形式内嵌在 mod 自带的 agent 里，由 mod 自动启停，玩家无需安装或运行任何额外程序。

## 用法

### 构建

```bash
./mod/build-natives.sh        # 编译 agent 二进制（不含密钥）
cd mod/platform/forge-1.7.10 && ./gradlew build
```

产物在 `build/libs/`。Gradle 需要 Java 21+，编译产物是 Java 8 字节码。

### 服务端

jar 丢进 `<MC服务端根目录>/mods/`，首次启动自动生成 `<MC服务端根目录>/config/netherway.cfg`，改完重启 MC 服务端生效。

**方式一：自建 frps**

\*注意，该方式需要一定的Linux操作基础，如果不会或没有公网IP的机器的话，建议使用方式二。

frps 跑在有公网 IP 的机器上。`config/netherway.cfg` 示例：

```
server {
    B:enabled=true
    S:params <
        server=frps.example.com
        serverPort=7000
        token=1234abcd
        room=testroom
        secret=auto
     >
}
```

- `server`/`serverPort`：你自建的 frps 的地址和端口，不是 MC 端口
- `token`：你自建的 frps 的 auth.token，玩家需要它连 frps
- `secret=auto`：每次重启随机生成房间密钥，旧凭证自动失效，这里默认即可
- `room`：xtcp 房间名，英文，不能包含空格，随意命名即可

frps 那边需要部署 authplugin 防止 token 滥用——玩家手里有 frps 的 token，不拦的话有人能拿它建别的代理开端口。authplugin 是一个独立的 HTTP 服务，跑在 frps 同一台机器上，frps 通过 `httpPlugins` 配置调用它。

**步骤 1**：把 `build-natives.sh` 编译出的 `netherway` 二进制程序拷到 frps 那台机器上，放在哪个目录都行。

**步骤 2**：在 frps 机器的终端里启动 authplugin。签发密钥和静态令牌都是你自己编的随机字符串（任意非空值即可，最好复杂一些），终端运行如下指令：

```bash
# 注意下面这个指令要在frps所在机器的终端里运行
# 签发密钥：自己编一串随机字符串，下面记作 <签发密钥>
# 静态令牌：再编一串，下面记作 <静态令牌>
NETHERWAY_AUTH_KEY=<签发密钥> ./netherway authplugin -static-token <静态令牌> -allow-legacy
```

authplugin（也就是netherway二进制程序）必须常驻运行——它挂了 frps 会拒绝所有登录（fail-closed）。生产环境用 systemd 之类的守护进程管理，开机自启 + 挂了自动拉起。示例 unit 文件：

```ini
# 不要直接复制，根据你自己的实际路径和参数自己去改
# /etc/systemd/system/netherway-auth.service
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

**步骤 3**：frps.toml 加上以下配置，然后重启 frps。`addr` 是 frps 调用 authplugin 的地址，只监听回环（127.0.0.1）即可——frps 和 authplugin 在同一台机器上，不需要外网访问。如果 7200 端口被占用了，这里改端口的同时，步骤 2 的启动命令也要加 `-listen 127.0.0.1:<新端口>`，两边对上：

```toml
[[httpPlugins]]
name = "netherway-auth"
addr = "127.0.0.1:7200"
path = "/handler"
ops = ["Login", "NewProxy"]
```

**步骤 4**：MC 服务端的 `config/netherway.cfg` 里补上同值的两个键（与步骤 2 的值一致）：

```
server {
    S:tokenSigningKey=<签发密钥>
    S:serveAuthToken=<静态令牌>
}
```

两侧启动日志都会打印签发密钥指纹，一致才说明没配岔。

authplugin 拦两件事：`Login` 检查登录、`NewProxy` 只允许服务端的 serve 注册代理，玩家只能建 visitor 不能建 proxy。`-allow-legacy` 是迁移开关，先带着上线，等玩家都登录过一轮再去掉。

**方式二：内嵌会合点 + 端口转发（推荐使用）**

开了 `rendezvous=true` 后 frps 内嵌在 MC 服务端进程里。只需要你随便找一个第三方的端口转发，保证 MC 服务器本身能被外网访问——樱花 frp、花生壳之类的都可以，服主不必自建 frps，也不用单独部署 authplugin。`config/netherway.cfg` 示例：

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

- `token=auto`：内置 frps 的 token 轮换，保持默认即可
- `secret=auto`：内置 frps 的 secret 轮换，保持默认即可
- `room=gtnh`：xtcp 房间名，不包含空格的英文随意命名即可

### 客户端

jar 直接丢进 `<MC客户端根目录>/mods/`，无需配置。

客户端启动时自动向服务器列表里的地址预取凭证并打洞，玩家打开多人游戏就能看到直连条目，玩家选择直连条目直接正常进服就行。打不通也有樱花 frp 之类的中转兜底，不影响游玩。

## 可选功能

### PROXY protocol（`server.proxyProtocol`）

让经 xtcp 隧道进来的连接带上真实来源地址，服务端日志和封禁看到的是玩家真实 IP 而不是 127.0.0.1。只对直连隧道的连接生效。

注意：当前 frp（v0.70）的 xtcp P2P 流尚不携带来源地址（上游 [fatedier/frp#2748](https://github.com/fatedier/frp/issues/2748) 尚未落地），此选项配置后暂不生效，待上游支持后无需改动即可用。

## 已知限制

- 打洞成功率取决于两端 NAT 类型，对称 NAT 大概率失败——但失败时留在原有的中转线路上，不影响游玩。
- Windows 首次运行有防火墙弹窗。
- 因吞吐受连接双方上传带宽限制（家宽一般限制60Mbps），无论用不用直连都不适合大流量传输的场景。

## 代码结构

```
cmd/netherway/       Go agent：serve / tunnel / authplugin
internal/            隧道实现、会合点、令牌校验、STUN 选型
mod/core/            Java 核心层，零 Minecraft 依赖，驱动 agent
mod/platform/        Forge 1.7.10 适配层
```

core 不含任何 Minecraft 类型，换版本或加载器只需重写适配层。
