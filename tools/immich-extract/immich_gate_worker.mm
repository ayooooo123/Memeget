#import <CoreGraphics/CoreGraphics.h>
#import <Foundation/Foundation.h>
#import <ImageIO/ImageIO.h>
#import <Vision/Vision.h>

#include <atomic>
#include <cmath>
#include <iostream>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include "KnowledgeCore.hpp"
#include "LlamaDecisionRunner.hpp"
#include "LlamaVisionRunner.hpp"
#include "MemeGateCore.hpp"
#include "ProjectorConversion.hpp"

namespace {

std::string jsonEscape(const std::string &s) {
    std::ostringstream o;
    for (unsigned char c : s) {
        switch (c) {
            case '"': o << "\\\""; break;
            case '\\': o << "\\\\"; break;
            case '\b': o << "\\b"; break;
            case '\f': o << "\\f"; break;
            case '\n': o << "\\n"; break;
            case '\r': o << "\\r"; break;
            case '\t': o << "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    o << buf;
                } else {
                    o << static_cast<char>(c);
                }
        }
    }
    return o.str();
}

CGImageRef loadCGImage(const std::string &path) {
    NSString *nsPath = [NSString stringWithUTF8String:path.c_str()];
    if (!nsPath) return nullptr;
    NSURL *url = [NSURL fileURLWithPath:nsPath];
    CGImageSourceRef src = CGImageSourceCreateWithURL((__bridge CFURLRef)url, nullptr);
    if (!src) return nullptr;
    NSDictionary *opts = @{
        (__bridge NSString *)kCGImageSourceCreateThumbnailFromImageAlways: @YES,
        (__bridge NSString *)kCGImageSourceCreateThumbnailWithTransform: @YES,
        (__bridge NSString *)kCGImageSourceThumbnailMaxPixelSize: @960
    };
    CGImageRef img = CGImageSourceCreateThumbnailAtIndex(src, 0, (__bridge CFDictionaryRef)opts);
    CFRelease(src);
    return img;
}

bool extractPackedRgb(CGImageRef cgImg, int maxPixels, std::vector<uint8_t> &outRgb, int &outW, int &outH) {
    if (!cgImg) return false;
    const int sw = std::max(1, static_cast<int>(CGImageGetWidth(cgImg)));
    const int sh = std::max(1, static_cast<int>(CGImageGetHeight(cgImg)));
    const double area = static_cast<double>(sw) * sh;
    const double scale = area > maxPixels ? std::sqrt(static_cast<double>(maxPixels) / area) : 1.0;
    outW = std::max(1, static_cast<int>(std::floor(sw * scale)));
    outH = std::max(1, static_cast<int>(std::floor(sh * scale)));

    std::vector<uint8_t> rgba(static_cast<size_t>(outW * outH * 4), 0);
    CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
    CGContextRef ctx = CGBitmapContextCreate(
        rgba.data(), outW, outH, 8, outW * 4, cs,
        kCGImageAlphaNoneSkipLast | kCGBitmapByteOrder32Big);
    CGColorSpaceRelease(cs);
    if (!ctx) return false;
    CGContextSetInterpolationQuality(ctx, kCGInterpolationHigh);
    CGContextDrawImage(ctx, CGRectMake(0, 0, outW, outH), cgImg);
    CGContextRelease(ctx);

    outRgb.resize(static_cast<size_t>(outW * outH * 3));
    for (size_t p = 0; p < static_cast<size_t>(outW * outH); ++p) {
        outRgb[p * 3] = rgba[p * 4];
        outRgb[p * 3 + 1] = rgba[p * 4 + 1];
        outRgb[p * 3 + 2] = rgba[p * 4 + 2];
    }
    return true;
}

void runAppleVisionOcr(CGImageRef cgImg, std::vector<memeget::core::OcrBox> &boxes, std::string &fullText) {
    boxes.clear();
    fullText.clear();
    if (!cgImg) return;

    __block std::vector<memeget::core::OcrBox> found;
    VNRecognizeTextRequest *req = [[VNRecognizeTextRequest alloc] initWithCompletionHandler:^(VNRequest *request, NSError * _Nullable) {
        for (VNRecognizedTextObservation *obs in request.results) {
            VNRecognizedText *top = [[obs topCandidates:1] firstObject];
            if (!top) continue;
            NSString *trimmed = [top.string stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
            if (trimmed.length == 0) continue;
            CGRect bb = obs.boundingBox;
            memeget::core::OcrBox box;
            box.left = std::clamp(static_cast<float>(bb.origin.x), 0.0f, 1.0f);
            box.right = std::clamp(static_cast<float>(bb.origin.x + bb.size.width), 0.0f, 1.0f);
            box.top = std::clamp(static_cast<float>(1.0 - (bb.origin.y + bb.size.height)), 0.0f, 1.0f);
            box.bottom = std::clamp(static_cast<float>(1.0 - bb.origin.y), 0.0f, 1.0f);
            box.confidence = top.confidence;
            box.text = [trimmed UTF8String];
            found.push_back(std::move(box));
        }
    }];
    req.recognitionLevel = VNRequestTextRecognitionLevelAccurate;
    req.usesLanguageCorrection = YES;

    VNImageRequestHandler *handler = [[VNImageRequestHandler alloc] initWithCGImage:cgImg options:@{}];
    [handler performRequests:@[req] error:nil];

    std::sort(found.begin(), found.end(), [](const auto &a, const auto &b) {
        if (std::abs(a.top - b.top) > 0.035f) return a.top < b.top;
        return a.left < b.left;
    });
    std::ostringstream oss;
    for (size_t i = 0; i < found.size(); ++i) {
        if (i > 0) oss << "\n";
        oss << found[i].text;
    }
    fullText = oss.str();
    boxes = std::move(found);
}

} // namespace

// Persistent NDJSON Apple Silicon worker executing the exact shared C++20 3-Stage Meme Gate
// (Apple Vision ANE OCR + Gutter Detector + 6,028 OpenVocabConcepts + PairwiseVerifier + d1-omni Metal + MiniCPM-V Metal).
int main(int argc, char **argv) {
    @autoreleasepool {
        std::string openVocabPath;
        std::string verifierPath;
        std::string anchorsPath;
        std::string d1ModelPath;
        std::string vlmModelPath;
        std::string vlmProjPath;

        for (int i = 1; i + 1 < argc; i += 2) {
            std::string flag = argv[i];
            std::string val = argv[i + 1];
            if (flag == "--open-vocab") openVocabPath = val;
            else if (flag == "--verifier") verifierPath = val;
            else if (flag == "--anchors") anchorsPath = val;
            else if (flag == "--d1-model") d1ModelPath = val;
            else if (flag == "--vlm-model") vlmModelPath = val;
            else if (flag == "--vlm-proj") vlmProjPath = val;
        }

        memeget::core::OpenVocabIndex openVocab;
        if (!openVocabPath.empty()) openVocab = memeget::core::OpenVocabIndex::loadFromFile(openVocabPath);

        memeget::core::PairwiseVerifierCore verifier;
        if (!verifierPath.empty()) verifier = memeget::core::PairwiseVerifierCore::loadFromFile(verifierPath);
        memeget::core::MemeGateAnchors gateAnchors;
        if (!anchorsPath.empty()) gateAnchors = memeget::core::MemeGateAnchors::loadFromFile(anchorsPath);

        std::atomic<bool> cancelled{false};
        std::unique_ptr<memeget::core::LlamaDecisionRunner> d1Runner;
        std::unique_ptr<memeget::LlamaVisionRunner> vlmRunner;

        std::string line;
        while (std::getline(std::cin, line)) {
            @autoreleasepool {
                if (line.empty()) continue;
                NSData *jsonData = [NSData dataWithBytes:line.data() length:line.size()];
                NSDictionary *cmd = [NSJSONSerialization JSONObjectWithData:jsonData options:0 error:nil];
                if (![cmd isKindOfClass:[NSDictionary class]]) continue;

                NSString *op = cmd[@"op"] ?: @"stage1";
                if ([op isEqualToString:@"convert_proj"]) {
                    std::string src = [cmd[@"src"] UTF8String];
                    std::string dst = [cmd[@"dst"] UTF8String];
                    int n = memeget::convertBf16TensorsToF16(src, dst, &cancelled);
                    std::cout << "{\"ok\":true,\"converted\":" << n << "}\n" << std::flush;
                    continue;
                }

                if ([op isEqualToString:@"d1_gate"]) {
                    std::string prompt = [(cmd[@"prompt"] ?: @"") UTF8String];
                    if (d1ModelPath.empty()) {
                        std::cout << "{\"ok\":false,\"error\":\"no d1 model\"}\n" << std::flush;
                        continue;
                    }
                    try {
                        vlmRunner.reset();
                        if (!d1Runner) {
                            d1Runner = std::make_unique<memeget::core::LlamaDecisionRunner>(d1ModelPath, 6, &cancelled);
                        }
                        const auto res = d1Runner->evaluateMemeGate(prompt);
                        std::cout << "{\"ok\":true,\"isMeme\":" << (res.isMeme ? "true" : "false")
                                  << ",\"prob\":" << res.memeProbability
                                  << ",\"evalMs\":" << res.evalMs << "}\n" << std::flush;
                    } catch (const std::exception &e) {
                        std::cout << "{\"ok\":false,\"error\":\"" << jsonEscape(e.what()) << "\"}\n" << std::flush;
                    }
                    continue;
                }

                if ([op isEqualToString:@"vlm_gate"]) {
                    std::string imgPath = [(cmd[@"path"] ?: @"") UTF8String];
                    std::string ocrHint = [(cmd[@"ocr"] ?: @"") UTF8String];
                    std::string hints = [(cmd[@"hints"] ?: @"") UTF8String];
                    CGImageRef cgImg = loadCGImage(imgPath);
                    if (!cgImg || vlmModelPath.empty() || vlmProjPath.empty()) {
                        if (cgImg) CGImageRelease(cgImg);
                        std::cout << "{\"ok\":false,\"error\":\"missing image or vlm model\"}\n" << std::flush;
                        continue;
                    }
                    try {
                        d1Runner.reset();
                        if (!vlmRunner) {
                            vlmRunner = std::make_unique<memeget::LlamaVisionRunner>(vlmModelPath, vlmProjPath, 6, &cancelled);
                        }
                        memeget::RgbImage rgb;
                        extractPackedRgb(cgImg, 448 * 448, rgb.pixels, rgb.width, rgb.height);
                        CGImageRelease(cgImg);
                        const auto res = vlmRunner->gateAndDescribe(rgb, ocrHint, hints, 140);
                        std::cout << "{\"ok\":true,\"isMeme\":" << (res.isMeme ? "true" : "false")
                                  << ",\"prob\":" << res.memeProbability
                                  << ",\"tokens\":" << res.generatedTokens
                                  << ",\"ms\":" << res.totalMs
                                  << ",\"description\":\"" << jsonEscape(res.description) << "\"}\n" << std::flush;
                    } catch (const std::exception &e) {
                        std::cout << "{\"ok\":false,\"error\":\"" << jsonEscape(e.what()) << "\"}\n" << std::flush;
                    }
                    continue;
                }

                // Default op == "stage1"
                std::string imgPath = [(cmd[@"path"] ?: @"") UTF8String];
                const bool isVideo = [cmd[@"isVideo"] boolValue];
                const int64_t durationMs = [cmd[@"durationMs"] longLongValue];
                const bool isScreenshot = [cmd[@"isScreenshot"] boolValue];
                const float ordinaryAnchorCos = [cmd[@"ordinaryAnchorCos"] floatValue];

                std::vector<float> vec(512, 0.0f);
                NSArray *vecArr = cmd[@"vec"];
                bool hasVec = ([vecArr isKindOfClass:[NSArray class]] && vecArr.count == 512);
                if (hasVec) {
                    for (int i = 0; i < 512; ++i) vec[i] = [vecArr[i] floatValue];
                }

                CGImageRef cgImg = loadCGImage(imgPath);
                if (!cgImg) {
                    std::cout << "{\"ok\":false,\"error\":\"unreadable image\"}\n" << std::flush;
                    continue;
                }

                memeget::core::MemeGateStage1Input in;
                in.width = static_cast<int>(CGImageGetWidth(cgImg));
                in.height = static_cast<int>(CGImageGetHeight(cgImg));
                in.isVideo = isVideo;
                in.durationMs = durationMs;
                in.isScreenshotHint = isScreenshot;
                if (ordinaryAnchorCos > 0.0f) {
                    in.maxOrdinaryPhotoAnchorCos = ordinaryAnchorCos;
                } else if (hasVec && !gateAnchors.empty()) {
                    in.maxOrdinaryPhotoAnchorCos = gateAnchors.maxOrdinaryPhotoCos(vec.data());
                    in.maxCuratedMargin = gateAnchors.maxMemeAnchorMargin(vec.data());
                }
                runAppleVisionOcr(cgImg, in.ocrBoxes, in.fullOcrText);

                std::vector<uint8_t> packedRgb;
                int rw = 0, rh = 0;
                if (extractPackedRgb(cgImg, 640 * 640, packedRgb, rw, rh)) {
                    const auto det = memeget::core::detectPanelsAndSubCrops(
                        packedRgb.data(), rw, rh, 3, static_cast<int>(in.ocrBoxes.size()), true);
                    in.detectedPanelCount = det.panelCount;
                    in.hasPanelGutter = det.hasHorizontalGutter || det.hasVerticalGutter;
                }
                CGImageRelease(cgImg);

                std::ostringstream tagsJson;
                tagsJson << "[";
                bool firstTag = true;
                if (hasVec && !openVocab.empty()) {
                    const auto summary = openVocab.evaluateImage(vec.data(), in.fullOcrText, 6);
                    in.maxOpenVocabHybridCos = summary.maxHybridCos;
                    in.maxOpenVocabZScore = summary.maxEntityZScore;
                    for (const auto &h : summary.hits) {
                        in.topCandidateLabels.push_back(h.label);
                        if (h.fromZTop1 || h.posCos >= 0.70f) {
                            if (!firstTag) tagsJson << ",";
                            firstTag = false;
                            tagsJson << "{\"label\":\"" << jsonEscape(h.label)
                                     << "\",\"category\":\"" << jsonEscape(h.category)
                                     << "\",\"score\":" << h.posCos << "}";
                        }
                    }
                }
                tagsJson << "]";

                const auto s1 = memeget::core::evaluateMemeGateStage1(in);
                std::string d1Prompt = (s1.verdict == memeget::core::MemeGateVerdict::UNCERTAIN_TEXT)
                    ? memeget::core::renderD1MemeGatePrompt(in, s1)
                    : "";

                std::cout << "{\"ok\":true,\"verdict\":" << static_cast<int>(s1.verdict)
                          << ",\"prob\":" << s1.memeProbability
                          << ",\"panelCount\":" << in.detectedPanelCount
                          << ",\"hasGutter\":" << (in.hasPanelGutter ? "true" : "false")
                          << ",\"reason\":\"" << jsonEscape(s1.reason) << "\""
                          << ",\"ocr\":\"" << jsonEscape(in.fullOcrText) << "\""
                          << ",\"d1Prompt\":\"" << jsonEscape(d1Prompt) << "\""
                          << ",\"tags\":" << tagsJson.str() << "}\n" << std::flush;
            }
        }
    }
    return 0;
}
