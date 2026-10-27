#!/usr/bin/env bash
set -euo pipefail

# Builds pinned llama.cpp b11588 static libraries (libllama, libggml*, libmtmd) for iOS Simulator (arm64),
# iOS Device (arm64, Metal GPU), and macOS host (arm64, Metal GPU) from native/runtime/build/llama.cpp.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LLAMA_SRC="${REPO_ROOT}/native/runtime/build/llama.cpp"

if [ ! -f "${LLAMA_SRC}/CMakeLists.txt" ]; then
  bash "${REPO_ROOT}/native/runtime/bootstrap.sh"
fi

TARGET="${1:-sim}" # sim | ios | macos
OUT_DIR="${REPO_ROOT}/ios/build/llama-${TARGET}"

if [ -f "${OUT_DIR}/.built_ok" ]; then
  echo "[llama-${TARGET}] Already built at ${OUT_DIR}"
  exit 0
fi

mkdir -p "${OUT_DIR}"

COMMON_FLAGS=(
  -DBUILD_SHARED_LIBS=OFF
  -DLLAMA_BUILD_NUMBER=11588
  -DLLAMA_BUILD_COMMIT="7f51953"
  -DLLAMA_BUILD_MTMD=ON
  -DLLAMA_BUILD_COMMON=OFF
  -DLLAMA_BUILD_TOOLS=OFF
  -DLLAMA_BUILD_TESTS=OFF
  -DLLAMA_BUILD_EXAMPLES=OFF
  -DLLAMA_BUILD_SERVER=OFF
  -DLLAMA_BUILD_APP=OFF
  -DLLAMA_OPENSSL=OFF
  -DMTMD_VIDEO=OFF
  -DGGML_OPENMP=OFF
  -DGGML_NATIVE=OFF
  -DCMAKE_BUILD_TYPE=Release
)

if [ "${TARGET}" = "sim" ]; then
  SDK_PATH="$(xcrun --sdk iphonesimulator --show-sdk-path)"
  cmake -S "${LLAMA_SRC}" -B "${OUT_DIR}" -G Ninja \
    "${COMMON_FLAGS[@]}" \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_SYSROOT="${SDK_PATH}" \
    -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=17.0 \
    -DGGML_METAL=OFF \
    -DGGML_ACCELERATE=ON \
    -DGGML_BLAS=OFF
elif [ "${TARGET}" = "ios" ]; then
  SDK_PATH="$(xcrun --sdk iphoneos --show-sdk-path)"
  cmake -S "${LLAMA_SRC}" -B "${OUT_DIR}" -G Ninja \
    "${COMMON_FLAGS[@]}" \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_SYSROOT="${SDK_PATH}" \
    -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=17.0 \
    -DGGML_METAL=ON \
    -DGGML_METAL_EMBED_LIBRARY=ON \
    -DGGML_ACCELERATE=ON \
    -DGGML_BLAS=OFF
elif [ "${TARGET}" = "macos" ]; then
  cmake -S "${LLAMA_SRC}" -B "${OUT_DIR}" -G Ninja \
    "${COMMON_FLAGS[@]}" \
    -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DGGML_METAL=ON \
    -DGGML_METAL_EMBED_LIBRARY=ON \
    -DGGML_ACCELERATE=ON
else
  echo "Unknown target: ${TARGET}" >&2
  exit 1
fi

cmake --build "${OUT_DIR}" --target llama mtmd -j8

# Merge all generated static archives into one clean libmemeget_llama.a so Xcode links without symbol order games
ARCHIVES=()
while IFS= read -r -d '' a; do
  ARCHIVES+=("$a")
done < <(find "${OUT_DIR}" -name "*.a" -print0)

libtool -static -o "${OUT_DIR}/libmemeget_llama.a" "${ARCHIVES[@]}"
touch "${OUT_DIR}/.built_ok"
echo "[llama-${TARGET}] Built ${OUT_DIR}/libmemeget_llama.a"
