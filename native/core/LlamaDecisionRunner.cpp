#include "LlamaDecisionRunner.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <vector>

#include "ggml-backend.h"
#include "ggml.h"
#include "llama.h"

#if defined(__ANDROID__)
#include <android/log.h>
#endif

namespace memeget::core {
namespace {

constexpr uint32_t kDecisionContextTokens = 1024;
constexpr int kHiddenDim = 1024;

using Clock = std::chrono::steady_clock;

long long millisSince(Clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count();
}

bool abortRequested(void* data) {
    return static_cast<const std::atomic<bool>*>(data)->load(std::memory_order_relaxed);
}

struct BatchDeleter {
    void operator()(llama_batch_ext* batch) const { llama_batch_ext_free(batch); }
};

void initLlamaBackendOnce() {
    static std::once_flag once;
    std::call_once(once, [] { llama_backend_init(); });
}

bool captureScorerHiddenCallback(struct ggml_tensor* t, bool ask, void* user_data) {
    if (std::strcmp(ggml_get_name(t), "result_norm") != 0 || t->ne[0] != kHiddenDim) {
        return false;
    }
    if (ask) return true;
    auto* state = static_cast<LlamaDecisionRunner::CaptureState*>(user_data);
    if (!state || !state->active || state->captured || state->markerIndices.empty()) return true;
    const int numOpts = static_cast<int>(state->markerIndices.size());
    state->hidden.resize(static_cast<size_t>(kHiddenDim) * numOpts);
    if (t->ne[1] == numOpts) {
        ggml_backend_tensor_get(t, state->hidden.data(), 0, state->hidden.size() * sizeof(float));
        state->captured = true;
    } else if (t->ne[1] > state->markerIndices.back()) {
        for (int k = 0; k < numOpts; ++k) {
            const size_t offsetBytes = static_cast<size_t>(state->markerIndices[k]) * kHiddenDim * sizeof(float);
            ggml_backend_tensor_get(t, state->hidden.data() + static_cast<size_t>(k) * kHiddenDim,
                                    offsetBytes, kHiddenDim * sizeof(float));
        }
        state->captured = true;
    }
    return true;
}

} // namespace

LlamaDecisionRunner::LlamaDecisionRunner(const std::string& modelPath,
                                         int threads,
                                         const std::atomic<bool>* cancelled)
    : modelPath_(modelPath), cancelled_(cancelled) {
    if (cancelled_ == nullptr) throw std::invalid_argument("A cancellation flag is required");
    initLlamaBackendOnce();
    const int nThreads = std::max(1, threads);

    llama_model_params modelParams = llama_model_default_params();
#if defined(__APPLE__)
    modelParams.n_gpu_layers = 99;
#else
    modelParams.n_gpu_layers = 0;
#endif
    model_ = llama_model_load_from_file(modelPath.c_str(), modelParams);
    if (model_ == nullptr) {
        throw std::runtime_error("Failed to load d1-omni decision model: " + modelPath);
    }

    llama_context_params contextParams = llama_context_default_params();
    contextParams.n_ctx = kDecisionContextTokens;
    contextParams.n_batch = kDecisionContextTokens;
    contextParams.n_ubatch = kDecisionContextTokens;
    contextParams.n_seq_max = 1;
    contextParams.n_threads = nThreads;
    contextParams.n_threads_batch = nThreads;
    contextParams.embeddings = true;
    contextParams.pooling_type = LLAMA_POOLING_TYPE_NONE;
    contextParams.abort_callback = abortRequested;
    contextParams.abort_callback_data = const_cast<std::atomic<bool>*>(cancelled_);
    contextParams.cb_eval = captureScorerHiddenCallback;
    contextParams.cb_eval_user_data = &capture_;
    contextParams.no_perf = true;

    context_ = llama_init_from_model(model_, contextParams);
    if (context_ == nullptr) {
        llama_model_free(model_);
        model_ = nullptr;
        throw std::runtime_error("Failed to create d1-omni decision context");
    }
}

LlamaDecisionRunner::~LlamaDecisionRunner() {
    capture_.hidden.clear();
    capture_.hidden.shrink_to_fit();
    if (context_ != nullptr) {
        llama_free(context_);
        context_ = nullptr;
    }
    if (model_ != nullptr) {
        llama_model_free(model_);
        model_ = nullptr;
    }
}

bool LlamaDecisionRunner::isCancelled() const {
    return cancelled_->load(std::memory_order_relaxed);
}

DecisionEvalOutput LlamaDecisionRunner::evaluate(const std::string& renderedPrompt) {
    if (renderedPrompt.empty()) throw std::invalid_argument("d1-omni prompt must not be empty");
    if (isCancelled()) throw std::runtime_error("Inference cancelled");

    // Flush sequence memory before every System-1 forward pass so recurrent/KV state never bloats or bleeds across items.
    llama_memory_clear(llama_get_memory(context_), true);

    const llama_vocab* vocab = llama_model_get_vocab(model_);
    const llama_token maskToken = llama_vocab_mask(vocab);
    if (maskToken == LLAMA_TOKEN_NULL) {
        throw std::runtime_error("Loaded GGUF has no <|mask|> token for d1-omni");
    }

    int32_t cap = static_cast<int32_t>(renderedPrompt.size()) + 16;
    std::vector<llama_token> tokens(cap);
    int32_t nTokens = llama_tokenize(vocab, renderedPrompt.c_str(), static_cast<int32_t>(renderedPrompt.size()),
                                     tokens.data(), cap, /*add_special=*/false, /*parse_special=*/true);
    if (nTokens < 0) {
        tokens.resize(-nTokens);
        nTokens = llama_tokenize(vocab, renderedPrompt.c_str(), static_cast<int32_t>(renderedPrompt.size()),
                                 tokens.data(), static_cast<int32_t>(tokens.size()), false, true);
    }
    if (nTokens <= 0 || static_cast<uint32_t>(nTokens) > kDecisionContextTokens) {
        throw std::runtime_error("d1-omni prompt token count out of range: " + std::to_string(nTokens));
    }
    tokens.resize(nTokens);

    std::unique_ptr<llama_batch_ext, BatchDeleter> batch(llama_batch_ext_init(context_));
    std::vector<int32_t> markerBatchIndices;
    for (int32_t i = 0; i < nTokens; ++i) {
        const int32_t idx = llama_batch_ext_add_token(batch.get(), 0, tokens[i]);
        if (idx < 0) throw std::runtime_error("d1-omni batch rejected a token");
        const llama_pos pos = i;
        llama_batch_ext_set_pos(batch.get(), idx, &pos);
        const bool isMarker = (tokens[i] == maskToken);
        llama_batch_ext_set_output_logits(batch.get(), idx, isMarker);
        if (isMarker) markerBatchIndices.push_back(idx);
    }

    const int numOptions = static_cast<int>(markerBatchIndices.size());
    if (numOptions < 2) {
        throw std::invalid_argument("d1-omni prompt needs at least 2 <|mask|> option markers");
    }

    capture_.active = true;
    capture_.captured = false;
    capture_.markerIndices = markerBatchIndices;
    capture_.hidden.assign(static_cast<size_t>(numOptions) * kHiddenDim, 0.0f);

    const auto started = Clock::now();
    const int32_t status = llama_process(context_, LLAMA_PROCESS_TYPE_DECODE, batch.get());
    capture_.active = false;
    const long long elapsed = millisSince(started);

    if (status == 2 || isCancelled()) throw std::runtime_error("Inference cancelled");
    if (status != 0) throw std::runtime_error("d1-omni forward pass failed: " + std::to_string(status));

    DecisionEvalOutput out;
    out.numOptions = numOptions;
    out.evalMs = elapsed;
    out.promptTokens = nTokens;
    out.baseLogits.resize(numOptions);
    for (int k = 0; k < numOptions; ++k) {
        const float* embd = llama_get_embeddings_ith(context_, markerBatchIndices[k]);
        if (embd == nullptr) {
            throw std::runtime_error("d1-omni returned null embeddings at option marker " + std::to_string(k));
        }
        out.baseLogits[k] = embd[0];
    }
    out.capturedCount = capture_.captured ? static_cast<int>(capture_.hidden.size()) : 0;
    out.scorerHidden = std::move(capture_.hidden);
    capture_.hidden.clear();

#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "MemegetD1Omni",
                        "decide options=%d tokens=%d eval_ms=%lld",
                        numOptions, nTokens, elapsed);
#endif
    return out;
}

MemeGateD1Result LlamaDecisionRunner::evaluateMemeGate(const std::string& renderedGatePrompt) {
    const DecisionEvalOutput raw = evaluate(renderedGatePrompt);
    if (raw.baseLogits.size() < 2) {
        throw std::runtime_error("d1-omni meme gate expected 2 option logits");
    }
    MemeGateD1Result res;
    res.memeLogit = raw.baseLogits[0];
    res.nonMemeLogit = raw.baseLogits[1];
    const float diff = std::clamp(res.memeLogit - res.nonMemeLogit, -20.0f, 20.0f);
    res.memeProbability = 1.0f / (1.0f + std::exp(-diff));
    res.isMeme = res.memeProbability >= 0.50f;
    res.evalMs = raw.evalMs;
    res.promptTokens = raw.promptTokens;
    return res;
}

} // namespace memeget::core
