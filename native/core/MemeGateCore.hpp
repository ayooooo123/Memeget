#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace memeget::core {

// Every text box her tongue licks off the frame, normalized to [0, 1] so phone resolutions never lie.
struct OcrBox {
    float left = 0.0f;
    float top = 0.0f;
    float right = 0.0f;
    float bottom = 0.0f;
    float confidence = 1.0f;
    std::string text;
};

struct CropRect {
    int x = 0;
    int y = 0;
    int width = 0;
    int height = 0;
};

struct PanelDetectionResult {
    int panelCount = 1;
    bool hasHorizontalGutter = false;
    bool hasVerticalGutter = false;
    bool usedMinerSubCrops = false;
    std::vector<CropRect> crops;
};

enum class MemeGateVerdict : int32_t {
    REJECTED = 0,
    ACCEPTED = 1,
    UNCERTAIN_TEXT = 2,
    UNCERTAIN_VISUAL = 3,
};

struct MemeGateStage1Input {
    int width = 0;
    int height = 0;
    bool isVideo = false;
    int64_t durationMs = 0;
    bool isScreenshotHint = false;

    // Visual embedding signals against 6,028 OpenVocabConcepts, curated prompts, and ordinary-photo negative anchors.
    float maxOpenVocabHybridCos = 0.0f;
    float maxOpenVocabZScore = -4.0f;
    float maxCuratedMargin = 0.0f;
    float maxOrdinaryPhotoAnchorCos = 0.0f;
    float maxUserRejectedCos = -1.0f;

    // Panel layout signal from detectPanelsAndSubCrops.
    int detectedPanelCount = 1;
    bool hasPanelGutter = false;

    std::vector<OcrBox> ocrBoxes;
    std::string fullOcrText;
    std::vector<std::string> topCandidateLabels;
};

struct MemeGateStage1Output {
    MemeGateVerdict verdict = MemeGateVerdict::REJECTED;
    float memeProbability = 0.0f;
    float logit = 0.0f;

    // Layout & lexical diagnostics fed straight into d1-omni when text screenshots land on the fence.
    int lineCount = 0;
    int charCount = 0;
    float verticalSpacingCv = 1.0f;
    bool hasCaptionBand = false;
    float receiptDocScore = 0.0f;
    float mundaneUiScore = 0.0f;
    float memeLexicalScore = 0.0f;
    std::string reason;
};

// Rips multi-panel comic gutters (2-panel, 3-panel, 2x2 Drake/Wojak grids), elongated strips, and MINER
// cluttered sub-crops open so panel memes stop collapsing into one muddy global vector.
PanelDetectionResult detectPanelsAndSubCrops(
    const uint8_t* pixels,
    int width,
    int height,
    int channels = 3,
    int ocrBoxCount = 0,
    bool allowMinerFallback = true);

// Sub-millisecond Stage-1 camera-roll gate: swallows real memes & textless reactions, spits out receipts/docs/selfies,
// and routes genuine edge cases cleanly to d1-omni (text) or MiniCPM-V (pixels).
MemeGateStage1Output evaluateMemeGateStage1(const MemeGateStage1Input& input);

// Formats the exact LiquidAI/d1-omni-600M (<|reserved_7|>...<|mask|>) System-1 decision prompt for Stage-2 text gating.
std::string renderD1MemeGatePrompt(
    const MemeGateStage1Input& input,
    const MemeGateStage1Output& stage1);

} // namespace memeget::core
