#!/usr/bin/env bash
# 构建各平台二进制，并把 frps 地址与房间密钥注入进去。
#
# 注入的目的是让玩家拿到的文件双击即用，不需要任何配置文件或命令行参数；
# 同时密钥只存在于构建环境，不进源码仓库。
#
# 用法：
#   TOKEN=xxx SECRET=yyy ./build.sh
#
# 可覆盖的变量见下方默认值。

set -euo pipefail

PKG=github.com/ripplecraft/xtcpinmc/internal/config

SERVER_ADDR="${SERVER_ADDR:-203.0.113.10}"
SERVER_PORT="${SERVER_PORT:-7000}"
ROOM="${ROOM:-gtnh}"
# 逗号分隔的候选列表，与 internal/config 的默认一致：单台 STUN 会间歇性
# 超时，agent 启动前会并行探测并选用当场验证过的一台。
STUN="${STUN:-stun.miwifi.com:3478,stun.easyvoip.com:3478,stun.qq.com:3478}"
MOTD="${MOTD:-涟漪GT:New Horizons}"
# 预拉取凭证（prefetch/authbridge）用；AUTHBRIDGE 留空表示不内置，玩家侧需 -bridge
AUTHSERVER="${AUTHSERVER:-https://skin.example.com/api/yggdrasil}"
AUTHBRIDGE="${AUTHBRIDGE:-}"

: "${TOKEN:?请设置 TOKEN（frps 的 auth.token）}"
: "${SECRET:?请设置 SECRET（房间密钥，两端必须一致）}"

# 每个值都用单引号包起来：MOTD 之类的值含空格，
# 不加引号会被 go 的 ldflags 解析器按空格拆成多个参数。
LDFLAGS="-s -w"
LDFLAGS+=" -X '${PKG}.DefaultServerAddr=${SERVER_ADDR}'"
LDFLAGS+=" -X '${PKG}.DefaultServerPort=${SERVER_PORT}'"
LDFLAGS+=" -X '${PKG}.DefaultToken=${TOKEN}'"
LDFLAGS+=" -X '${PKG}.DefaultSTUNServer=${STUN}'"
LDFLAGS+=" -X '${PKG}.DefaultRoom=${ROOM}'"
LDFLAGS+=" -X '${PKG}.DefaultSecretKey=${SECRET}'"
LDFLAGS+=" -X '${PKG}.DefaultMOTD=${MOTD}'"
LDFLAGS+=" -X '${PKG}.DefaultAuthServer=${AUTHSERVER}'"
LDFLAGS+=" -X '${PKG}.DefaultAuthBridge=${AUTHBRIDGE}'"

mkdir -p bin

build() {
  local goos="$1" goarch="$2" out="$3"
  echo "构建 ${goos}/${goarch} -> bin/${out}"
  GOOS="$goos" GOARCH="$goarch" CGO_ENABLED=0 \
    go build -trimpath -ldflags "$LDFLAGS" -o "bin/${out}" ./cmd/xtcpinmc
}

# 玩家侧：Windows 是主力，其次是 macOS
build windows amd64 xtcpinmc-windows-amd64.exe
build windows arm64 xtcpinmc-windows-arm64.exe
build darwin  arm64 xtcpinmc-macos-arm64
build darwin  amd64 xtcpinmc-macos-amd64
# 服务器侧
build linux   amd64 xtcpinmc-linux-amd64

echo
echo "完成。产物："
ls -lh bin/
