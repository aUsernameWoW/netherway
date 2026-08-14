#!/usr/bin/env bash
# 构建打进 mod jar 的 agent 二进制。
#
# 与根目录 build.sh 的关键区别：这里不注入 TOKEN/SECRET。
# mod jar 会分发给所有玩家，里面绝不能有任何密钥——mod 场景下
# agent 的全部参数都由服务端下发的凭证经 -O 传入，
# 构建期注入的默认值根本用不上。
#
# 产物落在 mod/build/natives/<os>-<arch>/netherway[.exe]，
# 与 core 里 Platform.resourcePath() 的布局一致，
# gradle 的 processResources 会原样打进 jar 的 natives/。

set -euo pipefail
cd "$(dirname "$0")/.."

OUT=mod/build/natives

build() {
  local goos="$1" goarch="$2" os="$3" suffix="$4"
  local dir="${OUT}/${os}-${goarch}"
  mkdir -p "$dir"
  echo "构建 ${goos}/${goarch} -> ${dir}/netherway${suffix}"
  GOOS="$goos" GOARCH="$goarch" CGO_ENABLED=0 \
    go build -trimpath -ldflags "-s -w" -o "${dir}/netherway${suffix}" ./cmd/netherway
}

# 玩家侧：Windows 是主力，其次是 macOS
build windows amd64 windows .exe
build windows arm64 windows .exe
build darwin  arm64 macos ''
build darwin  amd64 macos ''
# 玩家也可能在 Linux 上玩
build linux   amd64 linux ''
build linux   arm64 linux ''

echo
echo "完成。产物（不含任何密钥，可随 jar 分发）："
ls -lR "$OUT"
