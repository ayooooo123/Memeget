#include "ResidentSearchCore.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace memeget::core {

float dotFp32(const float* a, const float* b, int dim) {
#if defined(__aarch64__)
    float32x4_t s0 = vdupq_n_f32(0.0f);
    float32x4_t s1 = vdupq_n_f32(0.0f);
    float32x4_t s2 = vdupq_n_f32(0.0f);
    float32x4_t s3 = vdupq_n_f32(0.0f);
    int k = 0;
    for (; k + 16 <= dim; k += 16) {
        s0 = vfmaq_f32(s0, vld1q_f32(a + k), vld1q_f32(b + k));
        s1 = vfmaq_f32(s1, vld1q_f32(a + k + 4), vld1q_f32(b + k + 4));
        s2 = vfmaq_f32(s2, vld1q_f32(a + k + 8), vld1q_f32(b + k + 8));
        s3 = vfmaq_f32(s3, vld1q_f32(a + k + 12), vld1q_f32(b + k + 12));
    }
    float total = vaddvq_f32(vaddq_f32(vaddq_f32(s0, s1), vaddq_f32(s2, s3)));
    for (; k < dim; ++k) total += a[k] * b[k];
    return total;
#else
    float total = 0.0f;
    for (int k = 0; k < dim; ++k) total += a[k] * b[k];
    return total;
#endif
}

void quantizeUnitVec512(
    const float* srcFp32,
    int8_t* dstInt8,
    uint64_t* dstRabit64) {
    if (dstRabit64 != nullptr) {
        std::memset(dstRabit64, 0, sizeof(uint64_t) * kRabitWords512);
    }
    for (int i = 0; i < kDefaultEmbedDim; ++i) {
        const float scaled = std::clamp(srcFp32[i] * 127.0f, -127.0f, 127.0f);
        dstInt8[i] = static_cast<int8_t>(std::lrintf(scaled));
        if (dstRabit64 != nullptr && srcFp32[i] >= 0.0f) {
            dstRabit64[i >> 6] |= (uint64_t{1} << (i & 63));
        }
    }
}

float dotInt8_512(const int8_t* a, const int8_t* b) {
#if defined(__aarch64__)
    int32x4_t acc0 = vdupq_n_s32(0);
    int32x4_t acc1 = vdupq_n_s32(0);
    for (int k = 0; k < kDefaultEmbedDim; k += 32) {
        const int8x16_t va0 = vld1q_s8(a + k);
        const int8x16_t vb0 = vld1q_s8(b + k);
        const int16x8_t prod0Lo = vmull_s8(vget_low_s8(va0), vget_low_s8(vb0));
        const int16x8_t prod0Hi = vmull_s8(vget_high_s8(va0), vget_high_s8(vb0));
        acc0 = vpadalq_s16(acc0, vaddq_s16(prod0Lo, prod0Hi));

        const int8x16_t va1 = vld1q_s8(a + k + 16);
        const int8x16_t vb1 = vld1q_s8(b + k + 16);
        const int16x8_t prod1Lo = vmull_s8(vget_low_s8(va1), vget_low_s8(vb1));
        const int16x8_t prod1Hi = vmull_s8(vget_high_s8(va1), vget_high_s8(vb1));
        acc1 = vpadalq_s16(acc1, vaddq_s16(prod1Lo, prod1Hi));
    }
    const int32_t sum = vaddvq_s32(vaddq_s32(acc0, acc1));
    return static_cast<float>(sum) * kInt8InvScaleSq;
#else
    int32_t sum = 0;
    for (int k = 0; k < kDefaultEmbedDim; ++k) {
        sum += static_cast<int32_t>(a[k]) * static_cast<int32_t>(b[k]);
    }
    return static_cast<float>(sum) * kInt8InvScaleSq;
#endif
}

int hammingDistance512(const uint64_t* a64, const uint64_t* b64) {
#if defined(__aarch64__)
    const uint8x16_t x0 = veorq_u8(
        vreinterpretq_u8_u64(vld1q_u64(a64)),
        vreinterpretq_u8_u64(vld1q_u64(b64)));
    const uint8x16_t x1 = veorq_u8(
        vreinterpretq_u8_u64(vld1q_u64(a64 + 2)),
        vreinterpretq_u8_u64(vld1q_u64(b64 + 2)));
    const uint8x16_t x2 = veorq_u8(
        vreinterpretq_u8_u64(vld1q_u64(a64 + 4)),
        vreinterpretq_u8_u64(vld1q_u64(b64 + 4)));
    const uint8x16_t x3 = veorq_u8(
        vreinterpretq_u8_u64(vld1q_u64(a64 + 6)),
        vreinterpretq_u8_u64(vld1q_u64(b64 + 6)));
    const uint16x8_t sumPairs = vaddq_u16(
        vaddq_u16(vpaddlq_u8(vcntq_u8(x0)), vpaddlq_u8(vcntq_u8(x1))),
        vaddq_u16(vpaddlq_u8(vcntq_u8(x2)), vpaddlq_u8(vcntq_u8(x3))));
    return static_cast<int>(vaddvq_u16(sumPairs));
#else
    int dist = 0;
    for (int w = 0; w < kRabitWords512; ++w) {
        dist += __builtin_popcountll(a64[w] ^ b64[w]);
    }
    return dist;
#endif
}

float computeCandidateCslsHubness(
    const float* imageVec,
    const float* queryBankFlat,
    int bankCount,
    int dim,
    int topK) {
    if (imageVec == nullptr || queryBankFlat == nullptr || bankCount <= 0 || dim <= 0 || topK <= 0) {
        return 0.0f;
    }
    const int k = std::min(topK, bankCount);
    std::vector<float> top(k, -2.0f);
    for (int i = 0; i < bankCount; ++i) {
        const float c = dotFp32(imageVec, queryBankFlat + static_cast<size_t>(i) * dim, dim);
        if (c <= top[k - 1]) continue;
        int pos = k - 1;
        while (pos > 0 && top[pos - 1] < c) {
            top[pos] = top[pos - 1];
            --pos;
        }
        top[pos] = c;
    }
    float sum = 0.0f;
    for (int i = 0; i < k; ++i) sum += top[i];
    return sum / static_cast<float>(k);
}

int batchDenseScoresQuantized512(
    const float* queryVecFp32,
    const float* protoVecFp32,
    const int8_t* imageMatInt8,
    const uint64_t* imageRabit64,
    const uint8_t* hasImage,
    const float* imgCslsHubness,
    const int8_t* scenePoolInt8,
    const int32_t* sceneOffsets,
    const int8_t* captionMatInt8,
    const uint8_t* hasCaption,
    const float* capHubness,
    int count,
    float cslsWeight,
    float cslsFloor,
    float* outScores) {
    if (count <= 0 || queryVecFp32 == nullptr || imageMatInt8 == nullptr || hasImage == nullptr || outScores == nullptr) {
        return 0;
    }

    int8_t qInt8[kDefaultEmbedDim];
    uint64_t qRabit[kRabitWords512];
    quantizeUnitVec512(queryVecFp32, qInt8, qRabit);

    const bool useProto = (protoVecFp32 != nullptr);
    int8_t pInt8[kDefaultEmbedDim];
    if (useProto) {
        quantizeUnitVec512(protoVecFp32, pInt8, nullptr);
    }

    // Above 8,192 memes, use RaBitQ 64-byte sign distance to compute a fast lower bound for massive camera rolls,
    // while still computing exact Int8 NEON dot product on every item with any chance of semantic relevance.
    const bool useRabitGate = (count > 8192 && imageRabit64 != nullptr && scenePoolInt8 == nullptr);
    constexpr int kRabitRejectHamming = 286; // >286/512 sign flips implies negative cosine

    for (int i = 0; i < count; ++i) {
        float score = 0.0f;
        if (hasImage[i]) {
            if (useRabitGate) {
                const int hd = hammingDistance512(qRabit, imageRabit64 + static_cast<size_t>(i) * kRabitWords512);
                if (hd > kRabitRejectHamming && !useProto) {
                    const float approxCos = 1.0f - (2.0f * static_cast<float>(hd) / 512.0f);
                    outScores[i] = approxCos;
                    goto caption_pass;
                }
            }

            const int8_t* imgRow = imageMatInt8 + static_cast<size_t>(i) * kDefaultEmbedDim;
            float baseImgScore = dotInt8_512(qInt8, imgRow);

            // Multi-panel / MINER sub-crop MaxSim (arXiv:2609.27142): rescue small entities & 4-panel punchlines.
            if (scenePoolInt8 != nullptr && sceneOffsets != nullptr) {
                const int32_t sStart = sceneOffsets[i];
                const int32_t sEnd = sceneOffsets[i + 1];
                if (sEnd > sStart) {
                    float bestScene = -2.0f;
                    for (int32_t s = sStart; s < sEnd; ++s) {
                        const float sc = dotInt8_512(qInt8, scenePoolInt8 + static_cast<size_t>(s) * kDefaultEmbedDim);
                        if (sc > bestScene) bestScene = sc;
                    }
                    if (bestScene > baseImgScore) {
                        baseImgScore = std::max(baseImgScore, 0.25f * baseImgScore + 0.75f * bestScene);
                    }
                }
            }

            // Candidate-side CSLS anti-hubness subtraction (DBSN / MINER): knock generic hub faces off #1.
            if (imgCslsHubness != nullptr && cslsWeight > 0.0f) {
                const float excessHub = std::max(0.0f, imgCslsHubness[i] - cslsFloor);
                baseImgScore -= cslsWeight * excessHub;
            }

            score = baseImgScore;
            if (useProto) {
                const float ps = dotInt8_512(pInt8, imgRow);
                if (ps > 0.50f) {
                    score += 0.45f * (ps - 0.50f);
                }
            }
        }

    caption_pass:
        if (captionMatInt8 != nullptr && hasCaption != nullptr && hasCaption[i]) {
            const int8_t* capRow = captionMatInt8 + static_cast<size_t>(i) * kDefaultEmbedDim;
            const float capCos = dotInt8_512(qInt8, capRow);
            const float hubVal = (capHubness != nullptr) ? capHubness[i] : 0.0f;
            const float hubPenalty = 0.35f * std::max(0.0f, hubVal - 0.68f);
            const float cAdj = capCos - hubPenalty;
            if (cAdj > 0.60f) {
                score += std::min(0.14f, 0.45f * (cAdj - 0.60f));
            }
        }
        outScores[i] = score;
    }

    return count;
}

} // namespace memeget::core
