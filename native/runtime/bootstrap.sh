#!/usr/bin/env bash
set -euo pipefail

# Deterministic runtime bootstrap: checksum-gated upstream archives extracted into an ignored build cache.
#   - react-native-executorch 0.9.2: ExecuTorch runtime, OpenCV and model C++ helpers.
#   - llama.cpp b11588 (commit 7f51953d7d2f359939a763549e93d51f0347e569): MiniCPM-V 4.7 vision captions.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNTIME_BUILD_DIR="${SCRIPT_DIR}/build"
CACHE_DIR="${RUNTIME_BUILD_DIR}/cache"

checksum() {
  local algorithm="$1" file="$2"
  if command -v "sha${algorithm}sum" >/dev/null 2>&1; then
    "sha${algorithm}sum" "${file}" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a "${algorithm}" "${file}" | awk '{print $1}'
  else
    echo "[inference-bootstrap] ERROR: no sha${algorithm} tool (sha${algorithm}sum or shasum) found" >&2
    exit 1
  fi
}

# fetch_and_extract NAME URL ALGORITHM EXPECTED DEST
fetch_and_extract() {
  local name="$1" url="$2" algorithm="$3" expected="$4" dest="$5"
  local stamp="${dest}/.extracted"
  local archive="${CACHE_DIR}/${name}"

  if [ -f "${stamp}" ] && [ "$(cat "${stamp}")" = "${expected}" ]; then
    return 0
  fi

  mkdir -p "${CACHE_DIR}"
  if [ ! -f "${archive}" ]; then
    echo "[inference-bootstrap] Downloading ${name}..."
    curl -fSL --retry 3 "${url}" -o "${archive}.part"
    mv "${archive}.part" "${archive}"
  fi

  local actual
  actual="$(checksum "${algorithm}" "${archive}")"
  if [ "${actual}" != "${expected}" ]; then
    echo "[inference-bootstrap] ERROR: SHA-${algorithm} mismatch for ${archive}!" >&2
    echo "[inference-bootstrap] Expected: ${expected}" >&2
    echo "[inference-bootstrap] Actual:   ${actual}" >&2
    exit 1
  fi

  echo "[inference-bootstrap] Checksum verified. Extracting ${name} to ${dest}..."
  rm -rf "${dest}"
  mkdir -p "${dest}"
  # Both archives wrap their contents in a single top-level directory.
  tar -xzf "${archive}" -C "${dest}" --strip-components=1
  echo "${expected}" > "${stamp}"
}

fetch_and_extract \
  "react-native-executorch-0.9.2.tgz" \
  "https://registry.npmjs.org/react-native-executorch/-/react-native-executorch-0.9.2.tgz" \
  512 \
  "a59d826d53e5cdedf8d862e273850c4e7303ba09604e31c171a28b0a31935c64d2b6bb1caad3e9b68673c7b6eb809b70c2eec81d1cf7885d269366bbbec28b42" \
  "${RUNTIME_BUILD_DIR}/executorch"

fetch_and_extract \
  "llama.cpp-b11588.tar.gz" \
  "https://github.com/ggml-org/llama.cpp/archive/refs/tags/b11588.tar.gz" \
  256 \
  "37588f85e252f1b8753dcb200fba7198dc93e4f1b1615f459abffbe5662ac85d" \
  "${RUNTIME_BUILD_DIR}/llama.cpp"

echo "[inference-bootstrap] Runtime dependencies ready."
