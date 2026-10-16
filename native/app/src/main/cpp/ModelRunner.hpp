#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <executorch/extension/module/module.h>
#include <pytorch/tokenizers/hf_tokenizer.h>
#include <rnexecutorch/models/ocr/CTCLabelConverter.h>

#include "LlamaVisionRunner.hpp"

namespace memeget {

enum class ModelFamily {
    NONE,
    PRIMARY,       // MobileCLIP-S2 (Image + Text)
    VISUAL,        // DINOv2
    OCR,           // CRAFT + CRNN
    AUDIO,         // Whisper
    SEGMENTATION,  // FastSAM
    VLM            // MiniCPM-V 4.7 1B (llama.cpp)
};

struct OcrItem {
    std::string text;
    float confidence;
    float x;
    float y;
    float width;
    float height;
};

struct FastSamItem {
    int x;
    int y;
    int width;
    int height;
    float score;
    std::vector<uint8_t> mask; // width * height bytes (0 or 1)
};

class ModelRunner {
public:
    ModelRunner();
    ~ModelRunner();

    ModelRunner(const ModelRunner&) = delete;
    ModelRunner& operator=(const ModelRunner&) = delete;

    void cancel();
    void resetCancel();
    bool isCancelled() const;
    void unloadAll();
    std::string getDiagnostics();

    std::vector<float> embedImage(
        const std::string& modelPath,
        const std::string& imagePath,
        int inputSize,
        bool normalize);

    // tokenIds: 77 MobileCLIP-S2 ids, tokenized in Kotlin (ClipTokenizer). The runtime's HFTokenizer doesn't
    // reproduce CLIP's tokenizer: with its ids every text embedded as nearly the same vector.
    std::vector<float> embedTextTokens(
        const std::string& modelPath,
        const std::vector<int64_t>& tokenIds);

    std::vector<OcrItem> recognize(
        const std::string& detectorPath,
        const std::string& recognizerPath,
        const std::string& imagePath);

    std::string transcribe(
        const std::string& modelPath,
        const std::string& tokenizerPath,
        const std::vector<float>& pcmSamples);

    std::vector<FastSamItem> segment(
        const std::string& modelPath,
        const std::string& imagePath,
        int maxInstances,
        float confThreshold,
        float iouThreshold);

    // `maxImagePixels` bounds the image handed to the projector, so one meme stays one encoder pass.
    std::string describe(
        const std::string& modelPath,
        const std::string& projectorPath,
        const std::string& imagePath,
        const std::string& systemPrompt,
        const std::string& userPrompt,
        int maxTokens,
        float temperature,
        int maxImagePixels);

    // Writes an F16 copy of a BF16 GGUF projector (see ProjectorConversion.hpp); honors cancel().
    int convertProjector(const std::string& sourcePath, const std::string& destinationPath);

private:
    void ensureFamily(ModelFamily family);

    std::mutex mutex_;
    std::atomic<bool> cancelled_{false};
    ModelFamily current_family_{ModelFamily::NONE};
    // Primary family (MobileCLIP-S2)
    std::unique_ptr<executorch::extension::module::Module> s2_image_module_;
    std::unique_ptr<executorch::extension::module::Module> s2_text_module_;
    std::string s2_image_path_;
    std::string s2_text_path_;

    // Visual family (DINOv2)
    std::unique_ptr<executorch::extension::module::Module> visual_module_;
    std::string visual_path_;

    // OCR family (CRAFT + CRNN)
    std::unique_ptr<executorch::extension::module::Module> craft_module_;
    std::unique_ptr<executorch::extension::module::Module> crnn_module_;
    std::unique_ptr<rnexecutorch::models::ocr::CTCLabelConverter> ocr_converter_;
    std::string craft_path_;
    std::string crnn_path_;

    // Audio family (Whisper)
    std::unique_ptr<executorch::extension::module::Module> whisper_module_;
    std::unique_ptr<tokenizers::HFTokenizer> whisper_tokenizer_;
    std::string whisper_model_path_;
    std::string whisper_tokenizer_path_;

    // Segmentation family (FastSAM)
    std::unique_ptr<executorch::extension::module::Module> fastsam_module_;
    std::string fastsam_path_;

    // VLM family (MiniCPM-V 4.7 through llama.cpp); reads cancelled_ as its abort flag.
    std::unique_ptr<LlamaVisionRunner> vlm_runner_;
};

} // namespace memeget
