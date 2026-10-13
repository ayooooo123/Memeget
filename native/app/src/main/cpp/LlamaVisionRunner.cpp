#include "LlamaVisionRunner.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <thread>

#include "llama.h"
#include "mtmd-helper.h"
#include "mtmd.h"

#if defined(__ANDROID__)
#include <android/log.h>
#endif

namespace memeget {
namespace {

// One meme per request: <= 10 image slices of 64 tokens, a short prompt and the reply.
constexpr uint32_t kContextTokens = 2048;
constexpr uint32_t kBatchTokens = 512;
constexpr int32_t kRepeatWindow = 64;
constexpr float kRepeatPenalty = 1.05f;
constexpr uint32_t kSamplerSeed = 0x5EEDu;

void logSink(ggml_log_level level, const char* text, void*) {
    if (level != GGML_LOG_LEVEL_WARN && level != GGML_LOG_LEVEL_ERROR) return;
#if defined(__ANDROID__)
    __android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN,
                        "MemegetLlama", "%s", text);
#else
    std::fputs(text, stderr);
#endif
}

void initBackendOnce() {
    static std::once_flag once;
    std::call_once(once, [] {
        llama_log_set(logSink, nullptr);
        mtmd_helper_log_set(logSink, nullptr);
        llama_backend_init();
    });
}

bool abortRequested(void* data) {
    return static_cast<const std::atomic<bool>*>(data)->load(std::memory_order_relaxed);
}

struct SamplerDeleter {
    void operator()(llama_sampler* sampler) const { llama_sampler_free(sampler); }
};
struct ChunksDeleter {
    void operator()(mtmd_input_chunks* chunks) const { mtmd_input_chunks_free(chunks); }
};
struct BitmapDeleter {
    void operator()(mtmd_bitmap* bitmap) const { mtmd_bitmap_free(bitmap); }
};
struct BatchDeleter {
    void operator()(llama_batch_ext* batch) const { llama_batch_ext_free(batch); }
};

using Clock = std::chrono::steady_clock;

long long millisSince(Clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count();
}

std::unique_ptr<llama_sampler, SamplerDeleter> makeSampler(const llama_vocab* vocab, float temperature) {
    llama_sampler_chain_params params = llama_sampler_chain_default_params();
    params.no_perf = true;
    std::unique_ptr<llama_sampler, SamplerDeleter> chain(llama_sampler_chain_init(params));
    llama_sampler_chain_add(chain.get(), llama_sampler_init_penalties(
        llama_vocab_n_tokens(vocab), kRepeatWindow, kRepeatPenalty, 0.0f, 0.0f));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain.get(), llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain.get(), llama_sampler_init_top_k(40));
        llama_sampler_chain_add(chain.get(), llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(chain.get(), llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain.get(), llama_sampler_init_dist(kSamplerSeed));
    }
    return chain;
}

void appendPiece(const llama_vocab* vocab, llama_token token, std::string& output) {
    char buffer[128];
    int32_t written = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, false);
    if (written >= 0) {
        output.append(buffer, static_cast<size_t>(written));
        return;
    }
    std::string large(static_cast<size_t>(-written), '\0');
    written = llama_token_to_piece(vocab, token, large.data(), static_cast<int32_t>(large.size()), 0, false);
    if (written < 0) throw std::runtime_error("MiniCPM-V token could not be converted to text");
    output.append(large.data(), static_cast<size_t>(written));
}

} // namespace

LlamaVisionRunner::LlamaVisionRunner(const std::string& modelPath,
                                     const std::string& projectorPath,
                                     int threads,
                                     const std::atomic<bool>* cancelled)
    : modelPath_(modelPath), projectorPath_(projectorPath), cancelled_(cancelled) {
    if (cancelled_ == nullptr) throw std::invalid_argument("A cancellation flag is required");
    initBackendOnce();
    const int nThreads = std::max(1, threads);

    llama_model_params modelParams = llama_model_default_params();
    model_ = llama_model_load_from_file(modelPath.c_str(), modelParams);
    if (model_ == nullptr) throw std::runtime_error("Failed to load MiniCPM-V language model: " + modelPath);

    llama_context_params contextParams = llama_context_default_params();
    contextParams.n_ctx = kContextTokens;
    contextParams.n_batch = kBatchTokens;
    contextParams.n_ubatch = kBatchTokens;
    contextParams.n_seq_max = 1;
    contextParams.n_threads = nThreads;
    contextParams.n_threads_batch = nThreads;
    contextParams.abort_callback = abortRequested;
    contextParams.abort_callback_data = const_cast<std::atomic<bool>*>(cancelled_);
    contextParams.no_perf = true;
    context_ = llama_init_from_model(model_, contextParams);
    if (context_ == nullptr) {
        llama_model_free(model_);
        throw std::runtime_error("Failed to create MiniCPM-V language context");
    }

    mtmd_context_params visionParams = mtmd_context_params_default();
    visionParams.use_gpu = false;
    visionParams.print_timings = false;
    visionParams.n_threads = nThreads;
    visionParams.warmup = false;
    vision_ = mtmd_init_from_file(projectorPath.c_str(), model_, visionParams);
    if (vision_ == nullptr || !mtmd_support_vision(vision_)) {
        if (vision_ != nullptr) mtmd_free(vision_);
        llama_free(context_);
        llama_model_free(model_);
        throw std::runtime_error("Failed to load MiniCPM-V vision projector: " + projectorPath);
    }
}

LlamaVisionRunner::~LlamaVisionRunner() {
    mtmd_free(vision_);
    llama_free(context_);
    llama_model_free(model_);
}

bool LlamaVisionRunner::matches(const std::string& modelPath, const std::string& projectorPath) const {
    return modelPath_ == modelPath && projectorPath_ == projectorPath;
}

bool LlamaVisionRunner::isCancelled() const {
    return cancelled_->load(std::memory_order_relaxed);
}

std::string LlamaVisionRunner::describe(const RgbImage& image,
                                        const std::string& systemPrompt,
                                        const std::string& userPrompt,
                                        int maxTokens,
                                        float temperature) {
    if (image.width <= 0 || image.height <= 0 ||
        image.pixels.size() != static_cast<size_t>(image.width) * static_cast<size_t>(image.height) * 3) {
        throw std::invalid_argument("MiniCPM-V needs a non-empty RGB image");
    }
    if (maxTokens <= 0) throw std::invalid_argument("maxTokens must be positive");
    if (isCancelled()) throw std::runtime_error("Inference cancelled");

    llama_memory_clear(llama_get_memory(context_), true);

    // MiniCPM-V 4.7 chat template (ChatML) with enable_thinking=false: an empty think block opens the reply.
    std::string prompt;
    if (!systemPrompt.empty()) prompt += "<|im_start|>system\n" + systemPrompt + "<|im_end|>\n";
    prompt += "<|im_start|>user\n";
    prompt += mtmd_default_marker();
    prompt += "\n";
    prompt += userPrompt;
    prompt += "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";

    std::unique_ptr<mtmd_bitmap, BitmapDeleter> bitmap(mtmd_bitmap_init(
        static_cast<uint32_t>(image.width), static_cast<uint32_t>(image.height), image.pixels.data()));
    std::unique_ptr<mtmd_input_chunks, ChunksDeleter> chunks(mtmd_input_chunks_init());
    const mtmd_input_text text{prompt.c_str(), prompt.size(), true, true};
    const mtmd_bitmap* bitmaps[] = {bitmap.get()};
    int32_t status = mtmd_tokenize(vision_, chunks.get(), &text, bitmaps, 1);
    if (status != 0) throw std::runtime_error("MiniCPM-V prompt tokenization failed: " + std::to_string(status));

    const size_t promptTokens = mtmd_helper_get_n_tokens(chunks.get());
    if (promptTokens + static_cast<size_t>(maxTokens) > llama_n_ctx(context_)) {
        throw std::runtime_error("MiniCPM-V prompt does not fit the context: " + std::to_string(promptTokens) + " tokens");
    }

    long long imageMs = 0;
    long long prefillMs = 0;
    llama_pos nPast = 0;
    const size_t chunkCount = mtmd_input_chunks_size(chunks.get());
    for (size_t i = 0; i < chunkCount; ++i) {
        const mtmd_input_chunk* chunk = mtmd_input_chunks_get(chunks.get(), i);
        const auto started = Clock::now();
        status = mtmd_helper_eval_chunk_single(vision_, context_, chunk, nPast, 0,
                                               static_cast<int32_t>(kBatchTokens), i + 1 == chunkCount, &nPast);
        if (isCancelled()) throw std::runtime_error("Inference cancelled");
        if (status != 0) throw std::runtime_error("MiniCPM-V prompt evaluation failed: " + std::to_string(status));
        (mtmd_input_chunk_get_type(chunk) == MTMD_INPUT_CHUNK_TYPE_TEXT ? prefillMs : imageMs) += millisSince(started);
    }

    const llama_vocab* vocab = llama_model_get_vocab(model_);
    auto sampler = makeSampler(vocab, temperature);
    std::unique_ptr<llama_batch_ext, BatchDeleter> batch(llama_batch_ext_init(context_));
    std::string output;
    int generated = 0;
    const auto decodeStarted = Clock::now();
    for (; generated < maxTokens; ++generated) {
        if (isCancelled()) throw std::runtime_error("Inference cancelled");
        const llama_token token = llama_sampler_sample(sampler.get(), context_, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        appendPiece(vocab, token, output);

        llama_batch_ext_clear(batch.get());
        const int32_t index = llama_batch_ext_add_token(batch.get(), 0, token);
        if (index < 0) throw std::runtime_error("MiniCPM-V decode batch rejected a token");
        const llama_pos position = nPast++;
        llama_batch_ext_set_pos(batch.get(), index, &position);
        llama_batch_ext_set_output_logits(batch.get(), index, true);
        status = llama_process(context_, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        if (status == 2 || isCancelled()) throw std::runtime_error("Inference cancelled");
        if (status != 0) throw std::runtime_error("MiniCPM-V token decode failed: " + std::to_string(status));
    }
    const long long decodeMs = millisSince(decodeStarted);

    std::ostringstream timings;
    timings << "image_ms=" << imageMs << ";prefill_ms=" << prefillMs << ";prompt_tokens=" << promptTokens
            << ";decode_ms=" << decodeMs << ";generated_tokens=" << generated
            << ";input=" << image.width << "x" << image.height;
    timings_ = timings.str();
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "MemegetLlama", "describe %s", timings_.c_str());
#endif
    return output;
}

int defaultInferenceThreads() {
    std::vector<long> maxFrequencies;
    for (int cpu = 0; cpu < 32; ++cpu) {
        std::ifstream input("/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cpufreq/cpuinfo_max_freq");
        long khz = 0;
        if (input >> khz && khz > 0) maxFrequencies.push_back(khz);
    }
    if (maxFrequencies.empty()) {
        const unsigned hardware = std::thread::hardware_concurrency();
        return std::clamp(static_cast<int>(hardware / 2), 2, 4);
    }
    const long slowest = *std::min_element(maxFrequencies.begin(), maxFrequencies.end());
    int performanceCores = static_cast<int>(std::count_if(maxFrequencies.begin(), maxFrequencies.end(),
                                                          [slowest](long khz) { return khz > slowest; }));
    if (performanceCores == 0) performanceCores = static_cast<int>(maxFrequencies.size());
    return std::clamp(performanceCores, 2, 4);
}

void fitWithinPixels(int width, int height, int maxPixels, int& outWidth, int& outHeight) {
    if (width <= 0 || height <= 0 || maxPixels <= 0) throw std::invalid_argument("Invalid image size");
    const double pixels = static_cast<double>(width) * static_cast<double>(height);
    if (pixels <= static_cast<double>(maxPixels)) {
        outWidth = width;
        outHeight = height;
        return;
    }
    const double scale = std::sqrt(static_cast<double>(maxPixels) / pixels);
    outWidth = std::max(1, static_cast<int>(std::floor(width * scale)));
    outHeight = std::max(1, static_cast<int>(std::floor(height * scale)));
}

} // namespace memeget
