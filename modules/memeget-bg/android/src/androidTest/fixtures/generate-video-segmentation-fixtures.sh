#!/usr/bin/env bash
set -euo pipefail

FFMPEG_BIN="${FFMPEG_BIN:-ffmpeg}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ASSET_DIR="${SCRIPT_DIR}/../assets"
SOURCE="${SCRIPT_DIR}/opencv-vtest-4.12.0.avi"
SOURCE_URL="https://raw.githubusercontent.com/opencv/opencv/4.12.0/samples/data/vtest.avi"
SOURCE_SHA256="45cddc9490be69345cbdab64ca583be65987e864ca408038e648db99e10516cf"

if ! "${FFMPEG_BIN}" -version | grep -q '^ffmpeg version 8\.1\.2-tessus '; then
  echo "Expected the recorded fixture encoder ffmpeg 8.1.2-tessus" >&2
  exit 1
fi

curl -fsSL "${SOURCE_URL}" -o "${SOURCE}"
echo "${SOURCE_SHA256}  ${SOURCE}" | shasum -a 256 -c -

COMMON=(
  -an -c:v libx264 -preset veryfast -crf 18 -pix_fmt yuv420p
  -g 30 -keyint_min 30 -sc_threshold 0 -movflags +faststart
)

"${FFMPEG_BIN}" -hide_banner -loglevel error -y \
  -ss 40 -t 7 -i "${SOURCE}" \
  -vf "crop=180:280:265:150,setpts=10/7*PTS,scale=462:720:flags=lanczos,pad=1280:720:(ow-iw)/2:0:color=0x203040,fps=30" \
  "${COMMON[@]}" "${ASSET_DIR}/video_segmentation_one_person_10s_720p.mp4"

"${FFMPEG_BIN}" -hide_banner -loglevel error -y \
  -ss 65 -t 10 -i "${SOURCE}" \
  -vf "scale=960:720:flags=lanczos,pad=1280:720:(ow-iw)/2:0:color=0x203040,fps=30" \
  "${COMMON[@]}" "${ASSET_DIR}/video_segmentation_two_crossing_10s_720p.mp4"

"${FFMPEG_BIN}" -hide_banner -loglevel error -y \
  -ss 20 -t 40 -i "${SOURCE}" \
  -vf "setpts=0.25*PTS,scale=960:720:flags=lanczos,pad=1280:720:(ow-iw)/2:0:color=0x203040,fps=30" \
  -t 10 "${COMMON[@]}" "${ASSET_DIR}/video_segmentation_fast_motion_10s_720p.mp4"

EXPECTED=(
  "4cae38ceb3c6ff8faab4026b5ff3cb8907ca381e4b44bec47815471e6446453f  ${ASSET_DIR}/video_segmentation_one_person_10s_720p.mp4"
  "12c6173e83dc4ede1b4c016b16612df7b16932243c09cbe1d681a4c1735166d3  ${ASSET_DIR}/video_segmentation_two_crossing_10s_720p.mp4"
  "f4ff899061b78f5fb204daf68ffe25289d1eba84528ed424e83255995d8b0f67  ${ASSET_DIR}/video_segmentation_fast_motion_10s_720p.mp4"
)
printf '%s\n' "${EXPECTED[@]}" | shasum -a 256 -c -
rm -f "${SOURCE}"
