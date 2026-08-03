#!/usr/bin/env bash
# Fixtures for RetainedRangeCompositionInstrumentedTest.
#
# Deliberately small (240p, 3 s): the composition test exports five times on an
# emulator, and the properties under test — output duration, frame continuity
# across a seam, preserved orientation, title-card insertion — do not need
# resolution, they need known geometry and exact timing.
set -euo pipefail

FFMPEG_BIN="${FFMPEG_BIN:-ffmpeg}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ASSET_DIR="${SCRIPT_DIR}/../assets"
mkdir -p "${ASSET_DIR}"

# Plain landscape source: motion in every frame so a dropped or duplicated
# segment is visible in the decoded stream, plus a real AAC track.
"${FFMPEG_BIN}" -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=320x240:rate=30:duration=3" \
  -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=3" \
  -map 0:v:0 -map 1:a:0 \
  -c:v libx264 -preset veryfast -pix_fmt yuv420p -profile:v high -level 4.1 \
  -g 15 -keyint_min 15 -sc_threshold 0 -b:v 900k \
  -c:a aac -b:a 96k -ar 48000 -ac 2 \
  -movflags +faststart -shortest "${ASSET_DIR}/composition_landscape_3s_240p.mp4"

# Rotated source. The content is split along the CODED width — left half red,
# right half blue — and the container asks for a quarter turn clockwise, so a
# correctly oriented output frame is 240x320 with red on TOP and blue below.
# A segment that lost the rotation would show the split left-to-right instead,
# which is what the seam assertions look for.
#
# Two passes on purpose: ffmpeg 7+ dropped the `rotate` stream tag, and
# `-display_rotation` is an INPUT option, so the display matrix has to be
# stamped on while remuxing rather than while encoding.
ROTATED_RAW="$(mktemp -t composition_rotated_raw).mp4"
trap 'rm -f "${ROTATED_RAW}"' EXIT

"${FFMPEG_BIN}" -hide_banner -loglevel error -y \
  -f lavfi -i "color=c=red:s=160x240:r=30:d=3" \
  -f lavfi -i "color=c=blue:s=160x240:r=30:d=3" \
  -f lavfi -i "sine=frequency=660:sample_rate=48000:duration=3" \
  -filter_complex "[0:v][1:v]hstack=inputs=2,format=yuv420p[v]" \
  -map "[v]" -map 2:a:0 \
  -c:v libx264 -preset veryfast -profile:v high -level 4.1 \
  -g 15 -keyint_min 15 -sc_threshold 0 -b:v 600k \
  -c:a aac -b:a 96k -ar 48000 -ac 2 \
  -movflags +faststart -shortest "${ROTATED_RAW}"

"${FFMPEG_BIN}" -hide_banner -loglevel error -y \
  -noautorotate -display_rotation:v:0 -90 -i "${ROTATED_RAW}" \
  -c copy -movflags +faststart "${ASSET_DIR}/composition_rotated_3s_240p.mp4"

# Title card: solid green with a black frame, 3:2 so scaling it into a portrait
# composition letterboxes visibly. Its aspect deliberately differs from both
# sources so a card that dictated the output size would be caught.
"${FFMPEG_BIN}" -hide_banner -loglevel error -y \
  -f lavfi -i "color=c=black:s=720x480" \
  -vf "drawbox=x=24:y=24:w=672:h=432:color=lime@1.0:t=fill" \
  -frames:v 1 "${ASSET_DIR}/composition_title_card_720x480.png"
