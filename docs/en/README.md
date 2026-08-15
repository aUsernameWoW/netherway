# Netherway Documentation

[Project home](../../README.md) | [简体中文](../zh-CN/README.md)

## Prerequisites

The server needs an address reachable from the public Internet. There are two supported approaches:

- **Self-hosted frps:** run frps on a machine with a public IP address.
- **Embedded rendezvous with TCP port forwarding (recommended):** use any third-party TCP forwarding service to expose the Minecraft server itself. The server owner does not need to operate a separate frps instance.

With either approach, gameplay traffic no longer passes through the third party after NAT traversal succeeds.

## How it works

```text
Minecraft server              frps / port forward             Player
       │                               │                         │
       ├─ frpc (xtcp) ─ control ───────┼──────────── frpc ───────┤
       │                               │                         │
       └────────── Direct UDP connection; no relay ──────────────┘
```

frpc is embedded as a library in the agent bundled with the mod. The mod starts and stops it automatically, so players do not need to install or run a separate program.

## Installation

Download the Netherway build matching your Minecraft version and platform:

- Forge / Fabric: place the JAR in the `mods/` folder of both the client and server instances.
- Sponge: place the JAR in the server's `mods/` folder.
- Bukkit: place the JAR in the server's `plugins/` folder.

Only the Forge 1.7.10 build is currently available; builds for the other platforms have not been released yet.

On first launch, Netherway creates `<server-root>/config/netherway.cfg`. The generated configuration already uses Method 2 below and normally requires no changes. If necessary, edit it for the desired method and restart the Minecraft server.

## Method 1: Self-hosted frps

> This method requires a public IP address and some Linux administration experience. Use Method 2 if those are unavailable.

Run frps on the machine with the public IP address. Configure the `server` section of `config/netherway.cfg` as follows:

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

- `server` / `serverPort`: the address and port of your frps instance, not the Minecraft port.
- `token`: the `auth.token` configured on your frps instance. Players need it to connect to frps.
- `secret=auto`: generates a new room secret on every server restart, invalidating old credentials.
- `room`: the xtcp room name. Use an ASCII name without spaces.

An authplugin must be deployed next to frps to prevent players who receive the global frps token from creating unrelated proxies or opening ports. The authplugin is a standalone HTTP service called by frps through its `httpPlugins` configuration.

### Step 1: Copy the agent

Copy the `netherway` binary produced by `build-natives.sh` to the machine running frps.

### Step 2: Start the authplugin

Choose two different, sufficiently strong non-empty random strings: a signing key and a static token.

```bash
# Run this command on the machine hosting frps.
NETHERWAY_AUTH_KEY=<signing-key> ./netherway authplugin \
  -static-token <static-token> -allow-legacy
```

The authplugin must remain running. If it stops, frps rejects all logins. In production, manage it with systemd or another process supervisor:

```ini
# Example: /etc/systemd/system/netherway-auth.service
# Adjust paths and parameters for your installation.
[Unit]
Description=Netherway authplugin for frps
After=network.target

[Service]
Environment=NETHERWAY_AUTH_KEY=<signing-key>
ExecStart=/path/to/netherway authplugin -static-token <static-token> -allow-legacy
Restart=always

[Install]
WantedBy=multi-user.target
```

### Step 3: Configure frps

Add the following block to `frps.toml`, then restart frps. Since frps and the authplugin run on the same machine, the plugin only needs to listen on loopback:

```toml
[[httpPlugins]]
name = "netherway-auth"
addr = "127.0.0.1:7200"
path = "/handler"
ops = ["Login", "NewProxy"]
```

If port 7200 is already in use, change it here and add `-listen 127.0.0.1:<new-port>` to the command in Step 2.

### Step 4: Configure the Minecraft server

Add the same values from Step 2 to the Minecraft server's `config/netherway.cfg`:

```text
server {
    S:tokenSigningKey=<signing-key>
    S:serveAuthToken=<static-token>
}
```

Both startup logs print a fingerprint of the signing key. The fingerprints must match.

The authplugin validates `Login` and allows only the server-side serve process to register a proxy through `NewProxy`. Players may create visitors but not proxies. `-allow-legacy` is a migration switch: keep it during initial deployment, then remove it after players have logged in with the new version.

## Method 2: Embedded rendezvous with port forwarding (recommended and default)

With `rendezvous=true`, frps runs inside the Minecraft server process. You only need a third-party TCP forwarding service that makes the Minecraft server reachable from the Internet. There is no separate frps or authplugin to deploy.

Newly generated configurations already use this method. The relevant section is:

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

- `token=auto`: rotates the embedded frps token whenever the server restarts. Keep the default.
- `secret=auto`: rotates the room secret whenever the server restarts. Keep the default.
- `room=minecraft`: the xtcp room name. If changed, use an ASCII name without spaces.

Multiple Netherway servers may run on the same machine. They can share the default room name as long as each server has a distinct and stable public `host:port` endpoint. The client stores credentials and warm-up state separately by server endpoint.

## Client

After installing the Netherway build for the client's platform, no client configuration is required.

At startup, the client fetches credentials from all candidates in the multiplayer server list concurrently. NAT traversal itself is performed serially for each successful service so that simultaneous attempts do not interfere with mappings on the same NAT. Established tunnels can remain active at the same time.

By default, once warm-up succeeds, selecting the original multiplayer entry connects through the local P2P tunnel. Its real address is never replaced in `servers.dat`, so it remains available on the next launch, after a prefetch failure, or after removing the mod. If the player joins before warm-up finishes, Netherway switches the active connection as soon as the tunnel becomes ready. Failures retry with per-service backoff without affecting other services.

Set `client.replaceServerEntries` to `false` to keep separate `[P2P直连] <room> (<endpoint>)` entries visible alongside the original entries.

## Optional features

### PROXY protocol (`server.proxyProtocol`)

This option lets connections arriving through the xtcp tunnel carry their original source address, allowing server logs and bans to see the player's actual IP instead of `127.0.0.1`. It applies only to direct tunnel connections.

The current frp xtcp P2P stream does not yet carry the source address; see upstream issue [fatedier/frp#2748](https://github.com/fatedier/frp/issues/2748). The option therefore has no effect yet, but no further configuration change will be needed when upstream support lands.

## Known limitations

- NAT traversal success depends on both endpoints' NAT types. Symmetric NAT is likely to fail. The original relayed route remains usable when this happens.
- Windows may display a firewall prompt on first launch.
- Throughput is limited by the upload bandwidth of both peers, so direct mode is not intended for large data transfers.

## Building from source

```bash
./mod/build-natives.sh
cd mod/platform/forge-1.7.10 && ./gradlew build
```

The artifact is written to `build/libs/`. Gradle requires Java 21 or newer; the resulting mod uses Java 8 bytecode.
