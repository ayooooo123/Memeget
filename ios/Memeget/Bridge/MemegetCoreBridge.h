#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface MGPanelCrop : NSObject
@property (nonatomic, assign) NSInteger x;
@property (nonatomic, assign) NSInteger y;
@property (nonatomic, assign) NSInteger width;
@property (nonatomic, assign) NSInteger height;
@end

@interface MGPanelDetection : NSObject
@property (nonatomic, assign) NSInteger panelCount;
@property (nonatomic, assign) BOOL hasGutter;
@property (nonatomic, strong) NSArray<MGPanelCrop *> *crops;
@end

@interface MGOcrBoxInput : NSObject
@property (nonatomic, assign) float left;
@property (nonatomic, assign) float top;
@property (nonatomic, assign) float right;
@property (nonatomic, assign) float bottom;
@property (nonatomic, assign) float confidence;
@property (nonatomic, copy) NSString *text;
@end

@interface MGConceptHit : NSObject
@property (nonatomic, copy) NSString *label;
@property (nonatomic, copy) NSString *category;
@property (nonatomic, assign) float score;
@property (nonatomic, assign) float zScore;
@property (nonatomic, assign) BOOL isEntity;
@end

@interface MGStage1Outcome : NSObject
@property (nonatomic, assign) NSInteger verdict; // 0=REJECTED, 1=ACCEPTED, 2=UNCERTAIN_TEXT, 3=UNCERTAIN_VISUAL
@property (nonatomic, assign) float memeProbability;
@property (nonatomic, copy) NSString *d1Prompt;
@property (nonatomic, copy) NSString *reason;
@property (nonatomic, strong) NSArray<MGConceptHit *> *conceptHits;
@property (nonatomic, copy) NSString *extraTerms;
@end

@interface MGD1Outcome : NSObject
@property (nonatomic, assign) BOOL isMeme;
@property (nonatomic, assign) float memeProbability;
@property (nonatomic, assign) int64_t evalMs;
@end

@interface MGVlmGateOutcome : NSObject
@property (nonatomic, assign) BOOL isMeme;
@property (nonatomic, assign) float memeProbability;
@property (nonatomic, copy) NSString *replyText;
@property (nonatomic, assign) NSInteger generatedTokens;
@property (nonatomic, assign) int64_t totalMs;
@end

// Objective-C++ collar binding Swift straight onto the shared C++20 Memeget engine (ExecuTorch .pte +
// llama.cpp d1-omni & MiniCPM-V + 6,028 OpenVocabConcepts + PairwiseVisualVerifier + 30k Int8/CSLS search).
@interface MemegetCoreBridge : NSObject

- (instancetype)initWithOpenVocabPath:(nullable NSString *)openVocabPath
                         verifierPath:(nullable NSString *)verifierPath
                          anchorsPath:(nullable NSString *)anchorsPath;

- (instancetype)initWithOpenVocabPath:(nullable NSString *)openVocabPath
                         verifierPath:(nullable NSString *)verifierPath;

- (void)cancelActiveWork;
- (void)resetCancel;
- (void)unloadHeavyModels;
- (void)unloadAllModels;

// ExecuTorch MobileCLIP-S2 exact .pte execution (planar RGB [1, 3, 256, 256] in [0, 1] -> 512-d L2-normalized)
- (nullable NSArray<NSNumber *> *)embedImagePlanarRgb:(const float *)planarRgb256
                                            modelPath:(NSString *)modelPath
                                                error:(NSError * _Nullable * _Nullable)error;

- (nullable NSArray<NSNumber *> *)embedTextTokens77:(const int64_t *)tokenIds77
                                          modelPath:(NSString *)modelPath
                                              error:(NSError * _Nullable * _Nullable)error;

// Panel gutter + MINER sub-crop detector on packed RGB888 bytes
- (MGPanelDetection *)detectPanelsFromRgb:(NSData *)rgbData
                                    width:(NSInteger)width
                                   height:(NSInteger)height
                              ocrBoxCount:(NSInteger)ocrBoxCount;

// Stage-1 Multimodal Meme Gate + OpenVocabConcepts + PairwiseVisualVerifier classification
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
                                    fullOcrText:(NSString *)fullOcrText;

- (NSArray<MGConceptHit *> *)classifyMemeWithImageVec:(const float *)imageVec512
                                              ocrText:(NSString *)ocrText;

- (nullable NSData *)conceptPrototypeVectorForQuery:(NSString *)queryText;
- (NSArray<NSString *> *)semanticQueryTermsForVector:(const float *)queryVec512;

// Stage-2 Fine-Tuned LiquidAI/d1-omni-600M System-1 Gate
- (nullable MGD1Outcome *)evaluateD1GateWithPrompt:(NSString *)renderedPrompt
                                         modelPath:(NSString *)modelPath
                                             error:(NSError * _Nullable * _Nullable)error;

// Stage-3 MiniCPM-V 4.7 1B 1-Token YES/NO Gate + Zero-Re-Encode Two-Domain Continuation
- (nullable MGVlmGateOutcome *)gateAndDescribeRgb:(NSData *)rgbData
                                            width:(NSInteger)width
                                           height:(NSInteger)height
                                          ocrHint:(NSString *)ocrHint
                                   candidateHints:(NSString *)candidateHints
                                        modelPath:(NSString *)modelPath
                                    projectorPath:(NSString *)projectorPath
                                            error:(NSError * _Nullable * _Nullable)error;

- (BOOL)convertProjectorBf16AtPath:(NSString *)sourcePath
                         toF16Path:(NSString *)destPath
                             error:(NSError * _Nullable * _Nullable)error;

// 30,000-Item Int8 + RaBitQ + Candidate-Side CSLS + Multi-Scene MaxSim Resident Search
- (float)computeCslsHubnessForImageVec:(const float *)imageVec512;

+ (void)quantizeVec512:(const float *)srcFp32
               dstInt8:(int8_t *)dstInt8
            dstRabit64:(nullable uint64_t *)dstRabit64;

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
                             outScores:(float *)outScores;

@end

NS_ASSUME_NONNULL_END
