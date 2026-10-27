#import "MemegetCoreBridge.h"

#include <atomic>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_set>
#include <vector>

#include <executorch/extension/module/module.h>
#include <executorch/extension/tensor/tensor_ptr_maker.h>
#include <executorch/runtime/platform/runtime.h>

#include "KnowledgeCore.hpp"
#include "LlamaDecisionRunner.hpp"
#include "LlamaVisionRunner.hpp"
#include "MemeGateCore.hpp"
#include "ProjectorConversion.hpp"
#include "ResidentSearchCore.hpp"

@implementation MGPanelCrop
@end

@implementation MGPanelDetection
@end

@implementation MGOcrBoxInput
@end

@implementation MGConceptHit
@end

@implementation MGStage1Outcome
@end

@implementation MGD1Outcome
@end

@implementation MGVlmGateOutcome
@end

namespace {
std::once_flag gEtInitOnce;

NSError *makeBridgeError(NSInteger code, const std::string &msg) {
    NSString *ns = [NSString stringWithUTF8String:msg.c_str()] ?: @"Native C++ bridge error";
    return [NSError errorWithDomain:@"com.memeget.corebridge"
                               code:code
                           userInfo:@{NSLocalizedDescriptionKey: ns}];
}
} // namespace

@implementation MemegetCoreBridge {
    std::mutex _engineMutex;
    std::atomic<bool> _cancelled;

    memeget::core::OpenVocabIndex _openVocab;
    memeget::core::PairwiseVerifierCore _verifier;
    memeget::core::MemeGateAnchors _gateAnchors;
    std::vector<float> _cslsBank;

    std::unique_ptr<executorch::extension::module::Module> _s2ImageModule;
    std::string _s2ImagePath;

    std::unique_ptr<executorch::extension::module::Module> _s2TextModule;
    std::string _s2TextPath;

    std::unique_ptr<memeget::core::LlamaDecisionRunner> _d1Runner;
    std::unique_ptr<memeget::LlamaVisionRunner> _vlmRunner;
}

- (instancetype)initWithOpenVocabPath:(nullable NSString *)openVocabPath
                         verifierPath:(nullable NSString *)verifierPath {
    return [self initWithOpenVocabPath:openVocabPath verifierPath:verifierPath anchorsPath:nil];
}

- (instancetype)initWithOpenVocabPath:(nullable NSString *)openVocabPath
                         verifierPath:(nullable NSString *)verifierPath
                          anchorsPath:(nullable NSString *)anchorsPath {
    self = [super init];
    if (self) {
        std::call_once(gEtInitOnce, [] {
            executorch::runtime::runtime_init();
        });
        _cancelled.store(false, std::memory_order_relaxed);
        if (openVocabPath != nil && openVocabPath.length > 0) {
            try {
                _openVocab = memeget::core::OpenVocabIndex::loadFromFile([openVocabPath UTF8String]);
                _cslsBank = _openVocab.buildCslsQueryBank(256);
            } catch (...) {
            }
        }
        if (verifierPath != nil && verifierPath.length > 0) {
            try {
                _verifier = memeget::core::PairwiseVerifierCore::loadFromFile([verifierPath UTF8String]);
            } catch (...) {
            }
        }
        if (anchorsPath != nil && anchorsPath.length > 0) {
            try {
                _gateAnchors = memeget::core::MemeGateAnchors::loadFromFile([anchorsPath UTF8String]);
            } catch (...) {
            }
        }
    }
    return self;
}

- (void)cancelActiveWork {
    _cancelled.store(true, std::memory_order_relaxed);
}

- (void)resetCancel {
    _cancelled.store(false, std::memory_order_relaxed);
}

- (void)unloadHeavyModels {
    _cancelled.store(true, std::memory_order_relaxed);
    std::scoped_lock lock(_engineMutex);
    _s2ImageModule.reset();
    _s2ImagePath.clear();
    _d1Runner.reset();
    _vlmRunner.reset();
}

- (void)unloadAllModels {
    _cancelled.store(true, std::memory_order_relaxed);
    std::scoped_lock lock(_engineMutex);
    _s2ImageModule.reset();
    _s2ImagePath.clear();
    _s2TextModule.reset();
    _s2TextPath.clear();
    _d1Runner.reset();
    _vlmRunner.reset();
}

- (nullable NSArray<NSNumber *> *)embedImagePlanarRgb:(const float *)planarRgb256
                                            modelPath:(NSString *)modelPath
                                                error:(NSError * _Nullable * _Nullable)error {
    if (planarRgb256 == nullptr || modelPath.length == 0) {
        if (error) *error = makeBridgeError(1, "Invalid image tensor or model path");
        return nil;
    }
    try {
        std::scoped_lock lock(_engineMutex);
        _d1Runner.reset();
        _vlmRunner.reset();

        const std::string mPath([modelPath UTF8String]);
        if (!_s2ImageModule || _s2ImagePath != mPath) {
            _s2ImageModule = std::make_unique<executorch::extension::module::Module>(
                mPath, executorch::extension::module::Module::LoadMode::MmapUseMlockIgnoreErrors);
            if (_s2ImageModule->load() != executorch::runtime::Error::Ok) {
                _s2ImageModule.reset();
                _s2ImagePath.clear();
                if (error) *error = makeBridgeError(2, "Failed loading MobileCLIP-S2 image .pte");
                return nil;
            }
            _s2ImagePath = mPath;
        }

        std::vector<float> buf(planarRgb256, planarRgb256 + (3 * 256 * 256));
        auto tensor = executorch::extension::from_blob(
            buf.data(), {1, 3, 256, 256}, executorch::aten::ScalarType::Float);
        auto res = _s2ImageModule->forward(tensor);
        if (!res.ok() || res->empty() || !res->at(0).isTensor()) {
            if (error) *error = makeBridgeError(3, "MobileCLIP-S2 image forward failed");
            return nil;
        }
        const auto &outTensor = res->at(0).toTensor();
        const float *raw = outTensor.const_data_ptr<float>();
        const int dim = static_cast<int>(outTensor.numel());
        if (dim != 512 || raw == nullptr) {
            if (error) *error = makeBridgeError(4, "Unexpected MobileCLIP-S2 output dimension");
            return nil;
        }
        double normSq = 0.0;
        for (int i = 0; i < 512; ++i) normSq += static_cast<double>(raw[i]) * raw[i];
        const float invNorm = normSq > 1e-12 ? static_cast<float>(1.0 / std::sqrt(normSq)) : 1.0f;
        NSMutableArray<NSNumber *> *arr = [NSMutableArray arrayWithCapacity:512];
        for (int i = 0; i < 512; ++i) {
            [arr addObject:@(raw[i] * invNorm)];
        }
        return arr;
    } catch (const std::exception &e) {
        if (error) *error = makeBridgeError(5, e.what());
        return nil;
    }
}

- (nullable NSArray<NSNumber *> *)embedTextTokens77:(const int64_t *)tokenIds77
                                          modelPath:(NSString *)modelPath
                                              error:(NSError * _Nullable * _Nullable)error {
    if (tokenIds77 == nullptr || modelPath.length == 0) {
        if (error) *error = makeBridgeError(10, "Invalid text tokens or model path");
        return nil;
    }
    try {
        std::scoped_lock lock(_engineMutex);
        _d1Runner.reset();
        _vlmRunner.reset();

        const std::string mPath([modelPath UTF8String]);
        if (!_s2TextModule || _s2TextPath != mPath) {
            _s2TextModule = std::make_unique<executorch::extension::module::Module>(
                mPath, executorch::extension::module::Module::LoadMode::MmapUseMlockIgnoreErrors);
            if (_s2TextModule->load() != executorch::runtime::Error::Ok) {
                _s2TextModule.reset();
                _s2TextPath.clear();
                if (error) *error = makeBridgeError(11, "Failed loading MobileCLIP-S2 text .pte");
                return nil;
            }
            _s2TextPath = mPath;
        }

        std::vector<int64_t> ids(tokenIds77, tokenIds77 + 77);
        std::vector<int64_t> mask(77, 0);
        for (int i = 0; i < 77; ++i) mask[i] = (ids[i] != 0) ? 1 : 0;

        auto tokTensor = executorch::extension::from_blob(
            ids.data(), {1, 77}, executorch::aten::ScalarType::Long);
        auto maskTensor = executorch::extension::from_blob(
            mask.data(), {1, 77}, executorch::aten::ScalarType::Long);

        std::vector<executorch::runtime::EValue> inputs;
        inputs.emplace_back(tokTensor);
        inputs.emplace_back(maskTensor);

        auto res = _s2TextModule->forward(inputs);
        if (!res.ok() || res->empty() || !res->at(0).isTensor()) {
            if (error) *error = makeBridgeError(12, "MobileCLIP-S2 text forward failed");
            return nil;
        }
        const auto &outTensor = res->at(0).toTensor();
        const float *raw = outTensor.const_data_ptr<float>();
        const int dim = static_cast<int>(outTensor.numel());
        if (dim != 512 || raw == nullptr) {
            if (error) *error = makeBridgeError(13, "Unexpected MobileCLIP-S2 text output dimension");
            return nil;
        }
        double normSq = 0.0;
        for (int i = 0; i < 512; ++i) normSq += static_cast<double>(raw[i]) * raw[i];
        const float invNorm = normSq > 1e-12 ? static_cast<float>(1.0 / std::sqrt(normSq)) : 1.0f;
        NSMutableArray<NSNumber *> *arr = [NSMutableArray arrayWithCapacity:512];
        for (int i = 0; i < 512; ++i) {
            [arr addObject:@(raw[i] * invNorm)];
        }
        return arr;
    } catch (const std::exception &e) {
        if (error) *error = makeBridgeError(14, e.what());
        return nil;
    }
}

- (MGPanelDetection *)detectPanelsFromRgb:(NSData *)rgbData
                                    width:(NSInteger)width
                                   height:(NSInteger)height
                              ocrBoxCount:(NSInteger)ocrBoxCount {
    MGPanelDetection *out = [[MGPanelDetection alloc] init];
    out.panelCount = 1;
    out.hasGutter = NO;
    out.crops = @[];
    if (rgbData == nil || width <= 0 || height <= 0 || rgbData.length < static_cast<NSUInteger>(width * height * 3)) {
        return out;
    }
    const auto det = memeget::core::detectPanelsAndSubCrops(
        static_cast<const uint8_t *>(rgbData.bytes),
        static_cast<int>(width),
        static_cast<int>(height),
        3,
        static_cast<int>(ocrBoxCount),
        true);
    out.panelCount = det.panelCount;
    out.hasGutter = (det.hasHorizontalGutter || det.hasVerticalGutter) ? YES : NO;
    NSMutableArray<MGPanelCrop *> *arr = [NSMutableArray arrayWithCapacity:det.crops.size()];
    for (const auto &c : det.crops) {
        MGPanelCrop *mc = [[MGPanelCrop alloc] init];
        mc.x = c.x;
        mc.y = c.y;
        mc.width = c.width;
        mc.height = c.height;
        [arr addObject:mc];
    }
    out.crops = arr;
    return out;
}

- (NSArray<MGConceptHit *> *)classifyMemeWithImageVec:(const float *)imageVec512
                                              ocrText:(NSString *)ocrText {
    if (imageVec512 == nullptr || _openVocab.empty()) return @[];
    const std::string ocrStr = ocrText ? [ocrText UTF8String] : "";
    const auto summary = _openVocab.evaluateImage(imageVec512, ocrStr, 8);

    std::vector<memeget::core::PairwiseVerifierCore::Candidate> cands;
    for (const auto &h : summary.hits) {
        const auto *entry = _openVocab.findConcept(h.label);
        if (entry == nullptr) continue;
        memeget::core::PairwiseVerifierCore::Candidate vc;
        vc.key = h.label;
        vc.protoVec512 = entry->cHybrid.data();
        vc.textVec512 = entry->tStock.data();
        vc.calPosCos = h.posCos;
        vc.tagMean = entry->tagMean;
        vc.tagStd = entry->tagStd;
        vc.hasStats = true;
        vc.voteCount = h.fromZTop1 ? 3 : 1;
        vc.support70 = h.posCos >= 0.70f ? 2 : 0;
        vc.ocrHit = false;
        vc.isEntity = entry->isEntity;
        cands.push_back(vc);
    }

    std::unordered_map<std::string, float> pvProbs;
    if (_verifier.valid() && !cands.empty()) {
        for (const auto &sc : _verifier.scoreCandidates(imageVec512, cands)) {
            pvProbs[sc.key] = sc.probability;
        }
    }

    NSMutableArray<MGConceptHit *> *out = [NSMutableArray array];
    for (const auto &h : summary.hits) {
        float finalScore = h.posCos;
        auto it = pvProbs.find(h.label);
        if (it != pvProbs.end()) {
            finalScore = 0.55f * h.posCos + 0.45f * it->second;
        }
        if (h.fromZTop1 || h.posCos >= 0.72f || finalScore >= 0.68f) {
            MGConceptHit *mc = [[MGConceptHit alloc] init];
            mc.label = [NSString stringWithUTF8String:h.label.c_str()] ?: @"";
            mc.category = [NSString stringWithUTF8String:h.category.c_str()] ?: @"topic";
            mc.score = finalScore;
            mc.zScore = h.zScore;
            mc.isEntity = h.isEntity ? YES : NO;
            [out addObject:mc];
            if (out.count >= 6) break;
        }
    }
    return out;
}

- (MGStage1Outcome *)evaluateStage1WithImageVec:(const float *)imageVec512
                                          width:(NSInteger)width
                                         height:(NSInteger)height
                                        isVideo:(BOOL)isVideo
                                     durationMs:(int64_t)durationMs
                               isScreenshotHint:(BOOL)isScreenshotHint
                                     panelCount:(NSInteger)panelCount
                                      hasGutter:(BOOL)hasGutter
                              ordinaryAnchorCos:(float)ordinaryAnchorCos
                                userRejectedCos:(float)userRejectedCos
                                       ocrBoxes:(NSArray<MGOcrBoxInput *> *)ocrBoxes
                                    fullOcrText:(NSString *)fullOcrText {
    memeget::core::MemeGateStage1Input in;
    in.width = static_cast<int>(width);
    in.height = static_cast<int>(height);
    in.isVideo = isVideo == YES;
    in.durationMs = durationMs;
    in.isScreenshotHint = isScreenshotHint == YES;
    in.detectedPanelCount = static_cast<int>(panelCount);
    in.hasPanelGutter = hasGutter == YES;
    if (ordinaryAnchorCos > 0.0f) {
        in.maxOrdinaryPhotoAnchorCos = ordinaryAnchorCos;
    } else if (imageVec512 != nullptr && !_gateAnchors.empty()) {
        in.maxOrdinaryPhotoAnchorCos = _gateAnchors.maxOrdinaryPhotoCos(imageVec512);
        in.maxCuratedMargin = _gateAnchors.maxMemeAnchorMargin(imageVec512);
    }
    in.maxUserRejectedCos = userRejectedCos;
    in.fullOcrText = fullOcrText ? [fullOcrText UTF8String] : "";

    for (MGOcrBoxInput *b in ocrBoxes) {
        memeget::core::OcrBox cb;
        cb.left = b.left;
        cb.top = b.top;
        cb.right = b.right;
        cb.bottom = b.bottom;
        cb.confidence = b.confidence;
        cb.text = b.text ? [b.text UTF8String] : "";
        in.ocrBoxes.push_back(std::move(cb));
    }

    NSArray<MGConceptHit *> *classified = @[];
    NSMutableString *extraTerms = [NSMutableString string];
    if (imageVec512 != nullptr && !_openVocab.empty()) {
        const auto summary = _openVocab.evaluateImage(imageVec512, in.fullOcrText, 6);
        in.maxOpenVocabHybridCos = summary.maxHybridCos;
        in.maxOpenVocabZScore = summary.maxEntityZScore;
        for (const auto &h : summary.hits) {
            in.topCandidateLabels.push_back(h.label);
        }
        classified = [self classifyMemeWithImageVec:imageVec512 ocrText:fullOcrText ?: @""];
        for (MGConceptHit *hit in classified) {
            [extraTerms appendFormat:@"%@ ", hit.label];
            for (const auto &parent : memeget::core::OpenVocabIndex::parentImplications([hit.label UTF8String])) {
                [extraTerms appendFormat:@"%s ", parent.first.c_str()];
            }
        }
    }

    const auto res = memeget::core::evaluateMemeGateStage1(in);
    MGStage1Outcome *out = [[MGStage1Outcome alloc] init];
    out.verdict = static_cast<NSInteger>(res.verdict);
    out.memeProbability = res.memeProbability;
    out.reason = [NSString stringWithUTF8String:res.reason.c_str()] ?: @"";
    out.conceptHits = classified;
    out.extraTerms = [extraTerms stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]];
    if (res.verdict == memeget::core::MemeGateVerdict::UNCERTAIN_TEXT) {
        const std::string prompt = memeget::core::renderD1MemeGatePrompt(in, res);
        out.d1Prompt = [NSString stringWithUTF8String:prompt.c_str()] ?: @"";
    } else {
        out.d1Prompt = @"";
    }
    return out;
}

- (nullable NSData *)conceptPrototypeVectorForQuery:(NSString *)queryText {
    if (queryText.length == 0 || _openVocab.empty()) return nil;
    const auto *entry = _openVocab.findConcept([queryText UTF8String]);
    if (entry == nullptr || entry->cHybrid.size() != 512) return nil;
    return [NSData dataWithBytes:entry->cHybrid.data() length:512 * sizeof(float)];
}

- (NSArray<NSString *> *)semanticQueryTermsForVector:(const float *)queryVec512 {
    if (queryVec512 == nullptr || _openVocab.empty()) return @[];
    const auto terms = _openVocab.semanticQueryTerms(queryVec512, 0.30f, 4);
    NSMutableArray<NSString *> *out = [NSMutableArray arrayWithCapacity:terms.size()];
    for (const auto &t : terms) {
        NSString *s = [NSString stringWithUTF8String:t.c_str()];
        if (s != nil) [out addObject:s];
    }
    return out;
}

- (nullable MGD1Outcome *)evaluateD1GateWithPrompt:(NSString *)renderedPrompt
                                         modelPath:(NSString *)modelPath
                                             error:(NSError * _Nullable * _Nullable)error {
    if (renderedPrompt.length == 0 || modelPath.length == 0) {
        if (error) *error = makeBridgeError(20, "Empty d1 prompt or model path");
        return nil;
    }
    try {
        std::scoped_lock lock(_engineMutex);
        _vlmRunner.reset();
        const std::string mPath([modelPath UTF8String]);
        if (!_d1Runner || _d1Runner->modelPath() != mPath) {
            _d1Runner.reset();
            _d1Runner = std::make_unique<memeget::core::LlamaDecisionRunner>(mPath, 4, &_cancelled);
        }
        const auto res = _d1Runner->evaluateMemeGate([renderedPrompt UTF8String]);
        MGD1Outcome *out = [[MGD1Outcome alloc] init];
        out.isMeme = res.isMeme ? YES : NO;
        out.memeProbability = res.memeProbability;
        out.evalMs = res.evalMs;
        return out;
    } catch (const std::exception &e) {
        if (error) *error = makeBridgeError(21, e.what());
        return nil;
    }
}

- (nullable MGVlmGateOutcome *)gateAndDescribeRgb:(NSData *)rgbData
                                            width:(NSInteger)width
                                           height:(NSInteger)height
                                          ocrHint:(NSString *)ocrHint
                                   candidateHints:(NSString *)candidateHints
                                        modelPath:(NSString *)modelPath
                                    projectorPath:(NSString *)projectorPath
                                            error:(NSError * _Nullable * _Nullable)error {
    if (rgbData == nil || width <= 0 || height <= 0 || modelPath.length == 0 || projectorPath.length == 0) {
        if (error) *error = makeBridgeError(30, "Invalid VLM input parameters");
        return nil;
    }
    try {
        std::scoped_lock lock(_engineMutex);
        _d1Runner.reset();
        const std::string mPath([modelPath UTF8String]);
        const std::string pPath([projectorPath UTF8String]);
        if (!_vlmRunner || !_vlmRunner->matches(mPath, pPath)) {
            _vlmRunner.reset();
            _vlmRunner = std::make_unique<memeget::LlamaVisionRunner>(mPath, pPath, 4, &_cancelled);
        }
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384) {
            if (error) *error = makeBridgeError(32, "Invalid RGB dimensions");
            return nil;
        }
        const size_t expectedBytes = static_cast<size_t>(width) * static_cast<size_t>(height) * 3u;
        if (rgbData.length != expectedBytes) {
            if (error) *error = makeBridgeError(33, "RGB buffer length does not match width * height * 3");
            return nil;
        }
        memeget::RgbImage img;
        img.width = static_cast<int>(width);
        img.height = static_cast<int>(height);
        img.pixels.resize(expectedBytes);
        std::memcpy(img.pixels.data(), rgbData.bytes, expectedBytes);
        const auto res = _vlmRunner->gateAndDescribe(
            img,
            ocrHint ? [ocrHint UTF8String] : "",
            candidateHints ? [candidateHints UTF8String] : "",
            140);
        MGVlmGateOutcome *out = [[MGVlmGateOutcome alloc] init];
        out.isMeme = res.isMeme ? YES : NO;
        out.memeProbability = res.memeProbability;
        out.replyText = [NSString stringWithUTF8String:res.description.c_str()] ?: @"";
        out.generatedTokens = res.generatedTokens;
        out.totalMs = res.totalMs;
        return out;
    } catch (const std::exception &e) {
        if (error) *error = makeBridgeError(31, e.what());
        return nil;
    }
}

- (BOOL)convertProjectorBf16AtPath:(NSString *)sourcePath
                         toF16Path:(NSString *)destPath
                             error:(NSError * _Nullable * _Nullable)error {
    try {
        std::scoped_lock lock(_engineMutex);
        const int converted = memeget::convertBf16TensorsToF16([sourcePath UTF8String], [destPath UTF8String], &_cancelled);
        return converted >= 0 ? YES : NO;
    } catch (const std::exception &e) {
        if (error) *error = makeBridgeError(32, e.what());
        return NO;
    }
}

- (float)computeCslsHubnessForImageVec:(const float *)imageVec512 {
    if (imageVec512 == nullptr || _cslsBank.empty()) return 0.0f;
    return memeget::core::computeCandidateCslsHubness(
        imageVec512, _cslsBank.data(), static_cast<int>(_cslsBank.size() / 512), 512, 10);
}

+ (void)quantizeVec512:(const float *)srcFp32
               dstInt8:(int8_t *)dstInt8
            dstRabit64:(nullable uint64_t *)dstRabit64 {
    memeget::core::quantizeUnitVec512(srcFp32, dstInt8, dstRabit64);
}

+ (NSInteger)batchDenseScoresWithQuery:(const float *)queryVec512
                              protoVec:(nullable const float *)protoVec512
                          imageMatInt8:(const int8_t *)imageMatInt8
                          imageRabit64:(nullable const uint64_t *)imageRabit64
                              hasImage:(const uint8_t *)hasImage
                        imgCslsHubness:(nullable const float *)imgCslsHubness
                         scenePoolInt8:(nullable const int8_t *)scenePoolInt8
                          sceneOffsets:(nullable const int32_t *)sceneOffsets
                        captionMatInt8:(nullable const int8_t *)captionMatInt8
                            hasCaption:(nullable const uint8_t *)hasCaption
                            capHubness:(nullable const float *)capHubness
                                 count:(NSInteger)count
                             outScores:(float *)outScores {
    return memeget::core::batchDenseScoresQuantized512(
        queryVec512,
        protoVec512,
        imageMatInt8,
        imageRabit64,
        hasImage,
        imgCslsHubness,
        scenePoolInt8,
        sceneOffsets,
        captionMatInt8,
        hasCaption,
        capHubness,
        static_cast<int>(count),
        0.22f,
        0.37f,
        outScores);
}

@end
