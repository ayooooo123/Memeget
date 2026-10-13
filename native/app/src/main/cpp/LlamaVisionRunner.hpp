#pragma once

#include <atomic>
#include <cstdint>
#include <string>
#include <vector>

struct llama_model;
struct llama_context;
struct mtmd_context;

namespace memeget {

// Tightly packed 8-bit RGB pixels in row-major order.
struct RgbImage {
    int width = 0;
    int height = 0;
    std::vector<uint8_t> pixels;
};

// MiniCPM-V 4.7 through the pinned llama.cpp/libmtmd runtime: a GGUF language model plus its
// vision projector, CPU only. One instance owns both models until it is destroyed.
class LlamaVisionRunner {
public:
    // `cancelled` must outlive the runner; it aborts decoding between ubatches and tokens.
    LlamaVisionRunner(const std::string& modelPath,
                      const std::string& projectorPath,
                      int threads,
                      const std::atomic<bool>* cancelled);
    ~LlamaVisionRunner();

    LlamaVisionRunner(const LlamaVisionRunner&) = delete;
    LlamaVisionRunner& operator=(const LlamaVisionRunner&) = delete;

    bool matches(const std::string& modelPath, const std::string& projectorPath) const;

    // Single-turn ChatML request with thinking disabled. Throws on failure or cancellation.
    std::string describe(const RgbImage& image,
                         const std::string& systemPrompt,
                         const std::string& userPrompt,
                         int maxTokens,
                         float temperature);

    // Measurements from the latest describe call, for diagnostics.
    const std::string& lastTimings() const { return timings_; }

private:
    bool isCancelled() const;

    std::string modelPath_;
    std::string projectorPath_;
    const std::atomic<bool>* cancelled_;
    llama_model* model_ = nullptr;
    llama_context* context_ = nullptr;
    mtmd_context* vision_ = nullptr;
    std::string timings_;
};

// Performance-core count (cores faster than the slowest cluster), clamped to [2, 4].
int defaultInferenceThreads();

// Largest same-aspect size with at most `maxPixels` pixels; never upscales.
void fitWithinPixels(int width, int height, int maxPixels, int& outWidth, int& outHeight);

} // namespace memeget
