cmake_minimum_required(VERSION 3.22.1)
project(immich_meme_extractor LANGUAGES C CXX OBJCXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_OBJCXX_STANDARD 20)
set(CMAKE_OBJCXX_STANDARD_REQUIRED ON)
set(CMAKE_OSX_ARCHITECTURES arm64)

set(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../..")

add_executable(immich_gate_worker
  immich_gate_worker.mm
  "${REPO_ROOT}/native/core/MemeGateCore.cpp"
  "${REPO_ROOT}/native/core/KnowledgeCore.cpp"
  "${REPO_ROOT}/native/core/LlamaDecisionRunner.cpp"
  "${REPO_ROOT}/native/core/ResidentSearchCore.cpp"
  "${REPO_ROOT}/native/app/src/main/cpp/LlamaVisionRunner.cpp"
  "${REPO_ROOT}/native/app/src/main/cpp/ProjectorConversion.cpp"
)

target_include_directories(immich_gate_worker PRIVATE
  "${REPO_ROOT}/native/core"
  "${REPO_ROOT}/native/app/src/main/cpp"
  "${REPO_ROOT}/native/runtime/build/llama.cpp/include"
  "${REPO_ROOT}/native/runtime/build/llama.cpp/ggml/include"
  "${REPO_ROOT}/native/runtime/build/llama.cpp/tools/mtmd"
)

target_compile_options(immich_gate_worker PRIVATE -O3)

target_link_libraries(immich_gate_worker PRIVATE
  "${REPO_ROOT}/ios/build/llama-macos/libmemeget_llama.a"
  "-framework Foundation"
  "-framework Vision"
  "-framework ImageIO"
  "-framework CoreGraphics"
  "-framework Metal"
  "-framework MetalKit"
  "-framework Accelerate"
  z
)
