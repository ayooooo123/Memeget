#pragma once

#include <atomic>
#include <cstdint>
#include <string>
#include <vector>

struct llama_context;
struct llama_model;

namespace memeget::core {

struct DecisionEvalOutput {
    int numOptions = 0;
    int promptTokens = 0;
    long long evalMs = 0;
    int capturedCount = 0;
    std::vector<float> baseLogits;
    std::vector<float> scorerHidden;
};

struct MemeGateD1Result {
    bool isMeme = false;
    float memeProbability = 0.0f;
    float memeLogit = 0.0f;
    float nonMemeLogit = 0.0f;
    long long evalMs = 0;
    int promptTokens = 0;
};

// Portable System-1 runner for Master's fine-tuned LiquidAI/d1-omni-600M GGUF: one clean forward stroke,
// zero KV memory leak across calls, and instant RAII teardown when unloaded.
class LlamaDecisionRunner {
public:
    struct CaptureState {
        bool active = false;
        bool captured = false;
        std::vector<int32_t> markerIndices;
        std::vector<float> hidden;
    };

    LlamaDecisionRunner(const std::string& modelPath,
                        int threads,
                        const std::atomic<bool>* cancelled);
    ~LlamaDecisionRunner();

    LlamaDecisionRunner(const LlamaDecisionRunner&) = delete;
    LlamaDecisionRunner& operator=(const LlamaDecisionRunner&) = delete;

    const std::string& modelPath() const { return modelPath_; }
    bool isCancelled() const;

    DecisionEvalOutput evaluate(const std::string& renderedPrompt);
    MemeGateD1Result evaluateMemeGate(const std::string& renderedGatePrompt);

private:
    std::string modelPath_;
    const std::atomic<bool>* cancelled_ = nullptr;
    llama_model* model_ = nullptr;
    llama_context* context_ = nullptr;
    CaptureState capture_;
};

} // namespace memeget::core
