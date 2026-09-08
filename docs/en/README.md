# Netherway Documentation

[Project home](../../README.md) | [简体中文](../zh-CN/README.md)

## Prerequisites

The server only needs a Minecraft endpoint reachable from the public Internet:
a third-party port-forwarding service, a NAT rule on a cloud host, an nginx
stream block — anything that forwards TCP to the Minecraft port works.
Netherway does not require the forwarder to support any particular protocol,
and the server owner does not need to run any additional service.

After NAT traversal succeeds, gameplay traffic no longer passes through the
third party. When it fails, players keep playing over the existing forwarded
route.

## How it works

```text
Minecraft server                 Port forward                 Player
       │                              │                          │
       ├─ embedded signaling ◀─ via MC port ─┼─── signaling (before punch) ──┤
       │                              │                          │
       └──────── Direct P2P connection; no relay ─────────────────┘
```

The mod bundles an agent (the NAT-traversal and tunnel program) and starts
and stops it automatically, so players do not need to install or run a
separate program. The server-side mod embeds a signaling point inside the
Minecraft server process that listens on loopback only; a player's signaling
connection arrives at the Minecraft port through the existing port forward,
where the mod recognizes it and relays it to the signaling point. Once both
sides have exchanged address information they punch through, and gameplay
traffic then flows directly between them. No public third-party server is
involved at any stage.

## Installation

Download the Netherway build matching your Minecraft version and platform from [GitHub Releases](https://github.com/aUsernameWoW/netherway/releases):

- Forge / Fabric: place the JAR in the `mods/` folder of both the client and server instances.
- Bukkit (Spigot/Paper 1.13+): place the JAR in the server's `plugins/` folder.

On first launch, Netherway creates `<server-root>/config/netherway.cfg`. The
generated configuration works out of the box and normally requires no
changes. If necessary, edit it as described below and restart the Minecraft
server.

## Server configuration

The relevant part of a newly generated configuration is:

```text
server {
    B:enabled=true
    B:runAgent=true
    B:rendezvous=true
    S:backend=gonc-p2p
    S:params <
        sessionKey=auto
        room=minecraft
     >
}
```

- `rendezvous=true`: the signaling point is embedded in the Minecraft server
  process and player signaling enters through the Minecraft port. Keep the
  default.
- `sessionKey=auto`: rotates the session key whenever the server restarts,
  invalidating old credentials. Clients fetch fresh credentials on their own;
  nobody has to do anything. Keep the default.
- `room=minecraft`: the room name, used only for display and to tell services
  apart. If changed, use an ASCII name without spaces.

Multiple Netherway servers may run on the same machine. They can share the
default room name as long as each server has a distinct and stable public
`host:port` endpoint. The client stores credentials and warm-up state
separately by server endpoint.

A note on cfg syntax: keys carry a type prefix (`B:` boolean, `S:` string,
`I:` integer), and lists start with `S:params <` and end with `>` on its own
line. The configuration is read only at startup, so changes need a restart.
A syntax error does not crash the server: the mod logs it and disables
server-side direct connections for that launch; fix the file and restart.

## Optional features

### External signaling brokers (`brokers` in `server.params`)

By default the signaling point is embedded in the server process and reached
only through the Minecraft port; this is the recommended shape. If you would
rather use a public or self-hosted MQTT broker, add
`brokers=tcp://broker.example.com:1883` (comma-separate several) to
`server.params` and set `rendezvous` to `false`. Credentials then carry the
broker address and players signal to it directly instead of through the
Minecraft port.

### Invite codes (servers with no reachable entry at all)

When the server has no Minecraft entry reachable from the internet (no port
forward, no rented tunnel), credentials cannot reach players through login
or preauth. Use public (or self-hosted) signaling brokers instead and hand
players the credential folded into an **invite code**:

1. Add `brokers=tcp://broker.example.com:1883` (comma-separate several) to
   `server.params` and set `rendezvous` to `false`.
2. Start the server; the log prints a line `Invite code ...: nw1-…`.
3. Players open Multiplayer → Add Server and **paste the code as the server
   address**, with any name. The mod starts punching for it right away; once
   the tunnel is up, clicking the entry connects directly and the listed
   latency is the direct one. Until then the entry shows as unreachable.

The invite code is the session key: whoever holds it can open a tunnel to the
Minecraft port, and admission stays with the server's whitelist and online
mode. Under `sessionKey=auto` the key rotates on every restart and so does
the code; for a long-lived code put a fixed `sessionKey` in `server.params`.
The code has to fit the server-address field (128 characters): the default
parameters take about 50, two brokers still fit, and the startup log says so
when the list is too long. Deleting the entry closes its tunnel; invite
credentials are never written to the client's credential cache, the list
entry itself is the source.

### PROXY protocol (`server.proxyProtocol`)

Set it to `v2` (or `v1`) and connections arriving through the direct tunnel
carry the player's real source address, so server logs and bans see the
actual IP instead of `127.0.0.1`. It applies only to direct tunnel
connections; connections arriving through the port forward are unaffected.

### Punch timeout (`server.punchTimeoutSeconds`)

The server may suggest a hole-punching timeout (in seconds) to clients; `0`
lets each client use its own setting (`client.punchTimeoutSeconds`, default
15). Raise it on networks that regularly need a second punching round.

### Preauth (`server.preauth`)

Enabled by default: clients can request credentials from the Minecraft port
at startup and finish punching before joining. When disabled, players only
receive credentials after joining, and the direct connection takes effect
after one reconnect following login.

## Client

After installing the Netherway build for the client's platform, no client configuration is required.

At startup, the client fetches credentials from all candidates in the multiplayer server list concurrently. NAT traversal itself is performed serially for each successful service so that simultaneous attempts do not interfere with mappings on the same NAT. Established tunnels can remain active at the same time.

By default, once warm-up succeeds, selecting the original multiplayer entry connects through the local P2P tunnel. Its real address is never replaced in `servers.dat`, so it remains available on the next launch, after a prefetch failure, or after removing the mod. If the player joins before warm-up finishes, Netherway switches the active connection as soon as the tunnel becomes ready. Failures retry with per-service backoff without affecting other services.

Set `client.replaceServerEntries` to `false` to keep separate `[P2P直连] <room> (<endpoint>)` entries visible alongside the original entries.

A server-list entry starting with `nw1-` is treated as an invite code (see above); nothing else needs to be configured.

## Known limitations

- NAT traversal success depends on both endpoints' NAT types. A symmetric NAT
  on one side lowers the success rate noticeably; symmetric on both sides
  essentially fails. The original forwarded route remains usable when this
  happens.
- There is deliberately no relay fallback: when punching fails, the player
  stays on the original route rather than being relayed through a third
  party a second time.
- Both the server and the client need the mod. Clients without it join as
  usual over the original route.
- Windows may display a firewall prompt on first launch.
- Throughput is limited by the upload bandwidth of both peers, so direct mode is not intended for large data transfers.

## Building from source

```bash
go build ./... && go test ./...          # agent (Go 1.26)
./mod/build-natives.sh                    # cross-platform agent binaries bundled into the JAR
cd mod/platform/forge-1.7.10 && ./gradlew build
```

The artifact is written to `build/libs/`. Gradle requires Java 21 or newer;
the resulting mod uses Java 8 bytecode. The build needs no secrets or
deployment parameters: credentials are generated and handed out by the server
at runtime.
