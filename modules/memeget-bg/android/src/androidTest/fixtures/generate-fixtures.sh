#!/usr/bin/env bash
set -euo pipefail

FFMPEG_BIN="${FFMPEG_BIN:-ffmpeg}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ASSET_DIR="${SCRIPT_DIR}/../assets"
mkdir -p "${ASSET_DIR}"

make_fixture() {
  local width="$1"
  local height="$2"
  local duration="$3"
  local output="${ASSET_DIR}/synthetic_${duration}s_${height}p.mp4"

  "${FFMPEG_BIN}" -hide_banner -loglevel error -y \
    -f lavfi -i "testsrc2=size=${width}x${height}:rate=30:duration=${duration}" \
    -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=${duration}" \
    -map 0:v:0 -map 1:a:0 \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p -profile:v high -level 4.1 \
    -g 30 -keyint_min 30 -sc_threshold 0 -b:v 2500k \
    -c:a aac -b:a 128k -ar 48000 -ac 2 \
    -movflags +faststart -shortest "${output}"
}

make_fixture 1280 720 5
make_fixture 1280 720 15
make_fixture 1920 1080 5
make_fixture 1920 1080 15
