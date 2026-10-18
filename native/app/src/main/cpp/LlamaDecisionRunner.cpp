#include "LlamaDecisionRunner.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <vector>

#include "ggml-backend.h"
#include "ggml.h"
#include "llama.h"
#include "LlamaVisionRunner.hpp"

#if defined(__ANDROID__)
#include <android/log.h>
#endif

namespace memeget {
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

// Intercepts the 1024-wide `result_norm` trunk output tensor in `lfm2.cpp` (`cb(cur, "result_norm", -1)`)
// right before `build_decision_head`, so each `<|mask|>` option carries its full uncollapsed 1024-d
// bidirectional trunk embedding. Always returns true on `ask` so the backend scheduler registers
// the callback even when the graph is reserved during context init.
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
    llama_backend_init();
    const int nThreads = std::max(1, threads);

    llama_model_params modelParams = llama_model_default_params();
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
    if (context_ != nullptr) llama_free(context_);
    if (model_ != nullptr) llama_model_free(model_);
}

bool LlamaDecisionRunner::isCancelled() const {
    return cancelled_->load(std::memory_order_relaxed);
}

DecisionEvalOutput LlamaDecisionRunner::evaluate(const std::string& renderedPrompt) {
    if (renderedPrompt.empty()) throw std::invalid_argument("d1-omni prompt must not be empty");
    if (isCancelled()) throw std::runtime_error("Inference cancelled");

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
    if (capture_.captured && numOptions >= 2) {
        const int numCands = numOptions - 1;
        float* noneRow = out.scorerHidden.data() + static_cast<size_t>(numCands) * kHiddenDim;
        const float invCands = 0.5f / static_cast<float>(numCands);
        for (int d = 0; d < kHiddenDim; ++d) {
            noneRow[d] *= 0.5f;
        }
        for (int k = 0; k < numCands; ++k) {
            const float* candRow = out.scorerHidden.data() + static_cast<size_t>(k) * kHiddenDim;
            for (int d = 0; d < kHiddenDim; ++d) {
                noneRow[d] += candRow[d] * invCands;
            }
        }
    }
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "MemegetD1Omni",
                        "decide options=%d tokens=%d eval_ms=%lld captured=%d",
                        numOptions, nTokens, elapsed, out.capturedCount);
#endif
    return out;
}

int fitDecisionHeadNative(
    const float* baseLogits,
    const float* scorerHidden,
    const int* noneIndices,
    const float* targets,
    const float* weights,
    int numExamples,
    int numOptions,
    int hiddenDim,
    int epochs,
    float learningRate,
    float l2Decay,
    float* deltaW,
    float* outDeltaBAndLoss) {
    if (numExamples <= 0 || numOptions < 2 || hiddenDim <= 0) return -1;
    std::vector<float> gradW(hiddenDim, 0.0f);
    float deltaB = outDeltaBAndLoss[0];
    float lastLoss = 0.0f;

    for (int ep = 0; ep < epochs; ++ep) {
        const float lr = learningRate / (1.0f + 0.1f * static_cast<float>(ep));
        const float decay = 1.0f - lr * l2Decay;
        float epochLoss = 0.0f;
        float totalWeight = 0.0f;

        for (int i = 0; i < numExamples; ++i) {
            const int noneIdx = noneIndices[i];
            const float* exLogits = baseLogits + static_cast<size_t>(i) * numOptions;
            const float* exHidden = scorerHidden + static_cast<size_t>(i) * numOptions * hiddenDim;
            const float* exTargets = targets + static_cast<size_t>(i) * numOptions;
            const float* exWeights = weights + static_cast<size_t>(i) * numOptions;
            const float zNone = exLogits[noneIdx];
            const float* hNone = exHidden + static_cast<size_t>(noneIdx) * hiddenDim;

            for (int k = 0; k < numOptions; ++k) {
                if (k == noneIdx) continue;
                const float w = exWeights[k];
                if (w <= 0.0f) continue;
                const float y = exTargets[k];
                const float* hk = exHidden + static_cast<size_t>(k) * hiddenDim;

                float residual = deltaB;
                float normSq = 0.0f;
                for (int d = 0; d < hiddenDim; ++d) {
                    const float dv = hk[d] - hNone[d];
                    residual += deltaW[d] * dv;
                    normSq += dv * dv;
                }
                const float rel = std::clamp((exLogits[k] - zNone) + residual, -20.0f, 20.0f);
                const float prob = 1.0f / (1.0f + std::exp(-rel));
                const float diff = (prob - y) * w;
                const float step = (lr * diff) / std::max(4.0f, normSq);
                for (int d = 0; d < hiddenDim; ++d) {
                    deltaW[d] = deltaW[d] * decay - step * (hk[d] - hNone[d]);
                }
                // Keep deltaB at 0 so multi-label positive examples do not lift unrelated candidates via a shared scalar offset.
                epochLoss += (std::log(1.0f + std::exp(rel)) - y * rel) * w;
                totalWeight += w;
            }
        }
        lastLoss = totalWeight > 0.0f ? (epochLoss / totalWeight) : 0.0f;
    }
    outDeltaBAndLoss[0] = deltaB;
    outDeltaBAndLoss[1] = lastLoss;
    return 0;
}

} // namespace memeget
