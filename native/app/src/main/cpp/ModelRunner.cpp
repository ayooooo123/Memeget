#include "ModelRunner.hpp"
#include "ProjectorConversion.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <iostream>
#include <limits>
#include <span>
#include <sstream>

#if defined(__ANDROID__)
#include <android/log.h>
#endif

#include <executorch/extension/tensor/tensor_ptr_maker.h>
#include <rnexecutorch/data_processing/ImageProcessing.h>
#include <rnexecutorch/data_processing/Numerical.h>
#include <rnexecutorch/models/ocr/Constants.h>
#include <rnexecutorch/models/ocr/utils/DetectorUtils.h>
#include <rnexecutorch/models/ocr/utils/RecognizerUtils.h>
#include <rnexecutorch/models/ocr/utils/RecognitionHandlerUtils.h>
#include <rnexecutorch/utils/computer_vision/Processing.h>

namespace memeget {

using executorch::aten::ScalarType;
using executorch::aten::Tensor;
using executorch::extension::from_blob;
using executorch::extension::TensorPtr;
using executorch::extension::module::Module;
using executorch::runtime::EValue;
namespace ocr = rnexecutorch::models::ocr;
namespace cv_proc = rnexecutorch::utils::computer_vision;

// Genuine 97-symbol CTC alphabet for English CRNN recognizer (plus blank at index 0)
static const std::string kEnglishSymbols =
    "0123456789!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~ €ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

ModelRunner::ModelRunner() = default;

ModelRunner::~ModelRunner() {
    unloadAll();
}

void ModelRunner::cancel() {
    // The llama.cpp runner polls this flag between ubatches and generated tokens.
    cancelled_.store(true, std::memory_order_relaxed);
}

void ModelRunner::resetCancel() {
    cancelled_.store(false, std::memory_order_relaxed);
}

bool ModelRunner::isCancelled() const {
    return cancelled_.load(std::memory_order_relaxed);
}

void ModelRunner::unloadAll() {
    // Promptly signal cancellation before locking
    cancel();

    std::scoped_lock lock(mutex_);

    s2_image_module_.reset();
    s2_text_module_.reset();
    s2_tokenizer_.reset();
    s2_image_path_.clear();
    s2_text_path_.clear();
    s2_tokenizer_path_.clear();

    visual_module_.reset();
    visual_path_.clear();

    craft_module_.reset();
    crnn_module_.reset();
    ocr_converter_.reset();
    craft_path_.clear();
    crnn_path_.clear();

    whisper_module_.reset();
    whisper_tokenizer_.reset();
    whisper_model_path_.clear();
    whisper_tokenizer_path_.clear();

    fastsam_module_.reset();
    fastsam_path_.clear();

    vlm_runner_.reset();

    current_family_ = ModelFamily::NONE;
}

std::string ModelRunner::getDiagnostics() {
    std::scoped_lock lock(mutex_);
    std::ostringstream oss;
    oss << "current_family=";
    switch (current_family_) {
        case ModelFamily::NONE: oss << "NONE"; break;
        case ModelFamily::PRIMARY: oss << "PRIMARY(MobileCLIP-S2)"; break;
        case ModelFamily::VISUAL: oss << "VISUAL(DINOv2)"; break;
        case ModelFamily::OCR: oss << "OCR(CRAFT+CRNN)"; break;
        case ModelFamily::AUDIO: oss << "AUDIO(Whisper)"; break;
        case ModelFamily::SEGMENTATION: oss << "SEGMENTATION(FastSAM)"; break;
        case ModelFamily::VLM: oss << "VLM(MiniCPM-V-4.7-1B)"; break;
    }
    oss << ";cancelled=" << (cancelled_.load() ? "true" : "false");
    oss << ";runtime=executorch-0.9.2+llama.cpp-b11588;arch=arm64-v8a";
    return oss.str();
}

void ModelRunner::ensureFamily(ModelFamily family) {
    if (current_family_ == family) {
        return;
    }

    // Switch model family: unload other models to guarantee single-family lease
    if (current_family_ != ModelFamily::NONE) {
        if (current_family_ == ModelFamily::PRIMARY) {
            s2_image_module_.reset();
            s2_text_module_.reset();
            s2_tokenizer_.reset();
            s2_image_path_.clear();
            s2_text_path_.clear();
            s2_tokenizer_path_.clear();
        } else if (current_family_ == ModelFamily::VISUAL) {
            visual_module_.reset();
            visual_path_.clear();
        } else if (current_family_ == ModelFamily::OCR) {
            craft_module_.reset();
            crnn_module_.reset();
            ocr_converter_.reset();
            craft_path_.clear();
            crnn_path_.clear();
        } else if (current_family_ == ModelFamily::AUDIO) {
            whisper_module_.reset();
            whisper_tokenizer_.reset();
            whisper_model_path_.clear();
            whisper_tokenizer_path_.clear();
        } else if (current_family_ == ModelFamily::SEGMENTATION) {
            fastsam_module_.reset();
            fastsam_path_.clear();
        } else if (current_family_ == ModelFamily::VLM) {
            vlm_runner_.reset();
        }
    }
    current_family_ = family;
}

// ---------------------------------------------------------------------------
// 1. MobileCLIP-S2 / DINOv2 Image Embedding
// ---------------------------------------------------------------------------
std::vector<float> ModelRunner::embedImage(
    const std::string& modelPath,
    const std::string& imagePath,
    int inputSize,
    bool normalize) {

    std::scoped_lock lock(mutex_);
    const bool isVisual = (inputSize == 224);
    ensureFamily(isVisual ? ModelFamily::VISUAL : ModelFamily::PRIMARY);

    if (isVisual) {
        // DINOv2 (224x224)
        if (!visual_module_ || visual_path_ != modelPath) {
            visual_module_ = std::make_unique<Module>(
                modelPath, Module::LoadMode::MmapUseMlockIgnoreErrors);
            auto err = visual_module_->load();
            if (err != executorch::runtime::Error::Ok) {
                visual_module_.reset();
                visual_path_.clear();
                throw std::runtime_error("Failed to load DINOv2 model: " + modelPath);
            }
            visual_path_ = modelPath;
        }
    } else {
        // MobileCLIP-S2 (256x256)
        if (!s2_image_module_ || s2_image_path_ != modelPath) {
            s2_image_module_ = std::make_unique<Module>(
                modelPath, Module::LoadMode::MmapUseMlockIgnoreErrors);
            auto err = s2_image_module_->load();
            if (err != executorch::runtime::Error::Ok) {
                s2_image_module_.reset();
                s2_image_path_.clear();
                throw std::runtime_error("Failed to load MobileCLIP-S2 image model: " + modelPath);
            }
            s2_image_path_ = modelPath;
        }
    }

    Module* module = isVisual ? visual_module_.get() : s2_image_module_.get();

    cv::Mat imageBGR = rnexecutorch::image_processing::readImage(imagePath);
    if (imageBGR.empty()) {
        throw std::runtime_error("Failed to read image at: " + imagePath);
    }
    cv::Mat imageRGB;
    cv::cvtColor(imageBGR, imageRGB, cv::COLOR_BGR2RGB);

    cv::Mat resized;
    if (imageRGB.cols == inputSize && imageRGB.rows == inputSize) {
        resized = imageRGB;
    } else {
        cv::resize(imageRGB, resized, cv::Size(inputSize, inputSize));
    }

    // Both S2 and DINOv2 take RGB in [0, 1] planar CHW.
    // DINOv2 mean/std is baked in the ExecuTorch graph (do not normalize twice).
    std::vector<float> chw = rnexecutorch::image_processing::colorMatToVector(resized);
    std::vector<int32_t> dims = {1, 3, inputSize, inputSize};
    TensorPtr inputTensor = from_blob(chw.data(), dims, ScalarType::Float);

    auto forwardResult = module->execute("forward", {EValue(*inputTensor)});
    if (!forwardResult.ok()) {
        throw std::runtime_error("Image embedding forward pass failed");
    }

    Tensor outTensor = (*forwardResult)[0].toTensor();
    if (outTensor.scalar_type() != ScalarType::Float) {
        throw std::runtime_error("Unexpected output tensor scalar type: expected Float");
    }

    const size_t expectedDim = isVisual ? 768 : 512;
    if (outTensor.numel() != expectedDim) {
        throw std::runtime_error("Unexpected embedding dimension: got " +
            std::to_string(outTensor.numel()) + ", expected " + std::to_string(expectedDim));
    }

    const float* data = outTensor.const_data_ptr<float>();
    std::vector<float> vec(data, data + expectedDim);

    // Validate finite values
    for (float v : vec) {
        if (!std::isfinite(v)) {
            throw std::runtime_error("Non-finite value in image embedding output");
        }
    }

    if (normalize) {
        float sumSq = 0.0f;
        for (float v : vec) sumSq += v * v;
        float norm = std::sqrt(sumSq);
        if (!std::isfinite(norm) || norm <= 1e-12f) {
            throw std::runtime_error("Zero or invalid norm in image embedding output");
        }
        for (float& v : vec) v /= norm;
    }

    return vec;
}

// ---------------------------------------------------------------------------
// 2. MobileCLIP-S2 Text Embedding
// ---------------------------------------------------------------------------
std::vector<float> ModelRunner::embedText(
    const std::string& modelPath,
    const std::string& tokenizerPath,
    const std::string& text,
    int maxTokens) {

    std::scoped_lock lock(mutex_);
    ensureFamily(ModelFamily::PRIMARY);

    if (!s2_text_module_ || s2_text_path_ != modelPath) {
        s2_text_module_ = std::make_unique<Module>(
            modelPath, Module::LoadMode::MmapUseMlockIgnoreErrors);
        auto err = s2_text_module_->load();
        if (err != executorch::runtime::Error::Ok) {
            s2_text_module_.reset();
            s2_text_path_.clear();
            throw std::runtime_error("Failed to load MobileCLIP-S2 text model: " + modelPath);
        }
        s2_text_path_ = modelPath;
    }

    if (!s2_tokenizer_ || s2_tokenizer_path_ != tokenizerPath) {
        s2_tokenizer_ = std::make_unique<tokenizers::HFTokenizer>();
        auto err = s2_tokenizer_->load(tokenizerPath);
        if (err != tokenizers::Error::Ok) {
            s2_tokenizer_.reset();
            s2_tokenizer_path_.clear();
            throw std::runtime_error("Failed to load MobileCLIP-S2 tokenizer: " + tokenizerPath);
        }
        s2_tokenizer_path_ = tokenizerPath;
    }

    auto encodeResult = s2_tokenizer_->encode(text, 0, 0);
    if (!encodeResult.ok()) {
        throw std::runtime_error("Failed to tokenize text for embedding");
    }
    const std::vector<uint64_t>& tokenIds = *encodeResult;

    // MobileCLIP-S2 text encoder expects exact 77 tokens with int64 IDs and attention mask
    constexpr int kS2TokenLen = 77;
    const int effectiveTokens = (maxTokens > 0) ? maxTokens : kS2TokenLen;
    std::vector<int64_t> inputIds(effectiveTokens, 0);
    std::vector<int64_t> attentionMask(effectiveTokens, 0);

    size_t count = std::min(static_cast<size_t>(effectiveTokens), tokenIds.size());
    for (size_t i = 0; i < count; ++i) {
        inputIds[i] = static_cast<int64_t>(tokenIds[i]);
        attentionMask[i] = (tokenIds[i] != 0) ? 1 : 0;
    }

    std::vector<int32_t> shape = {1, effectiveTokens};
    TensorPtr tokenTensor = from_blob(inputIds.data(), shape, ScalarType::Long);
    TensorPtr maskTensor = from_blob(attentionMask.data(), shape, ScalarType::Long);

    auto forwardResult = s2_text_module_->execute("forward", {EValue(*tokenTensor), EValue(*maskTensor)});
    if (!forwardResult.ok()) {
        throw std::runtime_error("Text embedding forward pass failed");
    }

    Tensor outTensor = (*forwardResult)[0].toTensor();
    if (outTensor.scalar_type() != ScalarType::Float) {
        throw std::runtime_error("Unexpected text embedding scalar type: expected Float");
    }

    constexpr size_t kExpectedDim = 512;
    if (outTensor.numel() != kExpectedDim) {
        throw std::runtime_error("Unexpected text embedding dimension: got " +
            std::to_string(outTensor.numel()) + ", expected " + std::to_string(kExpectedDim));
    }

    const float* data = outTensor.const_data_ptr<float>();
    std::vector<float> vec(data, data + kExpectedDim);

    for (float v : vec) {
        if (!std::isfinite(v)) {
            throw std::runtime_error("Non-finite value in text embedding output");
        }
    }

    float sumSq = 0.0f;
    for (float v : vec) sumSq += v * v;
    float norm = std::sqrt(sumSq);
    if (!std::isfinite(norm) || norm <= 1e-12f) {
        throw std::runtime_error("Zero or invalid norm in text embedding output");
    }
    for (float& v : vec) v /= norm;

    return vec;
}

// ---------------------------------------------------------------------------
// 3. OCR (CRAFT Detector + CRNN English Recognizer)
// ---------------------------------------------------------------------------
std::vector<OcrItem> ModelRunner::recognize(
    const std::string& detectorPath,
    const std::string& recognizerPath,
    const std::string& imagePath) {

    std::scoped_lock lock(mutex_);
    ensureFamily(ModelFamily::OCR);

    if (!craft_module_ || craft_path_ != detectorPath) {
        craft_module_ = std::make_unique<Module>(
            detectorPath, Module::LoadMode::MmapUseMlockIgnoreErrors);
        auto err = craft_module_->load();
        if (err != executorch::runtime::Error::Ok) {
            craft_module_.reset();
            craft_path_.clear();
            throw std::runtime_error("Failed to load CRAFT detector: " + detectorPath);
        }
        craft_path_ = detectorPath;
    }

    if (!crnn_module_ || crnn_path_ != recognizerPath) {
        crnn_module_ = std::make_unique<Module>(
            recognizerPath, Module::LoadMode::MmapUseMlockIgnoreErrors);
        auto err = crnn_module_->load();
        if (err != executorch::runtime::Error::Ok) {
            crnn_module_.reset();
            crnn_path_.clear();
            throw std::runtime_error("Failed to load CRNN recognizer: " + recognizerPath);
        }
        crnn_path_ = recognizerPath;
    }

    if (!ocr_converter_) {
        ocr_converter_ = std::make_unique<ocr::CTCLabelConverter>(kEnglishSymbols);
    }

    cv::Mat imageBGR = rnexecutorch::image_processing::readImage(imagePath);
    if (imageBGR.empty()) {
        return {};
    }
    const int origWidth = imageBGR.cols;
    const int origHeight = imageBGR.rows;

    // CRAFT detection at medium width (800x800)
    constexpr int32_t kDetectorWidth = ocr::constants::kMediumDetectorWidth;
    cv::Size modelInputSize(kDetectorWidth, kDetectorWidth);
    cv::Mat resizedInputImage =
        rnexecutorch::image_processing::resizePadded(imageBGR, modelInputSize);

    std::vector<int32_t> craftInputShape = {1, 3, kDetectorWidth, kDetectorWidth};
    TensorPtr craftInputTensor = rnexecutorch::image_processing::getTensorFromMatrix(
        craftInputShape, resizedInputImage, ocr::constants::kNormalizationMean,
        ocr::constants::kNormalizationVariance);

    auto craftResult = craft_module_->execute("forward_" + std::to_string(kDetectorWidth), {EValue(*craftInputTensor)});
    if (!craftResult.ok()) {
        throw std::runtime_error("CRAFT detector forward pass failed");
    }

    Tensor craftOut = (*craftResult)[0].toTensor();
    std::span<const float> tensorData(craftOut.const_data_ptr<float>(), craftOut.numel());

    cv::Size halfSize(modelInputSize.width / 2, modelInputSize.height / 2);
    auto [scoreTextMat, scoreAffinityMat] =
        ocr::utils::interleavedArrayToMats(tensorData, halfSize);

    std::vector<ocr::types::DetectorBBox> bboxesList =
        ocr::utils::getDetBoxesFromTextMap(
            scoreTextMat, scoreAffinityMat, ocr::constants::kTextThreshold,
            ocr::constants::kLinkThreshold, ocr::constants::kLowTextThreshold);

    const float restoreRatio = ocr::utils::calculateRestoreRatio(
        scoreTextMat.rows, ocr::constants::kRecognizerImageSize);
    ocr::utils::restoreBboxRatio(bboxesList, restoreRatio);

    bboxesList = ocr::utils::groupTextBoxes(
        bboxesList, ocr::constants::kCenterThreshold, ocr::constants::kDistanceThreshold,
        ocr::constants::kHeightThreshold, ocr::constants::kMinSideThreshold,
        ocr::constants::kMaxSideThreshold, ocr::constants::kMaxWidth);

    if (bboxesList.empty()) {
        return {};
    }

    // CRNN recognition on grayscale 1280x1280
    cv::Mat imgGray;
    cv::cvtColor(imageBGR, imgGray, cv::COLOR_BGR2GRAY);

    cv::Size desiredSize(ocr::constants::kRecognizerImageSize, ocr::constants::kRecognizerImageSize);
    ocr::types::PaddingInfo ratioAndPadding =
        ocr::utils::calculateResizeRatioAndPaddings(imgGray.size(), desiredSize);
    imgGray = rnexecutorch::image_processing::resizePadded(imgGray, desiredSize);

    std::vector<OcrItem> results;
    results.reserve(bboxesList.size());

    for (auto& box : bboxesList) {
        if (cancelled_.load()) break;

        cv::Mat croppedImage = ocr::utils::cropImage(box, imgGray, ocr::constants::kRecognizerHeight);
        if (croppedImage.empty()) continue;

        int32_t desiredWidth = ocr::utils::getDesiredWidth(croppedImage, false);
        croppedImage = ocr::utils::normalizeForRecognizer(
            croppedImage, ocr::constants::kRecognizerHeight, ocr::constants::kAdjustContrast, false);

        std::string methodName = "forward_" + std::to_string(desiredWidth);
        std::vector<int32_t> recogShape = {1, 1, ocr::constants::kRecognizerHeight, desiredWidth};
        TensorPtr recogInput = rnexecutorch::image_processing::getTensorFromMatrixGray(recogShape, croppedImage);

        auto recogResult = crnn_module_->execute(methodName, {EValue(*recogInput)});
        if (!recogResult.ok()) continue;

        Tensor recogOut = (*recogResult)[0].toTensor();
        const int32_t alphabetSize = recogOut.size(2);
        const int32_t numRows = recogOut.numel() / alphabetSize;
        cv::Mat resultMat(numRows, alphabetSize, CV_32F, recogOut.mutable_data_ptr<float>());

        auto probabilities = ocr::utils::softmax(resultMat);
        auto [maxVal, maxIndices] = ocr::utils::findMaxValuesIndices(probabilities);
        float confidence = ocr::utils::confidenceScore(maxVal, maxIndices);

        if (confidence < ocr::constants::kLowConfidenceThreshold) {
            cv::Mat rotatedCropped;
            cv::rotate(croppedImage, rotatedCropped, cv::ROTATE_180);
            TensorPtr rotRecogInput = rnexecutorch::image_processing::getTensorFromMatrixGray(recogShape, rotatedCropped);
            auto rotRecogResult = crnn_module_->execute(methodName, {EValue(*rotRecogInput)});
            if (rotRecogResult.ok()) {
                Tensor rotRecogOut = (*rotRecogResult)[0].toTensor();
                cv::Mat rotResultMat(numRows, alphabetSize, CV_32F, rotRecogOut.mutable_data_ptr<float>());
                auto rotProbs = ocr::utils::softmax(rotResultMat);
                auto [rotMaxVal, rotMaxIndices] = ocr::utils::findMaxValuesIndices(rotProbs);
                float rotConf = ocr::utils::confidenceScore(rotMaxVal, rotMaxIndices);
                if (rotConf > confidence) {
                    confidence = rotConf;
                    maxIndices = rotMaxIndices;
                }
            }
        }

        std::vector<std::string> decoded = ocr_converter_->decodeGreedy(maxIndices, maxIndices.size());
        std::string text = decoded.empty() ? "" : decoded[0];
        if (text.empty()) continue;

        const float ratio = ratioAndPadding.resizeRatio;
        const float padLeft = static_cast<float>(ratioAndPadding.left);
        const float padTop = static_cast<float>(ratioAndPadding.top);

        float x1 = (box.bbox.p1.x - padLeft) * ratio;
        float y1 = (box.bbox.p1.y - padTop) * ratio;
        float x2 = (box.bbox.p2.x - padLeft) * ratio;
        float y2 = (box.bbox.p2.y - padTop) * ratio;

        float nx1 = std::max(0.0f, std::min(static_cast<float>(origWidth), x1));
        float ny1 = std::max(0.0f, std::min(static_cast<float>(origHeight), y1));
        float nx2 = std::max(0.0f, std::min(static_cast<float>(origWidth), x2));
        float ny2 = std::max(0.0f, std::min(static_cast<float>(origHeight), y2));

        if (nx2 > nx1 && ny2 > ny1) {
            results.push_back(OcrItem{
                .text = std::move(text),
                .confidence = confidence,
                .x = nx1 / origWidth,
                .y = ny1 / origHeight,
                .width = (nx2 - nx1) / origWidth,
                .height = (ny2 - ny1) / origHeight,
            });
        }
    }

    return results;
}

// ---------------------------------------------------------------------------
// 4. Speech-to-Text (Whisper; model chosen in ModelRegistry.kt)
// ---------------------------------------------------------------------------
std::string ModelRunner::transcribe(
    const std::string& modelPath,
    const std::string& tokenizerPath,
    const std::vector<float>& pcmSamples) {

    constexpr size_t kSamplingRate = 16000;
    constexpr size_t kMinChunkSamples = 16000; // 1 second minimum
    if (pcmSamples.size() < kMinChunkSamples) {
        return "";
    }

    const auto started = std::chrono::steady_clock::now();
    std::chrono::steady_clock::duration encodeTime{};
    size_t decodeSteps = 0;
    std::scoped_lock lock(mutex_);
    ensureFamily(ModelFamily::AUDIO);

    if (!whisper_module_ || whisper_model_path_ != modelPath) {
        whisper_module_ = std::make_unique<Module>(
            modelPath, Module::LoadMode::MmapUseMlockIgnoreErrors);
        auto err = whisper_module_->load();
        if (err != executorch::runtime::Error::Ok) {
            whisper_module_.reset();
            whisper_model_path_.clear();
            throw std::runtime_error("Failed to load Whisper STT model: " + modelPath);
        }
        whisper_model_path_ = modelPath;
    }

    if (!whisper_tokenizer_ || whisper_tokenizer_path_ != tokenizerPath) {
        whisper_tokenizer_ = std::make_unique<tokenizers::HFTokenizer>();
        auto err = whisper_tokenizer_->load(tokenizerPath);
        if (err != tokenizers::Error::Ok) {
            whisper_tokenizer_.reset();
            whisper_tokenizer_path_.clear();
            throw std::runtime_error("Failed to load Whisper tokenizer: " + tokenizerPath);
        }
        whisper_tokenizer_path_ = tokenizerPath;
    }

    // Upstream whisper constants: 29s chunks
    constexpr size_t kChunkSizeSec = 29;

    const auto tokenId = [this](const char* piece) -> uint64_t {
        const auto result = whisper_tokenizer_->piece_to_id(piece);
        if (!result.ok()) throw std::runtime_error(std::string("Whisper tokenizer is missing ") + piece);
        return result.get();
    };
    const uint64_t startOfTranscriptionToken = tokenId("<|startoftranscript|>");
    const uint64_t endOfTextToken = tokenId("<|endoftext|>");
    const uint64_t timestampBeginToken = tokenId("<|0.00|>");

    std::string fullTranscript;
    float seek = 0.0f;
    const float waveformSize = static_cast<float>(pcmSamples.size());

    while (seek * kSamplingRate < waveformSize && !cancelled_.load()) {
        size_t start = static_cast<size_t>(seek * kSamplingRate);
        size_t end = std::min(
            static_cast<size_t>((seek + kChunkSizeSec) * kSamplingRate),
            pcmSamples.size());
        size_t chunkLen = (end > start) ? (end - start) : 0;
        if (chunkLen < kMinChunkSamples) {
            break;
        }

        TensorPtr wavTensor = from_blob(
            const_cast<float*>(pcmSamples.data() + start),
            {static_cast<int32_t>(chunkLen)}, ScalarType::Float);

        const auto encodeStarted = std::chrono::steady_clock::now();
        auto encResult = whisper_module_->execute("encode", {EValue(*wavTensor)});
        encodeTime += std::chrono::steady_clock::now() - encodeStarted;
        if (!encResult.ok()) {
            throw std::runtime_error("Whisper encode method failed");
        }

        Tensor encFeatures = (*encResult)[0].toTensor();
        const float* encData = encFeatures.const_data_ptr<float>();
        constexpr int32_t numFrames = 1500;
        const int32_t encHidden = static_cast<int32_t>(encFeatures.numel() / numFrames);
        std::vector<int32_t> encShape = {1, numFrames, encHidden};
        TensorPtr encTensor = from_blob(const_cast<float*>(encData), encShape, ScalarType::Float);

        // Initial sequence: <|startoftranscript|>, <|0.00|>
        const std::array<uint64_t, 2> sequenceIds = {startOfTranscriptionToken, timestampBeginToken};
        uint64_t startPos = 0;
        uint64_t curTok = 0;
        uint64_t curPos = 0;
        TensorPtr tokTensor = from_blob(&curTok, {1, 1}, ScalarType::Long);
        TensorPtr posTensor = from_blob(&curPos, {1}, ScalarType::Long);
        const std::vector<EValue> decodeInputs = {EValue(*tokTensor), EValue(*posTensor), EValue(*encTensor)};

        Tensor logitsTensor{nullptr};
        for (size_t i = 0; i < sequenceIds.size(); ++i, ++startPos) {
            curTok = sequenceIds[i];
            curPos = startPos;
            auto decResult = whisper_module_->execute("decode", decodeInputs);
            ++decodeSteps;
            if (!decResult.ok()) throw std::runtime_error("Whisper decoder prefill failed");
            logitsTensor = (*decResult)[0].toTensor();
        }

        constexpr size_t kMaxDecodeLength = 128;
        std::vector<uint64_t> chunkTokens;
        chunkTokens.reserve(kMaxDecodeLength);
        // Where the last finished segment ends: a timestamp right after text closes one.
        uint64_t lastClosedStep = 0;
        size_t closedTokens = 0;
        bool previousWasTimestamp = true;

        while (startPos < kMaxDecodeLength && !cancelled_.load()) {
            if (!logitsTensor.const_data_ptr()) break;

            const size_t logitsInnerDim = logitsTensor.size(1);
            const size_t logitsDictSize = logitsTensor.size(2);
            const float* logitsData = logitsTensor.const_data_ptr<float>() +
                                      (logitsInnerDim - 1) * logitsDictSize;

            uint64_t nextId = 0;
            float maxLogit = -1e9f;
            for (size_t v = 0; v < logitsDictSize; ++v) {
                if (logitsData[v] > maxLogit) {
                    maxLogit = logitsData[v];
                    nextId = v;
                }
            }

            if (nextId == endOfTextToken) {
                break;
            }

            if (nextId >= timestampBeginToken) {
                if (!previousWasTimestamp) {
                    lastClosedStep = nextId - timestampBeginToken;
                    closedTokens = chunkTokens.size();
                }
                previousWasTimestamp = true;
            } else {
                chunkTokens.push_back(nextId);
                previousWasTimestamp = false;
            }

            curTok = nextId;
            curPos = startPos++;
            auto decResult = whisper_module_->execute("decode", decodeInputs);
            ++decodeSteps;
            if (!decResult.ok()) throw std::runtime_error("Whisper decode method failed");
            logitsTensor = (*decResult)[0].toTensor();
        }

        // When the model stops early in the window, or runs out of room mid-segment, carry on from the end of
        // its last finished segment instead of skipping the rest of the window; anything after that point is
        // decoded again from there. Each step moves at least a second, so the loop always ends.
        const float closedSec = static_cast<float>(lastClosedStep) * 0.02f;
        const float windowSec = static_cast<float>(chunkLen) / kSamplingRate;
        const bool resume = closedSec >= 1.0f && closedSec < windowSec - 1.0f;
        if (resume) chunkTokens.resize(closedTokens);

        if (!chunkTokens.empty()) {
            auto decodedText = whisper_tokenizer_->decode(chunkTokens, true);
            if (!decodedText.ok()) throw std::runtime_error("Whisper token decoding failed");
            if (!fullTranscript.empty()) fullTranscript += " ";
            fullTranscript += *decodedText;
        }

        seek += resume ? closedSec : static_cast<float>(kChunkSizeSec);
    }

#if defined(__ANDROID__)
    const auto ms = [](auto d) { return static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(d).count()); };
    __android_log_print(ANDROID_LOG_INFO, "MemegetWhisper", "transcribe %.1f s audio: %lld ms total, encode %lld ms, %zu decode steps",
                        static_cast<double>(pcmSamples.size()) / kSamplingRate,
                        ms(std::chrono::steady_clock::now() - started), ms(encodeTime), decodeSteps);
#endif
    return fullTranscript;
}

// ---------------------------------------------------------------------------
// 5. Instance Segmentation (FastSAM-s)
// ---------------------------------------------------------------------------
std::vector<FastSamItem> ModelRunner::segment(
    const std::string& modelPath,
    const std::string& imagePath,
    int maxInstances,
    float confThreshold,
    float iouThreshold) {

    std::scoped_lock lock(mutex_);
    ensureFamily(ModelFamily::SEGMENTATION);

    if (!fastsam_module_ || fastsam_path_ != modelPath) {
        fastsam_module_ = std::make_unique<Module>(
            modelPath, Module::LoadMode::MmapUseMlockIgnoreErrors);
        auto err = fastsam_module_->load();
        if (err != executorch::runtime::Error::Ok) {
            fastsam_module_.reset();
            fastsam_path_.clear();
            throw std::runtime_error("Failed to load FastSAM model: " + modelPath);
        }
        fastsam_path_ = modelPath;
    }

    cv::Mat imageBGR = rnexecutorch::image_processing::readImage(imagePath);
    if (imageBGR.empty()) {
        return {};
    }
    cv::Mat imageRGB;
    cv::cvtColor(imageBGR, imageRGB, cv::COLOR_BGR2RGB);

    const int origWidth = imageRGB.cols;
    const int origHeight = imageRGB.rows;

    auto methodMeta = fastsam_module_->method_meta("forward");
    if (!methodMeta.ok()) {
        throw std::runtime_error("FastSAM forward metadata failed: " +
                                 std::to_string(static_cast<int>(methodMeta.error())));
    }
    auto inputMeta = methodMeta->input_tensor_meta(0);
    if (!inputMeta.ok()) {
        throw std::runtime_error("FastSAM input metadata failed: " +
                                 std::to_string(static_cast<int>(inputMeta.error())));
    }
    const auto sizes = inputMeta->sizes();
    const bool rgbInput = (sizes.size() == 3 && sizes[0] == 3) ||
                          (sizes.size() == 4 && sizes[0] == 1 && sizes[1] == 3);
    if (!rgbInput || sizes[sizes.size() - 2] <= 0 || sizes[sizes.size() - 1] <= 0) {
        throw std::runtime_error("FastSAM requires a fixed CHW RGB input");
    }
    const int modelHeight = sizes[sizes.size() - 2];
    const int modelWidth = sizes[sizes.size() - 1];
    const cv::Size modelSize(modelWidth, modelHeight);
    cv::Mat resized;
    if (imageRGB.size() == modelSize) resized = imageRGB;
    else cv::resize(imageRGB, resized, modelSize);

    std::vector<float> chw = rnexecutorch::image_processing::colorMatToVector(resized);
    std::vector<int32_t> shape(sizes.begin(), sizes.end());
    TensorPtr inputTensor = from_blob(chw.data(), shape, ScalarType::Float);

    auto forwardResult = fastsam_module_->execute("forward", {EValue(*inputTensor)});
    if (!forwardResult.ok()) {
        throw std::runtime_error("FastSAM forward pass failed: " +
                                 std::to_string(static_cast<int>(forwardResult.error())));
    }

    const auto& outputs = *forwardResult;
    if (outputs.size() < 3) {
        throw std::runtime_error("FastSAM unexpected output count: expected 3 tensors");
    }

    Tensor bboxTensor = outputs[0].toTensor();   // [1, N, 4]
    Tensor scoresTensor = outputs[1].toTensor(); // [1, N, 2]
    Tensor maskTensor = outputs[2].toTensor();   // [1, N, maskH, maskW]

    int32_t numInstances = bboxTensor.size(1);
    int32_t maskH = maskTensor.size(2);
    int32_t maskW = maskTensor.size(3);

    const float* bboxData = bboxTensor.const_data_ptr<float>();
    const float* scoresData = scoresTensor.const_data_ptr<float>();
    const float* maskData = maskTensor.const_data_ptr<float>();

    const float widthRatio = static_cast<float>(origWidth) / modelWidth;
    const float heightRatio = static_cast<float>(origHeight) / modelHeight;

    struct RawCandidate {
        cv_proc::BBox bbox;
        float score;
        int32_t index;
    };

    std::vector<RawCandidate> candidates;
    for (int32_t i = 0; i < numInstances; ++i) {
        float score = scoresData[i * 2];
        if (score < confThreshold) continue;

        cv_proc::BBox bboxModel{
            bboxData[i * 4], bboxData[i * 4 + 1],
            bboxData[i * 4 + 2], bboxData[i * 4 + 3]
        };
        cv_proc::BBox bboxOriginal = bboxModel.scale(widthRatio, heightRatio);
        if (!bboxOriginal.isValid()) continue;

        candidates.push_back(RawCandidate{bboxOriginal, score, i});
    }

    // Non-maximum suppression
    auto suppressed = cv_proc::nonMaxSuppression(candidates, iouThreshold);
    if (suppressed.size() > static_cast<size_t>(maxInstances)) {
        suppressed.resize(maxInstances);
    }

    std::vector<FastSamItem> results;
    results.reserve(suppressed.size());

    for (const auto& item : suppressed) {
        if (cancelled_.load()) break;

        int32_t i = item.index;
        cv_proc::BBox bboxModel{
            bboxData[i * 4], bboxData[i * 4 + 1],
            bboxData[i * 4 + 2], bboxData[i * 4 + 3]
        };

        // Upstream BaseInstanceSegmentation::computeMaskCropRect + addPaddingToRect:
        float mx1F = bboxModel.p1.x * maskW / modelWidth;
        float my1F = bboxModel.p1.y * maskH / modelHeight;
        float mx2F = bboxModel.p2.x * maskW / modelWidth;
        float my2F = bboxModel.p2.y * maskH / modelHeight;

        int32_t mx1 = std::max(0, static_cast<int32_t>(std::floor(mx1F)));
        int32_t my1 = std::max(0, static_cast<int32_t>(std::floor(my1F)));
        int32_t mx2 = std::min(maskW, static_cast<int32_t>(std::ceil(mx2F)));
        int32_t my2 = std::min(maskH, static_cast<int32_t>(std::ceil(my2F)));
        cv::Rect cropRect(mx1, my1, std::max(1, mx2 - mx1), std::max(1, my2 - my1));

        // addPaddingToRect
        int32_t px1 = std::max(0, cropRect.x - 1);
        int32_t py1 = std::max(0, cropRect.y - 1);
        int32_t px2 = std::min(maskW, cropRect.x + cropRect.width + 1);
        int32_t py2 = std::min(maskH, cropRect.y + cropRect.height + 1);
        cv::Rect paddedCropRect(px1, py1, std::max(1, px2 - px1), std::max(1, py2 - py1));

        cv::Mat logitsMat(maskH, maskW, CV_32FC1, const_cast<float*>(maskData + (i * maskH * maskW)));
        cv::Mat cropped = logitsMat(paddedCropRect);
        cv::Mat probMat = rnexecutorch::image_processing::applySigmoid(cropped);

        // warpToOriginalResolution with exact affine matrix M
        float scaleX = static_cast<float>(origWidth) / maskW;
        float scaleY = static_cast<float>(origHeight) / maskH;

        cv::Mat M = (cv::Mat_<float>(2, 3) << scaleX, 0.0f,
                     (paddedCropRect.x * scaleX - item.bbox.p1.x),
                     0.0f, scaleY,
                     (paddedCropRect.y * scaleY - item.bbox.p1.y));

        int outBoxW = std::max(1, static_cast<int>(std::round(item.bbox.width())));
        int outBoxH = std::max(1, static_cast<int>(std::round(item.bbox.height())));
        cv::Size bboxSize(outBoxW, outBoxH);

        cv::Mat warped;
        cv::warpAffine(probMat, warped, M, bboxSize, cv::INTER_LINEAR);

        // Threshold to binary 0/1 at 127
        cv::Mat binaryMask;
        cv::threshold(warped, binaryMask, 127.0, 1.0, cv::THRESH_BINARY);

        // Convert CV_32F to CV_8U (each byte 0 or 1)
        cv::Mat binaryMask8U;
        binaryMask.convertTo(binaryMask8U, CV_8U);

        std::vector<uint8_t> maskBytes(binaryMask8U.data, binaryMask8U.data + binaryMask8U.total());

        results.push_back(FastSamItem{
            .x = static_cast<int>(std::round(item.bbox.p1.x)),
            .y = static_cast<int>(std::round(item.bbox.p1.y)),
            .width = outBoxW,
            .height = outBoxH,
            .score = item.score,
            .mask = std::move(maskBytes)
        });
    }

    return results;
}

// ---------------------------------------------------------------------------
// 6. MiniCPM-V 4.7 Describe (llama.cpp / libmtmd)
// ---------------------------------------------------------------------------
std::string ModelRunner::describe(
    const std::string& modelPath,
    const std::string& projectorPath,
    const std::string& imagePath,
    const std::string& systemPrompt,
    const std::string& userPrompt,
    int maxTokens,
    float temperature,
    int maxImagePixels) {

    std::scoped_lock lock(mutex_);
    ensureFamily(ModelFamily::VLM);
    if (cancelled_.load()) throw std::runtime_error("Inference cancelled");

    // Decode and bound the image before loading the model, so unreadable media fails cheaply.
    cv::Mat imageBGR = rnexecutorch::image_processing::readImage(imagePath);
    int width = 0;
    int height = 0;
    fitWithinPixels(imageBGR.cols, imageBGR.rows, maxImagePixels, width, height);
    if (width != imageBGR.cols || height != imageBGR.rows) {
        cv::resize(imageBGR, imageBGR, cv::Size(width, height), 0, 0, cv::INTER_AREA);
    }
    cv::Mat imageRGB;
    cv::cvtColor(imageBGR, imageRGB, cv::COLOR_BGR2RGB);
    RgbImage image;
    image.width = imageRGB.cols;
    image.height = imageRGB.rows;
    image.pixels.resize(static_cast<size_t>(image.width) * static_cast<size_t>(image.height) * 3);
    const size_t rowBytes = static_cast<size_t>(image.width) * 3;
    for (int row = 0; row < imageRGB.rows; ++row) {
        std::memcpy(image.pixels.data() + row * rowBytes, imageRGB.ptr<uint8_t>(row), rowBytes);
    }

    if (!vlm_runner_ || !vlm_runner_->matches(modelPath, projectorPath)) {
        vlm_runner_.reset();
        vlm_runner_ = std::make_unique<LlamaVisionRunner>(
            modelPath, projectorPath, defaultInferenceThreads(), &cancelled_);
    }
    return vlm_runner_->describe(image, systemPrompt, userPrompt, maxTokens, temperature);
}

int ModelRunner::convertProjector(const std::string& sourcePath, const std::string& destinationPath) {
    std::scoped_lock lock(mutex_);
    return convertBf16TensorsToF16(sourcePath, destinationPath, &cancelled_);
}

} // namespace memeget
