cmake_minimum_required(VERSION 3.22.1)
project(memeget_inference LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Enforce 16 KiB link alignment (Android 15+ requirement)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

set(RUNTIME_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../runtime")
set(EXTRACTED_STAMP "${RUNTIME_DIR}/build/executorch/.extracted")
set(LLAMA_STAMP "${RUNTIME_DIR}/build/llama.cpp/.extracted")

# Run runtime bootstrap if extracted dependencies are missing
if(NOT EXISTS "${EXTRACTED_STAMP}" OR NOT EXISTS "${LLAMA_STAMP}")
  message(STATUS "Runtime dependencies missing. Invoking bootstrap.sh...")
  execute_process(
    COMMAND bash "${RUNTIME_DIR}/bootstrap.sh"
    WORKING_DIRECTORY "${RUNTIME_DIR}"
    RESULT_VARIABLE BOOTSTRAP_RES
  )
  if(NOT BOOTSTRAP_RES EQUAL 0)
    message(FATAL_ERROR "Runtime bootstrap failed with exit code: ${BOOTSTRAP_RES}")
  endif()
endif()

set(ET_DIR "${RUNTIME_DIR}/build/executorch")
set(ET_INCLUDE_DIR "${ET_DIR}/third-party/include")
set(ET_COMMON_DIR "${ET_DIR}/common")
set(ET_LIBS_DIR "${ET_DIR}/third-party/android/libs")

if(NOT EXISTS "${ET_LIBS_DIR}/executorch/${ANDROID_ABI}/libexecutorch.so")
  message(FATAL_ERROR "libexecutorch.so not found for ABI ${ANDROID_ABI} at ${ET_LIBS_DIR}/executorch/${ANDROID_ABI}")
endif()

include_directories(
  ${ET_INCLUDE_DIR}
  ${ET_INCLUDE_DIR}/executorch/extension/llm/tokenizers/include
  ${ET_COMMON_DIR}
  ${CMAKE_CURRENT_SOURCE_DIR}
)

# Imported shared libraries from react-native-executorch
# The upstream filename differs from its ELF SONAME. Package the SONAME, not an alias.
set(EXECUTORCH_RUNTIME_SO "${CMAKE_CURRENT_BINARY_DIR}/libexecutorch_jni.so")
configure_file("${ET_LIBS_DIR}/executorch/${ANDROID_ABI}/libexecutorch.so" "${EXECUTORCH_RUNTIME_SO}" COPYONLY)
add_library(executorch_so SHARED IMPORTED)
set_target_properties(executorch_so PROPERTIES IMPORTED_LOCATION "${EXECUTORCH_RUNTIME_SO}")

add_library(cpuinfo_so SHARED IMPORTED)
set_target_properties(cpuinfo_so PROPERTIES IMPORTED_LOCATION "${ET_LIBS_DIR}/cpuinfo/${ANDROID_ABI}/libcpuinfo.so")

add_library(pthreadpool_so SHARED IMPORTED)
set_target_properties(pthreadpool_so PROPERTIES IMPORTED_LOCATION "${ET_LIBS_DIR}/pthreadpool/${ANDROID_ABI}/libpthreadpool.so")

# Imported static OpenCV libraries
add_library(opencv_imgproc_a STATIC IMPORTED)
set_target_properties(opencv_imgproc_a PROPERTIES IMPORTED_LOCATION "${ET_LIBS_DIR}/opencv/${ANDROID_ABI}/libopencv_imgproc.a")

add_library(opencv_core_a STATIC IMPORTED)
set_target_properties(opencv_core_a PROPERTIES IMPORTED_LOCATION "${ET_LIBS_DIR}/opencv/${ANDROID_ABI}/libopencv_core.a")

add_library(opencv_photo_a STATIC IMPORTED)
set_target_properties(opencv_photo_a PROPERTIES IMPORTED_LOCATION "${ET_LIBS_DIR}/opencv/${ANDROID_ABI}/libopencv_photo.a")

add_library(opencv_video_a STATIC IMPORTED)
set_target_properties(opencv_video_a PROPERTIES IMPORTED_LOCATION "${ET_LIBS_DIR}/opencv/${ANDROID_ABI}/libopencv_video.a")

add_library(opencv_features2d_a STATIC IMPORTED)
set_target_properties(opencv_features2d_a PROPERTIES IMPORTED_LOCATION "${ET_LIBS_DIR}/opencv/${ANDROID_ABI}/libopencv_features2d.a")

add_library(opencv_highgui_a STATIC IMPORTED)
set_target_properties(opencv_highgui_a PROPERTIES IMPORTED_LOCATION "${ET_LIBS_DIR}/opencv/${ANDROID_ABI}/libopencv_highgui.a")

set(KLEIDICV_LIBS "")
if(EXISTS "${ET_LIBS_DIR}/opencv-third-party/${ANDROID_ABI}/libkleidicv.a")
  add_library(kleidicv_a STATIC IMPORTED)
  set_target_properties(kleidicv_a PROPERTIES IMPORTED_LOCATION "${ET_LIBS_DIR}/opencv-third-party/${ANDROID_ABI}/libkleidicv.a")

  add_library(kleidicv_thread_a STATIC IMPORTED)
  set_target_properties(kleidicv_thread_a PROPERTIES IMPORTED_LOCATION "${ET_LIBS_DIR}/opencv-third-party/${ANDROID_ABI}/libkleidicv_thread.a")

  add_library(kleidicv_hal_a STATIC IMPORTED)
  set_target_properties(kleidicv_hal_a PROPERTIES IMPORTED_LOCATION "${ET_LIBS_DIR}/opencv-third-party/${ANDROID_ABI}/libkleidicv_hal.a")

  list(APPEND KLEIDICV_LIBS kleidicv_hal_a kleidicv_thread_a kleidicv_a)
endif()

# Keep local account paths out of native diagnostics and debug metadata, including llama.cpp's.
if(NOT "$ENV{HOME}" STREQUAL "")
  add_compile_options("-ffile-prefix-map=$ENV{HOME}=~")
endif()

# Pinned llama.cpp/libmtmd (bootstrap.sh) for MiniCPM-V 4.7 captions: static, CPU-only libraries without
# tools, network code or OpenMP (OpenCV's private static OpenMP runtime stays the only one in this library).
# ARMv8.2 dot product + FP16 is the baseline of every supported test phone.
set(BUILD_SHARED_LIBS OFF)
set(LLAMA_BUILD_NUMBER 11588)
set(LLAMA_BUILD_COMMIT "7f51953")
set(LLAMA_BUILD_MTMD ON)
set(LLAMA_BUILD_COMMON OFF)
set(LLAMA_BUILD_TOOLS OFF)
set(LLAMA_BUILD_TESTS OFF)
set(LLAMA_BUILD_EXAMPLES OFF)
set(LLAMA_BUILD_SERVER OFF)
set(LLAMA_BUILD_APP OFF)
set(LLAMA_OPENSSL OFF)
set(MTMD_VIDEO OFF)
set(GGML_NATIVE OFF)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16")
set(GGML_OPENMP OFF)
# Debug APKs still need optimized inference kernels; restore this project's settings afterwards.
set(MEMEGET_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG}")
set(MEMEGET_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG}")
set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG")
set(CMAKE_CXX_STANDARD 17)
add_subdirectory("${RUNTIME_DIR}/build/llama.cpp" llama.cpp EXCLUDE_FROM_ALL)
set(CMAKE_C_FLAGS_DEBUG "${MEMEGET_C_FLAGS_DEBUG}")
set(CMAKE_CXX_FLAGS_DEBUG "${MEMEGET_CXX_FLAGS_DEBUG}")
set(CMAKE_CXX_STANDARD 20)

# Main inference JNI shared library
add_library(memeget_inference SHARED
  NativeInferenceJni.cpp
  ModelRunner.cpp
  LlamaVisionRunner.cpp
  ProjectorConversion.cpp
  ImageProcessing.cpp
  ${ET_COMMON_DIR}/rnexecutorch/data_processing/Numerical.cpp
  ${ET_COMMON_DIR}/rnexecutorch/data_processing/gzip.cpp
  ${ET_COMMON_DIR}/rnexecutorch/models/ocr/utils/DetectorUtils.cpp
  ${ET_COMMON_DIR}/rnexecutorch/models/ocr/utils/RecognizerUtils.cpp
  ${ET_COMMON_DIR}/rnexecutorch/models/ocr/utils/RecognitionHandlerUtils.cpp
  ${ET_COMMON_DIR}/rnexecutorch/models/ocr/CTCLabelConverter.cpp
  ${ET_COMMON_DIR}/rnexecutorch/utils/computer_vision/Processing.cpp
)

# The prebuilt OpenCV archives require OpenMP; keep its runtime private to this JNI library.
target_link_options(memeget_inference PRIVATE
  -fopenmp
  -static-openmp
  -Wl,--exclude-libs,libomp.a
)

target_link_libraries(memeget_inference
  PRIVATE
  executorch_so
  cpuinfo_so
  pthreadpool_so
  mtmd
  llama
  ggml
  opencv_imgproc_a
  opencv_photo_a
  opencv_video_a
  opencv_features2d_a
  opencv_highgui_a
  opencv_core_a
  ${KLEIDICV_LIBS}
  log
  jnigraphics
  z
)
