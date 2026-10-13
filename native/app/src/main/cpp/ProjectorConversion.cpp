#include "ProjectorConversion.hpp"

#include <cmath>
#include <cstdio>
#include <memory>
#include <stdexcept>
#include <vector>

#include "ggml.h"
#include "gguf.h"
#include "llama.h"

namespace memeget {
namespace {

constexpr size_t kChunkElements = size_t{1} << 20;
constexpr size_t kCopyBytes = size_t{4} << 20;
constexpr float kMaxF16 = 65504.0f;

struct GgufDeleter {
    void operator()(gguf_context* context) const { gguf_free(context); }
};
struct GgmlDeleter {
    void operator()(ggml_context* context) const { ggml_free(context); }
};
struct FileCloser {
    void operator()(FILE* file) const { std::fclose(file); }
};

void checkCancelled(const std::atomic<bool>* cancelled) {
    if (cancelled != nullptr && cancelled->load(std::memory_order_relaxed)) {
        throw std::runtime_error("Projector conversion cancelled");
    }
}

void readExactly(FILE* file, void* data, size_t bytes) {
    if (bytes != 0 && std::fread(data, 1, bytes, file) != bytes) {
        throw std::runtime_error("Projector source is truncated");
    }
}

void writeExactly(FILE* file, const void* data, size_t bytes) {
    if (bytes != 0 && std::fwrite(data, 1, bytes, file) != bytes) {
        throw std::runtime_error("Projector output write failed");
    }
}

} // namespace

int convertBf16TensorsToF16(const std::string& sourcePath,
                            const std::string& destinationPath,
                            const std::atomic<bool>* cancelled) {
    ggml_context* rawMeta = nullptr;
    const gguf_init_params params{true, &rawMeta};
    std::unique_ptr<gguf_context, GgufDeleter> input(gguf_init_from_file(sourcePath.c_str(), params));
    if (!input) throw std::runtime_error("Projector is not a readable GGUF file: " + sourcePath);
    std::unique_ptr<ggml_context, GgmlDeleter> meta(rawMeta);

    std::unique_ptr<gguf_context, GgufDeleter> output(gguf_init_empty());
    gguf_set_kv(output.get(), input.get());
    const int64_t tensorCount = gguf_get_n_tensors(input.get());
    int converted = 0;
    for (int64_t i = 0; i < tensorCount; ++i) {
        const char* name = gguf_get_tensor_name(input.get(), i);
        const ggml_tensor* tensor = ggml_get_tensor(meta.get(), name);
        if (tensor == nullptr) throw std::runtime_error(std::string("Projector tensor is missing: ") + name);
        gguf_add_tensor(output.get(), tensor);
        if (tensor->type == GGML_TYPE_BF16) {
            gguf_set_tensor_type(output.get(), name, GGML_TYPE_F16);
            ++converted;
        }
    }
    // The file-type label describes the dominant tensor type; keep it truthful for the converted file.
    const int64_t fileType = gguf_find_key(output.get(), "general.file_type");
    if (converted > 0 && fileType >= 0 && gguf_get_kv_type(output.get(), fileType) == GGUF_TYPE_UINT32 &&
        gguf_get_val_u32(output.get(), fileType) == LLAMA_FTYPE_MOSTLY_BF16) {
        gguf_set_val_u32(output.get(), "general.file_type", LLAMA_FTYPE_MOSTLY_F16);
    }

    std::unique_ptr<FILE, FileCloser> source(std::fopen(sourcePath.c_str(), "rb"));
    if (!source) throw std::runtime_error("Cannot open projector source: " + sourcePath);
    FILE* rawDestination = std::fopen(destinationPath.c_str(), "wb");
    if (rawDestination == nullptr) throw std::runtime_error("Cannot create projector output: " + destinationPath);
    std::unique_ptr<FILE, FileCloser> destination(rawDestination);

    std::vector<uint8_t> header(gguf_get_meta_size(output.get()));
    gguf_get_meta_data(output.get(), header.data());
    writeExactly(destination.get(), header.data(), header.size());

    const size_t alignment = gguf_get_alignment(output.get());
    const size_t sourceData = gguf_get_data_offset(input.get());
    std::vector<uint8_t> bytes(kCopyBytes);
    std::vector<ggml_bf16_t> bf16(kChunkElements);
    std::vector<float> f32(kChunkElements);
    std::vector<ggml_fp16_t> f16(kChunkElements);
    const std::vector<uint8_t> zeros(alignment, 0);

    for (int64_t i = 0; i < tensorCount; ++i) {
        checkCancelled(cancelled);
        const size_t offset = sourceData + gguf_get_tensor_offset(input.get(), i);
        if (fseeko(source.get(), static_cast<off_t>(offset), SEEK_SET) != 0) {
            throw std::runtime_error("Projector source seek failed");
        }
        size_t remaining = gguf_get_tensor_size(input.get(), i);
        if (gguf_get_tensor_type(input.get(), i) == GGML_TYPE_BF16) {
            size_t elements = remaining / sizeof(ggml_bf16_t);
            while (elements > 0) {
                checkCancelled(cancelled);
                const size_t count = elements < kChunkElements ? elements : kChunkElements;
                readExactly(source.get(), bf16.data(), count * sizeof(ggml_bf16_t));
                ggml_bf16_to_fp32_row(bf16.data(), f32.data(), static_cast<int64_t>(count));
                for (size_t j = 0; j < count; ++j) {
                    if (std::isfinite(f32[j]) && std::fabs(f32[j]) > kMaxF16) {
                        throw std::runtime_error(std::string("Projector value exceeds the F16 range in ") +
                                                 gguf_get_tensor_name(input.get(), i));
                    }
                }
                ggml_fp32_to_fp16_row(f32.data(), f16.data(), static_cast<int64_t>(count));
                writeExactly(destination.get(), f16.data(), count * sizeof(ggml_fp16_t));
                elements -= count;
            }
        } else {
            while (remaining > 0) {
                const size_t count = remaining < bytes.size() ? remaining : bytes.size();
                readExactly(source.get(), bytes.data(), count);
                writeExactly(destination.get(), bytes.data(), count);
                remaining -= count;
            }
        }
        const size_t written = gguf_get_tensor_size(output.get(), i);
        writeExactly(destination.get(), zeros.data(), GGML_PAD(written, alignment) - written);
    }

    if (std::fflush(destination.get()) != 0) throw std::runtime_error("Projector output flush failed");
    FILE* finished = destination.release();
    if (std::fclose(finished) != 0) throw std::runtime_error("Projector output close failed");
    return converted;
}

} // namespace memeget
