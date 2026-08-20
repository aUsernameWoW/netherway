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

# VARIANT picks which backends get compiled in (build tags in cmd/netherway):
#   all  (default) — every backend, the regular release binary
#   frp            — frp-xtcp only  (-tags nogonc)
#   gonc           — gonc-p2p only  (-tags nofrp), drops frp entirely
VARIANT="${VARIANT:-all}"
case "$VARIANT" in
  all)  GOTAGS="" ;;
  frp)  GOTAGS="nogonc" ;;
  gonc) GOTAGS="nofrp" ;;
  *) echo "unknown VARIANT '$VARIANT' (expected all/frp/gonc)" >&2; exit 2 ;;
esac
echo "backend variant: $VARIANT${GOTAGS:+ (-tags $GOTAGS)}"

OUT=mod/build/natives
# 整目录会被 processResources 原样打进 jar，先清空，
# 否则上一次构建的多余平台会跟着混进去（平台清单缩减时尤其如此）
rm -rf "$OUT"

build() {
  local goos="$1" goarch="$2" os="$3" suffix="$4"
  local dir="${OUT}/${os}-${goarch}"
  mkdir -p "$dir"
  echo "构建 ${goos}/${goarch} -> ${dir}/netherway${suffix}"
  GOOS="$goos" GOARCH="$goarch" CGO_ENABLED=0 \
    go build -trimpath ${GOTAGS:+-tags "$GOTAGS"} -ldflags "-s -w" \
    -o "${dir}/netherway${suffix}" ./cmd/netherway
}

# 只打玩家侧主力平台：每个二进制 20MB 上下，全平台打包会把 jar 撑得太大。
# Windows ARM64 靠系统自带的 x64 转译层兜底（BinaryStore 会回落到
# windows-amd64 的资源），Intel Mac 与 Linux ARM64 暂不支持——
# 其余平台如何补充（按需下载等）另行设计，先不占 jar 体积。
build windows amd64 windows .exe
build darwin  arm64 macos ''
build linux   amd64 linux ''

echo
echo "完成。产物（不含任何密钥，可随 jar 分发）："
ls -lR "$OUT"
