# Netherway

[简体中文](#解决什么问题) | [English](#english)

让 Minecraft 玩家和服务器之间走 P2P 直连，游戏流量不经过中转节点。类似 HMCL、PCL 启动器自带的联机功能，只不过面向的是有正经服务端的场景。

## 解决什么问题

国内很多 MC 服务器跑在“家里云”上——家里宽带没有公网 IP，服主会通过樱花 frp 之类的第三方端口转发让玩家连进来。作者不喜欢让游戏流量一直经过中转节点，于是做了 Netherway。

Netherway 让这类服务器也能 P2P 直连：玩家和服务器打洞成功后，游戏流量直接走 UDP 互连，第三方只负责最初的信令交换，不再转发游戏数据。

## 使用方法

下载与你的 Minecraft 版本和平台匹配的 Netherway：

- Forge / Fabric 版：客户端和服务端分别放入对应实例的 `mods/` 文件夹。
- Sponge 版：放入服务端的 `mods/` 文件夹。
- Bukkit 版：放入服务端的 `plugins/` 文件夹。

目前仅提供 Forge 1.7.10 版，其他平台版本尚未发布。安装、默认配置、自建 frps 和故障排查请阅读[完整中文文档](docs/zh-CN/README.md)。

---

## English

Netherway establishes a peer-to-peer connection between Minecraft players and servers, so gameplay traffic no longer passes through a relay. It provides an experience similar to the multiplayer features built into launchers such as HMCL and PCL, but is designed for dedicated servers.

### What problem does it solve?

Many Minecraft servers run behind residential connections without a public IP address, so their owners use third-party port-forwarding services to make them reachable. The author does not like keeping gameplay traffic on a relay path, which is why Netherway exists.

Once Netherway successfully performs NAT traversal, gameplay traffic flows directly between the player and the server over UDP. The third-party service is used only for the initial signaling exchange and no longer carries gameplay data.

### Usage

Download the Netherway build matching your Minecraft version and platform:

- Forge / Fabric: place the JAR in the `mods/` folder of both the client and server instances.
- Sponge: place the JAR in the server's `mods/` folder.
- Bukkit: place the JAR in the server's `plugins/` folder.

Only the Forge 1.7.10 build is currently available; builds for the other platforms have not been released yet. See the [complete English documentation](docs/en/README.md) for installation, default configuration, self-hosted frps setup, and troubleshooting.

---

## 项目维护 / Maintainers

商定的版本发布路线、人工检查清单与发布后验证命令见
[发布文档](docs/releasing.md)。

See the [release guide](docs/releasing.md) for the agreed release route, maintainer
checklist, and post-release verification commands.

## 许可证 / License

本项目以 [GNU AGPL-3.0](LICENSE) 授权发布。修改后分发、或以修改版本向网络
用户提供服务（含托管/集成形态），都必须以同一许可证提供对应源码。

This project is licensed under the [GNU AGPL-3.0](LICENSE). If you distribute
a modified version, or let users interact with one over a network (including
hosted or integrated deployments), you must offer its corresponding source
under the same license.
