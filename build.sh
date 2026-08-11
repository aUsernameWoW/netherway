#!/usr/bin/env bash
# 构建各平台二进制，并把 frps 地址与房间密钥注入进去。
#
# 注入的目的是让玩家拿到的文件双击即用，不需要任何配置文件或命令行参数；
# 同时密钥只存在于构建环境，不进源码仓库。
#
# 用法：
#   TOKEN=xxx SECRET=yyy ./build.sh
#
# 真实部署参数（frps 地址与可选密钥等）放 build.env（已 gitignore，
# 模板见 build.env.example），环境变量可临时覆盖其中任何一项。
# 版本库里不出现任何真实地址——这是转公开仓库的前提之一。
#
# ONLY=<goos>/<goarch> 只构建单个目标（CI 冒烟用，如 ONLY=linux/amd64）。

set -euo pipefail

# 先读 build.env 再看环境变量：环境变量优先（已设置的不被覆盖）
if [[ -f "$(dirname "$0")/build.env" ]]; then
  while IFS='=' read -r k v; do
    if [[ "$k" =~ ^[A-Z_]+$ && -z "${!k:-}" ]]; then
      export "$k=$v"
    fi
  done < "$(dirname "$0")/build.env"
fi

PKG=github.com/aUsernameWoW/netherway/internal/config

: "${SERVER_ADDR:?请设置 SERVER_ADDR（frps 公网地址），经环境变量或 build.env}"
SERVER_PORT="${SERVER_PORT:-7000}"
ROOM="${ROOM:-gtnh}"
# 逗号分隔的候选列表，与 internal/config 的默认一致：单台 STUN 会间歇性
# 超时，agent 启动前会并行探测并选用当场验证过的一台。
STUN="${STUN:-stun.miwifi.com:3478,stun.easyvoip.com:3478,stun.qq.com:3478}"

: "${TOKEN:?请设置 TOKEN（frps 的 auth.token）}"
: "${SECRET:?请设置 SECRET（房间密钥，两端必须一致）}"

# 每个值都用单引号包起来：值可能含空格，
# 不加引号会被 go 的 ldflags 解析器按空格拆成多个参数。
LDFLAGS="-s -w"
LDFLAGS+=" -X '${PKG}.DefaultServerAddr=${SERVER_ADDR}'"
LDFLAGS+=" -X '${PKG}.DefaultServerPort=${SERVER_PORT}'"
LDFLAGS+=" -X '${PKG}.DefaultToken=${TOKEN}'"
LDFLAGS+=" -X '${PKG}.DefaultSTUNServer=${STUN}'"
LDFLAGS+=" -X '${PKG}.DefaultRoom=${ROOM}'"
LDFLAGS+=" -X '${PKG}.DefaultSecretKey=${SECRET}'"

mkdir -p bin

build() {
  local goos="$1" goarch="$2" out="$3"
  if [[ -n "${ONLY:-}" && "$ONLY" != "${goos}/${goarch}" ]]; then
    return 0
  fi
  echo "构建 ${goos}/${goarch} -> bin/${out}"
  GOOS="$goos" GOARCH="$goarch" CGO_ENABLED=0 \
    go build -trimpath -ldflags "$LDFLAGS" -o "bin/${out}" ./cmd/netherway
}

# 玩家侧：Windows 是主力，其次是 macOS
build windows amd64 netherway-windows-amd64.exe
build windows arm64 netherway-windows-arm64.exe
build darwin  arm64 netherway-macos-arm64
build darwin  amd64 netherway-macos-amd64
# 服务器侧
build linux   amd64 netherway-linux-amd64

echo
echo "完成。产物："
ls -lh bin/
