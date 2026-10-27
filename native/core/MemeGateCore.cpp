#include "MemeGateCore.hpp"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <numeric>
#include <sstream>

namespace memeget::core {
namespace {

float clamp01(float x) {
    return std::clamp(x, 0.0f, 1.0f);
}

float sigmoid(float z) {
    const float c = std::clamp(z, -20.0f, 20.0f);
    return 1.0f / (1.0f + std::exp(-c));
}

std::string lowerAlnumPadded(const std::string& raw) {
    std::string out;
    out.reserve(raw.size() + 2);
    out.push_back(' ');
    bool prevSpace = true;
    for (unsigned char ch : raw) {
        if (std::isalnum(ch) || ch == ':' || ch == '>' || ch == '$' || ch == '%') {
            out.push_back(static_cast<char>(std::tolower(ch)));
            prevSpace = false;
        } else if (!prevSpace) {
            out.push_back(' ');
            prevSpace = true;
        }
    }
    if (!prevSpace) out.push_back(' ');
    return out;
}

std::string escapeD1Tokens(const std::string& text) {
    std::string out;
    out.reserve(text.size());
    for (size_t i = 0; i < text.size(); ++i) {
        if (i + 1 < text.size() && text[i] == '<' && text[i + 1] == '|') {
            out += "<\xC2\xA6";
            ++i;
        } else if (i + 1 < text.size() && text[i] == '|' && text[i + 1] == '>') {
            out += "\xC2\xA6>";
            ++i;
        } else {
            out.push_back(text[i]);
        }
    }
    return out;
}

inline float pixelLuma(const uint8_t* px, int channels) {
    if (channels >= 3) {
        return 0.299f * px[0] + 0.587f * px[1] + 0.114f * px[2];
    }
    return static_cast<float>(px[0]);
}

// Scans interior rows or columns for low-variance solid divider gutters flanked by actual visual texture.
std::vector<int> findGutterSplits(
    const uint8_t* pixels,
    int width,
    int height,
    int channels,
    bool horizontal) {
    const int primaryLen = horizontal ? height : width;
    const int crossLen = horizontal ? width : height;
    if (primaryLen < 160 || crossLen < 160) return {};

    const int start = static_cast<int>(primaryLen * 0.22f);
    const int end = static_cast<int>(primaryLen * 0.78f);
    const int step = std::max(1, crossLen / 96);

    std::vector<float> lineStd(primaryLen, 999.0f);
    std::vector<float> lineMean(primaryLen, 128.0f);

    for (int p = std::max(0, start - 10); p <= std::min(primaryLen - 1, end + 10); ++p) {
        double sum = 0.0;
        double sumSq = 0.0;
        int n = 0;
        // Trim 4% outer margin so rounded screenshot borders don't poison a genuine panel divider.
        const int cStart = static_cast<int>(crossLen * 0.04f);
        const int cEnd = static_cast<int>(crossLen * 0.96f);
        for (int c = cStart; c < cEnd; c += step) {
            const int x = horizontal ? c : p;
            const int y = horizontal ? p : c;
            const uint8_t* px = pixels + (static_cast<size_t>(y) * width + x) * channels;
            const float l = pixelLuma(px, channels);
            sum += l;
            sumSq += static_cast<double>(l) * l;
            ++n;
        }
        if (n >= 8) {
            const double mean = sum / n;
            const double var = std::max(0.0, (sumSq / n) - mean * mean);
            lineMean[p] = static_cast<float>(mean);
            lineStd[p] = static_cast<float>(std::sqrt(var));
        }
    }

    struct Band {
        int center;
        float score;
    };
    std::vector<Band> candidates;
    int p = start;
    while (p <= end) {
        // Either a crisp solid white/black/gray gutter band OR a sharp 1-2px step edge across 85% of the frame.
        if (lineStd[p] <= 7.5f) {
            int runStart = p;
            while (p <= end && lineStd[p] <= 8.5f) ++p;
            int runEnd = p - 1;
            int runWidth = runEnd - runStart + 1;
            if (runWidth >= 2 && runWidth <= std::max(24, primaryLen / 14)) {
                const int mid = (runStart + runEnd) / 2;
                const int beforeIdx = std::max(0, runStart - 6);
                const int afterIdx = std::min(primaryLen - 1, runEnd + 6);
                // Adjacent panels must carry real visual variance or luminance jump so blank backgrounds don't fake gutters.
                const float flankActivity = std::max(lineStd[beforeIdx], lineStd[afterIdx]);
                const float flankJump = std::max(
                    std::abs(lineMean[mid] - lineMean[beforeIdx]),
                    std::abs(lineMean[mid] - lineMean[afterIdx]));
                if (flankActivity >= 14.0f || flankJump >= 22.0f) {
                    candidates.push_back({mid, flankActivity + flankJump});
                }
            }
        } else {
            ++p;
        }
    }

    // Keep at most 2 well-separated splits (supporting 2-panel and 3-panel stacks cleanly).
    std::sort(candidates.begin(), candidates.end(), [](const Band& a, const Band& b) {
        return a.score > b.score;
    });
    std::vector<int> chosen;
    const int minGap = static_cast<int>(primaryLen * 0.22f);
    for (const auto& cand : candidates) {
        bool tooClose = false;
        for (int existing : chosen) {
            if (std::abs(cand.center - existing) < minGap) {
                tooClose = true;
                break;
            }
        }
        if (!tooClose) {
            chosen.push_back(cand.center);
            if (chosen.size() == 2) break;
        }
    }
    std::sort(chosen.begin(), chosen.end());
    return chosen;
}

CropRect clampCrop(int x, int y, int w, int h, int imgW, int imgH) {
    const int cx = std::clamp(x, 0, std::max(0, imgW - 1));
    const int cy = std::clamp(y, 0, std::max(0, imgH - 1));
    const int cw = std::clamp(w, 1, imgW - cx);
    const int ch = std::clamp(h, 1, imgH - cy);
    return {cx, cy, cw, ch};
}

} // namespace

PanelDetectionResult detectPanelsAndSubCrops(
    const uint8_t* pixels,
    int width,
    int height,
    int channels,
    int ocrBoxCount,
    bool allowMinerFallback) {
    PanelDetectionResult out;
    if (pixels == nullptr || width < 96 || height < 96 || channels < 1) {
        return out;
    }

    const std::vector<int> hSplits = findGutterSplits(pixels, width, height, channels, /*horizontal=*/true);
    const std::vector<int> vSplits = findGutterSplits(pixels, width, height, channels, /*horizontal=*/false);

    out.hasHorizontalGutter = !hSplits.empty();
    out.hasVerticalGutter = !vSplits.empty();

    if (out.hasHorizontalGutter || out.hasVerticalGutter) {
        std::vector<int> ys = {0};
        ys.insert(ys.end(), hSplits.begin(), hSplits.end());
        ys.push_back(height);

        std::vector<int> xs = {0};
        xs.insert(xs.end(), vSplits.begin(), vSplits.end());
        xs.push_back(width);

        for (size_t yi = 0; yi + 1 < ys.size() && out.crops.size() < 4; ++yi) {
            for (size_t xi = 0; xi + 1 < xs.size() && out.crops.size() < 4; ++xi) {
                const int pw = xs[xi + 1] - xs[xi];
                const int ph = ys[yi + 1] - ys[yi];
                if (pw >= 64 && ph >= 64) {
                    out.crops.push_back(clampCrop(xs[xi], ys[yi], pw, ph, width, height));
                }
            }
        }
        out.panelCount = std::max(1, static_cast<int>(out.crops.size()));
        if (out.crops.size() >= 2) {
            return out;
        }
        out.crops.clear();
        out.panelCount = 1;
    }

    // Elongated screenshots / comic strips: slice up to 3 square windows along the long shaft.
    const int shortSide = std::min(width, height);
    const int longSide = std::max(width, height);
    const float aspect = static_cast<float>(longSide) / static_cast<float>(shortSide);
    if (aspect >= 1.65f) {
        const int span = longSide - shortSide;
        std::vector<int> offsets = (aspect >= 2.35f)
            ? std::vector<int>{0, span / 2, span}
            : std::vector<int>{0, span};
        const bool vertical = height > width;
        for (int off : offsets) {
            out.crops.push_back(vertical
                ? clampCrop(0, off, shortSide, shortSide, width, height)
                : clampCrop(off, 0, shortSide, shortSide, width, height));
        }
        out.panelCount = static_cast<int>(out.crops.size());
        return out;
    }

    // MINER (arXiv:2609.27142) spatial sub-crops for cluttered multi-caption scenes where global pooling drowns small entities.
    if (allowMinerFallback && ocrBoxCount >= 3 && width >= 192 && height >= 192) {
        out.usedMinerSubCrops = true;
        const int cw = static_cast<int>(width * 0.62f);
        const int ch = static_cast<int>(height * 0.62f);
        out.crops.push_back(clampCrop(0, 0, cw, ch, width, height));
        out.crops.push_back(clampCrop(width - cw, 0, cw, ch, width, height));
        out.crops.push_back(clampCrop(0, height - ch, cw, ch, width, height));
        out.crops.push_back(clampCrop(width - cw, height - ch, cw, ch, width, height));
    }

    return out;
}

MemeGateStage1Output evaluateMemeGateStage1(const MemeGateStage1Input& input) {
    MemeGateStage1Output out;

    // Hard veto if Master already tossed a near-identical frame out of the library as "Not a meme".
    if (input.maxUserRejectedCos >= 0.90f) {
        out.verdict = MemeGateVerdict::REJECTED;
        out.memeProbability = 0.02f;
        out.logit = -3.9f;
        out.reason = "rejected-by-user-memory";
        return out;
    }

    // 1. Extract OCR box geometry & spacing regularity (MemeTector layout topology).
    std::vector<float> centersY;
    centersY.reserve(input.ocrBoxes.size());
    bool topCaptionBand = false;
    bool bottomCaptionBand = false;
    int totalChars = static_cast<int>(input.fullOcrText.size());

    for (const auto& box : input.ocrBoxes) {
        const float bw = std::max(0.0f, box.right - box.left);
        const float cy = 0.5f * (box.top + box.bottom);
        centersY.push_back(cy);
        if (bw >= 0.42f && cy <= 0.25f && box.text.size() >= 4) topCaptionBand = true;
        if (bw >= 0.42f && cy >= 0.75f && box.text.size() >= 4) bottomCaptionBand = true;
    }

    out.lineCount = static_cast<int>(input.ocrBoxes.size());
    if (out.lineCount == 0 && !input.fullOcrText.empty()) {
        out.lineCount = 1 + static_cast<int>(std::count(input.fullOcrText.begin(), input.fullOcrText.end(), '\n'));
    }
    out.charCount = totalChars;
    out.hasCaptionBand = topCaptionBand || bottomCaptionBand;

    if (centersY.size() >= 4) {
        std::sort(centersY.begin(), centersY.end());
        std::vector<float> gaps;
        for (size_t i = 1; i < centersY.size(); ++i) {
            const float g = centersY[i] - centersY[i - 1];
            if (g > 0.005f) gaps.push_back(g);
        }
        if (gaps.size() >= 3) {
            const float mean = std::accumulate(gaps.begin(), gaps.end(), 0.0f) / gaps.size();
            float var = 0.0f;
            for (float g : gaps) var += (g - mean) * (g - mean);
            var /= gaps.size();
            out.verticalSpacingCv = (mean > 1e-4f) ? (std::sqrt(var) / mean) : 0.0f;
        }
    }

    // 2. Lexical signals: separate receipts/invoices/OS-chrome from internet meme dialect.
    const std::string padded = lowerAlnumPadded(input.fullOcrText);
    static const char* kReceiptCues[] = {
        " subtotal ", " sales tax ", " total ", " invoice ", " order number ", " visa ",
        " mastercard ", " balance due ", " amount paid ", " tracking number ", " receipt ",
        " qty ", " unit price ", " billing ", " routing number ", " account summary "
    };
    int receiptHits = 0;
    for (const char* cue : kReceiptCues) {
        if (padded.find(cue) != std::string::npos) ++receiptHits;
    }
    int currencyDigits = 0;
    for (size_t i = 0; i + 1 < input.fullOcrText.size(); ++i) {
        if ((input.fullOcrText[i] == '$' || input.fullOcrText[i] == '%') &&
            std::isdigit(static_cast<unsigned char>(input.fullOcrText[i + 1]))) {
            ++currencyDigits;
        }
    }
    out.receiptDocScore = clamp01(0.30f * receiptHits + 0.12f * currencyDigits +
        ((out.lineCount >= 14 && out.verticalSpacingCv < 0.35f) ? 0.45f : 0.0f));

    static const char* kMundaneUiCues[] = {
        " settings ", " bluetooth ", " cellular ", " battery usage ", " verification code ",
        " two factor ", " flight ", " boarding pass ", " calendar ", " alarm ", " screen time ",
        " storage ", " wi fi ", " password ", " sign in ", " starting service ", " intent ",
        " customize toolbar ", " clean energy ", " upgraded ", " newly installed ", " node pubkey ",
        " micromodal ", " lock screen ", " supplement ", " chewables ", " mylar packaging ",
        " appetizers ", " sushi ", " faq ", " hiring ", " alldebrid "
    };
    int uiHits = 0;
    for (const char* cue : kMundaneUiCues) {
        if (padded.find(cue) != std::string::npos) ++uiHits;
    }
    out.mundaneUiScore = clamp01(0.32f * uiHits);
    static const char* kMemeCues[] = {
        " pov:", " pov ", " nobody:", " me:", " me when ", " when you ", " mfw ", " tfw ",
        " bro ", " literally me ", " be like ", " >be me ", " wojak ", " pepe ", " cooked ",
        " joever ", " copium ", " real ", " shitpost ", " mf ", " bruh ", " lmao "
    };
    int memeHits = 0;
    for (const char* cue : kMemeCues) {
        if (padded.find(cue) != std::string::npos) ++memeHits;
    }
    out.memeLexicalScore = clamp01(0.38f * memeHits + (out.hasCaptionBand ? 0.35f : 0.0f));

    // 3. Fuse visual concept alignment, ordinary-photo negative anchor margin, panel grid, and text topology.
    float logit = -0.55f;

    // Strong OpenVocabConcepts match (6,028 meme/reaction/pop-culture prototypes)
    if (input.maxOpenVocabHybridCos >= 0.70f) {
        logit += 5.5f * (input.maxOpenVocabHybridCos - 0.67f);
    }
    if (input.maxOpenVocabZScore >= 3.2f) {
        logit += 0.45f * std::min(4.0f, input.maxOpenVocabZScore - 2.6f);
    }
    if (input.maxCuratedMargin >= 0.11f) {
        logit += 6.5f * (input.maxCuratedMargin - 0.09f);
    }

    // Ordinary natural camera photo penalty (landscapes, pets, food, unedited portraits, dev/settings UI)
    if (input.maxOrdinaryPhotoAnchorCos > 0.22f) {
        const float photoExcess = input.maxOrdinaryPhotoAnchorCos - std::max(0.21f, input.maxOpenVocabHybridCos * 0.50f);
        if (photoExcess > 0.0f) {
            logit -= 6.4f * photoExcess;
        }
    }
    if (input.maxCuratedMargin <= -0.025f && out.memeLexicalScore < 0.20f && input.maxOpenVocabHybridCos < 0.70f) {
        logit += 8.0f * (input.maxCuratedMargin + 0.015f);
    }

    // Multi-panel comic gutters indicate meme composition ONLY on non-tall-phone-UI aspect ratios
    // (tall 9:19.5 phone screenshots have UI horizontal dividers that mimic comic gutters!).
    const bool isTallPhoneUi = input.isScreenshotHint ||
        (input.width > 0 && static_cast<float>(input.height) > static_cast<float>(input.width) * 1.72f);
    if (input.hasPanelGutter && input.detectedPanelCount >= 2 &&
        (!isTallPhoneUi || out.memeLexicalScore >= 0.35f || input.maxCuratedMargin >= 0.05f)) {
        logit += 1.05f;
    }
    logit += 1.45f * out.memeLexicalScore;
    // Short punchy overlay text (1-6 lines) vs dense receipt/document tables
    if (out.lineCount >= 1 && out.lineCount <= 6 && totalChars >= 5 && totalChars <= 260 && out.receiptDocScore < 0.25f) {
        logit += 0.55f;
    }
    logit -= 2.65f * out.receiptDocScore;
    logit -= 1.65f * out.mundaneUiScore;

    // Short video clips (<45s) with reaction/concept resonance lean meme; long multi-minute camera videos lean personal.
    if (input.isVideo) {
        if (input.durationMs > 0 && input.durationMs <= 30000) logit += 0.25f;
        else if (input.durationMs >= 120000) logit -= 0.95f;
    }

    out.logit = logit;
    out.memeProbability = sigmoid(logit);

    if (out.memeProbability >= 0.70f) {
        out.verdict = MemeGateVerdict::ACCEPTED;
        out.reason = "stage1-accepted-meme";
    } else if (out.memeProbability <= 0.24f) {
        out.verdict = MemeGateVerdict::REJECTED;
        out.reason = out.receiptDocScore >= 0.45f ? "stage1-rejected-receipt-or-doc" : "stage1-rejected-ordinary-photo";
    } else if (totalChars >= 12 || (out.lineCount >= 2 && totalChars >= 8)) {
        out.verdict = MemeGateVerdict::UNCERTAIN_TEXT;
        out.reason = "stage1-uncertain-text-route-d1";
    } else {
        out.verdict = MemeGateVerdict::UNCERTAIN_VISUAL;
        out.reason = "stage1-uncertain-visual-route-minicpm";
    }
    return out;
}

std::string renderD1MemeGatePrompt(
    const MemeGateStage1Input& input,
    const MemeGateStage1Output& stage1) {
    // LiquidAI/d1-omni-600M exact System-1 decision layout (<|startoftext|><|reserved_7|>state<|reserved_8|>instructions<|reserved_9|><|mask|>opt...)
    std::ostringstream oss;
    oss << "<|startoftext|><|reserved_7|>";
    oss << "media_kind: " << (input.isVideo ? "video" : "image")
        << "\ndimensions: " << input.width << "x" << input.height
        << "\npanel_count: " << input.detectedPanelCount
        << " (gutter=" << (input.hasPanelGutter ? "yes" : "no") << ")"
        << "\ncaption_band: " << (stage1.hasCaptionBand ? "yes" : "no")
        << "\nocr_lines: " << stage1.lineCount
        << "\nvisual_concept_cos: " << stage1.memeProbability
        << "\nordinary_photo_cos: " << input.maxOrdinaryPhotoAnchorCos
        << "\nreceipt_doc_score: " << stage1.receiptDocScore
        << "\nmundane_ui_score: " << stage1.mundaneUiScore;

    if (!input.topCandidateLabels.empty()) {
        oss << "\nvisual_candidates: ";
        for (size_t i = 0; i < input.topCandidateLabels.size() && i < 6; ++i) {
            if (i > 0) oss << ", ";
            oss << escapeD1Tokens(input.topCandidateLabels[i]);
        }
    }
    if (!input.fullOcrText.empty()) {
        std::string clipped = input.fullOcrText.substr(0, std::min<size_t>(input.fullOcrText.size(), 900));
        oss << "\nocr_text:\n" << escapeD1Tokens(clipped);
    }

    oss << "<|reserved_8|>"
        << "Decide whether this camera-roll item is an internet meme, reaction image, humorous social screenshot, "
        << "or shitpost that belongs in a meme library, versus a mundane personal photo, pet/nature/room snapshot, "
        << "product label, menu, receipt, developer/terminal log, or ordinary app/settings/lockscreen screenshot."
        << "<|reserved_9|>"
        << "<|mask|> meme: Internet meme, image macro, reaction image, comic panel, ironic shitpost, or funny social post screenshot"
        << "<|reserved_10|><|reserved_9|>"
        << "<|mask|> non_meme: Ordinary camera photo, pet/nature/room snapshot, product box, menu, receipt, dev log, or mundane utility screenshot"
        << "<|reserved_10|><|reserved_11|>";
    return oss.str();
}

} // namespace memeget::core
