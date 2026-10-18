#pragma once

#include <atomic>
#include <string>
#include <vector>

struct llama_model;
struct llama_context;

namespace memeget {

struct DecisionEvalOutput {
    int numOptions = 0;
    std::vector<float> baseLogits;
    std::vector<float> scorerHidden;
    long long evalMs = 0;
    int capturedCount = 0;
    int promptTokens = 0;
};

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

    bool matches(const std::string& modelPath) const { return modelPath_ == modelPath; }

    DecisionEvalOutput evaluate(const std::string& renderedPrompt);

private:
    bool isCancelled() const;

    std::string modelPath_;
    const std::atomic<bool>* cancelled_;
    CaptureState capture_;
    llama_model* model_ = nullptr;
    llama_context* context_ = nullptr;
};

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
    float* outDeltaBAndLoss);

} // namespace memeget
