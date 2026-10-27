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

struct VisionGateDescribeResult {
    bool isMeme = false;
    float memeProbability = 0.0f;
    std::string description;
    int generatedTokens = 0;
    long long totalMs = 0;
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

    // Single-turn ChatML request with thinking disabled. `userPrompt` must contain the image marker
    // (mtmd_default_marker()) exactly once, where the image goes. The text before the image is kept
    // in the context between calls and reused while it stays the same, so put fixed instructions
    // there. Throws on failure or cancellation.
    std::string describe(const RgbImage& image,
                         const std::string& systemPrompt,
                         const std::string& userPrompt,
                         int maxTokens,
                         float temperature);

    // 1-token camera-roll gate + zero-re-encode two-domain meme describer:
    // reads step-0 YES/NO logits right off the prefilled image state, aborts at <=1 token on non-memes,
    // and continues straight into structured two-domain captioning when a real meme is on the slab.
    VisionGateDescribeResult gateAndDescribe(const RgbImage& image,
                                             const std::string& ocrHint,
                                             const std::string& candidateHints,
                                             int maxTokens = 140);

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
    // The prompt before the image from an earlier call, and the context's state right after it: MiniCPM-V 4.7's
    // language model has recurrent layers, so its state can be restored but not cut back to a position.
    std::vector<int32_t> cachedPrefix_;
    std::vector<uint8_t> cachedState_;
};

// Performance-core count (cores faster than the slowest cluster), clamped to [2, 4].
int defaultInferenceThreads();

// Largest same-aspect size with at most `maxPixels` pixels; never upscales.
void fitWithinPixels(int width, int height, int maxPixels, int& outWidth, int& outHeight);

} // namespace memeget
