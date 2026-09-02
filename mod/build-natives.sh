#!/usr/bin/env bash
# Build the agent binaries that get packed into the mod jars.
#
# The agent carries no build-time secrets at all: every parameter it needs
# (session key, brokers, STUN list) arrives at runtime through -O from the
# credentials the server hands out, so the binaries are safe to ship to
# every player as-is. Do not add -ldflags -X injection here — a jar goes to
# everyone, and there is nothing that should be baked in anyway.
#
# Output lands in mod/build/natives/<os>-<arch>/netherway[.exe], the layout
# core's Platform.resourcePath() expects; gradle's processResources copies
# the directory verbatim into the jar's natives/.

set -euo pipefail
cd "$(dirname "$0")/.."

OUT=mod/build/natives
# processResources packs the whole directory, so clear it first: stale
# platforms from a previous build would ride along otherwise (especially
# when the platform list shrinks).
rm -rf "$OUT"

build() {
  local goos="$1" goarch="$2" os="$3" suffix="$4"
  local dir="${OUT}/${os}-${goarch}"
  mkdir -p "$dir"
  echo "building ${goos}/${goarch} -> ${dir}/netherway${suffix}"
  GOOS="$goos" GOARCH="$goarch" CGO_ENABLED=0 \
    go build -trimpath -ldflags "-s -w" \
    -o "${dir}/netherway${suffix}" ./cmd/netherway
}

# Player-side mainstream platforms only: every binary is several MB, and a
# full platform matrix would bloat the jar. Windows ARM64 falls back to the
# OS's x64 translation layer (BinaryStore resolves to the windows-amd64
# resource); Intel Mac and Linux ARM64 are not shipped yet — how to supply
# the remaining platforms (on-demand download, etc.) is a separate design,
# not jar bytes.
build windows amd64 windows .exe
build darwin  arm64 macos ''
build linux   amd64 linux ''

echo
echo "done. Artifacts (no secrets inside, safe to ship with the jar):"
ls -lR "$OUT"
