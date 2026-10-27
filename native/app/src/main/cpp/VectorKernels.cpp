// Cosine work over unit vectors for the Label tab and the look-alike vote. The same loops in Kotlin take
// ~11 s for 1,700 memes on a Pixel 5a running the debug app; here they take a fraction of a second.
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>
#include <vector>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif
#include "ResidentSearchCore.hpp"

namespace {

// Dot product of two rows whose length is a multiple of 16.
inline float dot(const float* a, const float* b, int dim) {
#if defined(__aarch64__)
    float32x4_t s0 = vdupq_n_f32(0.f);
    float32x4_t s1 = vdupq_n_f32(0.f);
    float32x4_t s2 = vdupq_n_f32(0.f);
    float32x4_t s3 = vdupq_n_f32(0.f);
    for (int k = 0; k < dim; k += 16) {
        s0 = vfmaq_f32(s0, vld1q_f32(a + k), vld1q_f32(b + k));
        s1 = vfmaq_f32(s1, vld1q_f32(a + k + 4), vld1q_f32(b + k + 4));
        s2 = vfmaq_f32(s2, vld1q_f32(a + k + 8), vld1q_f32(b + k + 8));
        s3 = vfmaq_f32(s3, vld1q_f32(a + k + 12), vld1q_f32(b + k + 12));
    }
    return vaddvq_f32(vaddq_f32(vaddq_f32(s0, s1), vaddq_f32(s2, s3)));
#else
    float s = 0.f;
    for (int k = 0; k < dim; ++k) s += a[k] * b[k];
    return s;
#endif
}

// Row count of [vectors] for rows of [dim] floats, or -1 after throwing IllegalArgumentException.
int rowsOf(JNIEnv* env, jfloatArray vectors, jint dim) {
    const jsize total = env->GetArrayLength(vectors);
    if (dim <= 0 || dim % 16 != 0 || total % dim != 0) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                      "vectors must hold whole rows of a multiple of 16 floats");
        return -1;
    }
    return total / dim;
}

}  // namespace

extern "C" {

// counts[i] = how many other rows reach [minCos] with row i.
JNIEXPORT jintArray JNICALL
Java_com_memeget_nativeapp_inference_NativeBridge_nativeLookalikeCounts(
    JNIEnv* env, jobject /* thiz */, jfloatArray vectors, jint dim, jfloat minCos) {
    const int n = rowsOf(env, vectors, dim);
    if (n < 0) return nullptr;
    // A copy rather than a pinned array: this pass runs long enough to matter to the garbage collector.
    std::vector<float> data(static_cast<size_t>(n) * dim);
    env->GetFloatArrayRegion(vectors, 0, static_cast<jsize>(data.size()), data.data());
    std::vector<jint> counts(n, 0);
    for (int i = 0; i < n; ++i) {
        const float* a = data.data() + static_cast<size_t>(i) * dim;
        for (int j = i + 1; j < n; ++j) {
            if (dot(a, data.data() + static_cast<size_t>(j) * dim, dim) >= minCos) {
                ++counts[i];
                ++counts[j];
            }
        }
    }
    jintArray out = env->NewIntArray(n);
    if (out != nullptr) env->SetIntArrayRegion(out, 0, n, counts.data());
    return out;
}

// Rows other than [row], not marked in [covered], that reach [minCos] with row [row].
JNIEXPORT jintArray JNICALL
Java_com_memeget_nativeapp_inference_NativeBridge_nativeLookalikesOf(
    JNIEnv* env, jobject /* thiz */, jfloatArray vectors, jint dim, jint row, jbooleanArray covered, jfloat minCos) {
    const int n = rowsOf(env, vectors, dim);
    if (n < 0) return nullptr;
    if (row < 0 || row >= n || env->GetArrayLength(covered) != n) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "row or covered out of range");
        return nullptr;
    }
    std::vector<jboolean> skip(n);
    env->GetBooleanArrayRegion(covered, 0, n, skip.data());
    std::vector<jint> found;
    // One pass over the rows: short enough to read the Java array in place.
    auto* data = static_cast<const float*>(env->GetPrimitiveArrayCritical(vectors, nullptr));
    if (data == nullptr) return nullptr;
    const float* a = data + static_cast<size_t>(row) * dim;
    for (int j = 0; j < n; ++j) {
        if (j == row || skip[j]) continue;
        if (dot(a, data + static_cast<size_t>(j) * dim, dim) >= minCos) found.push_back(j);
    }
    env->ReleasePrimitiveArrayCritical(vectors, const_cast<float*>(data), JNI_ABORT);
    jintArray out = env->NewIntArray(static_cast<jsize>(found.size()));
    if (out != nullptr) env->SetIntArrayRegion(out, 0, static_cast<jsize>(found.size()), found.data());
    return out;
}

// For each query row, the [k] example rows with the highest dot product, best first, into
// [outIndex]/[outDot] at row * k; -1 and -infinity fill slots fewer rows reach. An example is passed
// over when its owner equals the query's (owners below 0 never match) or its dot product reaches
// [skipAtOrAbove]. Ties keep the lower example index first, as LookalikeVote.rank does.
JNIEXPORT void JNICALL
Java_com_memeget_nativeapp_inference_NativeBridge_nativeNearest(
    JNIEnv* env, jobject /* thiz */, jfloatArray queries, jlongArray queryOwners, jfloatArray examples,
    jlongArray exampleOwners, jint dim, jint k, jfloat skipAtOrAbove, jintArray outIndex, jfloatArray outDot) {
    const int nq = rowsOf(env, queries, dim);
    if (nq < 0) return;
    const int ne = rowsOf(env, examples, dim);
    if (ne < 0) return;
    const jsize slots = static_cast<jsize>(nq) * k;
    if (k <= 0 || env->GetArrayLength(queryOwners) != nq || env->GetArrayLength(exampleOwners) != ne ||
        env->GetArrayLength(outIndex) != slots || env->GetArrayLength(outDot) != slots) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "owners or outputs do not match the rows");
        return;
    }
    // Copies rather than pinned arrays: this pass runs long enough to matter to the garbage collector.
    std::vector<float> q(static_cast<size_t>(nq) * dim);
    std::vector<float> e(static_cast<size_t>(ne) * dim);
    env->GetFloatArrayRegion(queries, 0, static_cast<jsize>(q.size()), q.data());
    env->GetFloatArrayRegion(examples, 0, static_cast<jsize>(e.size()), e.data());
    std::vector<jlong> qOwner(nq);
    std::vector<jlong> eOwner(ne);
    env->GetLongArrayRegion(queryOwners, 0, nq, qOwner.data());
    env->GetLongArrayRegion(exampleOwners, 0, ne, eOwner.data());
    std::vector<jint> index(static_cast<size_t>(slots), -1);
    std::vector<jfloat> best(static_cast<size_t>(slots), -std::numeric_limits<float>::infinity());
    for (int i = 0; i < nq; ++i) {
        jint* topIndex = index.data() + static_cast<size_t>(i) * k;
        jfloat* topDot = best.data() + static_cast<size_t>(i) * k;
        const float* a = q.data() + static_cast<size_t>(i) * dim;
        for (int j = 0; j < ne; ++j) {
            if (qOwner[i] >= 0 && eOwner[j] == qOwner[i]) continue;
            const float c = dot(a, e.data() + static_cast<size_t>(j) * dim, dim);
            if (c >= skipAtOrAbove || c <= topDot[k - 1]) continue;
            int s = k - 1;
            while (s > 0 && topDot[s - 1] < c) {
                topDot[s] = topDot[s - 1];
                topIndex[s] = topIndex[s - 1];
                --s;
            }
            topDot[s] = c;
            topIndex[s] = j;
        }
    }
    env->SetIntArrayRegion(outIndex, 0, slots, index.data());
    env->SetFloatArrayRegion(outDot, 0, slots, best.data());
}

// Grinds Master's online Positive-Unlabeled + explicit-rejection BCE updates straight into the residual
// verifier head (`deltaW`), zero GGUF bloat, keeping `deltaB` pinned so multi-label hits never lift bystanders.
JNIEXPORT void JNICALL
Java_com_memeget_nativeapp_inference_NativeBridge_nativeFitDecisionHead(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray baseLogits,
    jfloatArray scorerHidden,
    jintArray noneIndices,
    jfloatArray targets,
    jfloatArray weights,
    jint numExamples,
    jint numOptions,
    jint hiddenDim,
    jint epochs,
    jfloat learningRate,
    jfloat l2Decay,
    jfloatArray deltaW,
    jfloatArray outDeltaBAndLoss) {
    if (numExamples <= 0 || numOptions < 2 || hiddenDim <= 0) return;
    std::vector<float> logitsVec(static_cast<size_t>(numExamples) * numOptions);
    std::vector<float> hiddenVec(static_cast<size_t>(numExamples) * numOptions * hiddenDim);
    std::vector<jint> noneVec(numExamples);
    std::vector<float> targetVec(static_cast<size_t>(numExamples) * numOptions);
    std::vector<float> weightVec(static_cast<size_t>(numExamples) * numOptions);
    std::vector<float> wVec(hiddenDim);
    float bLoss[2] = {0.0f, 0.0f};

    env->GetFloatArrayRegion(baseLogits, 0, static_cast<jsize>(logitsVec.size()), logitsVec.data());
    env->GetFloatArrayRegion(scorerHidden, 0, static_cast<jsize>(hiddenVec.size()), hiddenVec.data());
    env->GetIntArrayRegion(noneIndices, 0, numExamples, noneVec.data());
    env->GetFloatArrayRegion(targets, 0, static_cast<jsize>(targetVec.size()), targetVec.data());
    env->GetFloatArrayRegion(weights, 0, static_cast<jsize>(weightVec.size()), weightVec.data());
    env->GetFloatArrayRegion(deltaW, 0, hiddenDim, wVec.data());
    env->GetFloatArrayRegion(outDeltaBAndLoss, 0, 2, bLoss);

    const float deltaB = bLoss[0];
    float lastLoss = 0.0f;
    for (int ep = 0; ep < epochs; ++ep) {
        const float lr = learningRate / (1.0f + 0.1f * static_cast<float>(ep));
        const float decay = 1.0f - lr * l2Decay;
        float epochLoss = 0.0f;
        float totalWeight = 0.0f;

        for (int i = 0; i < numExamples; ++i) {
            const int noneIdx = noneVec[i];
            const float* exLogits = logitsVec.data() + static_cast<size_t>(i) * numOptions;
            const float* exHidden = hiddenVec.data() + static_cast<size_t>(i) * numOptions * hiddenDim;
            const float* exTargets = targetVec.data() + static_cast<size_t>(i) * numOptions;
            const float* exWeights = weightVec.data() + static_cast<size_t>(i) * numOptions;
            const float zNone = exLogits[noneIdx];
            const float* hNone = exHidden + static_cast<size_t>(noneIdx) * hiddenDim;

            for (int k = 0; k < numOptions; ++k) {
                if (k == noneIdx) continue;
                const float w = exWeights[k];
                if (w <= 0.0f) continue;
                const float y = exTargets[k];
                const float* hk = exHidden + static_cast<size_t>(k) * hiddenDim;

                float residual = deltaB;
                float normSq = 0.0f;
                for (int d = 0; d < hiddenDim; ++d) {
                    const float dv = hk[d] - hNone[d];
                    residual += wVec[d] * dv;
                    normSq += dv * dv;
                }
                const float rel = std::clamp((exLogits[k] - zNone) + residual, -20.0f, 20.0f);
                const float prob = 1.0f / (1.0f + std::exp(-rel));
                const float step = (lr * (prob - y) * w) / std::max(4.0f, normSq);
                for (int d = 0; d < hiddenDim; ++d) {
                    wVec[d] = wVec[d] * decay - step * (hk[d] - hNone[d]);
                }
                epochLoss += (std::log(1.0f + std::exp(rel)) - y * rel) * w;
                totalWeight += w;
            }
        }
        lastLoss = totalWeight > 0.0f ? (epochLoss / totalWeight) : 0.0f;
    }
    bLoss[0] = deltaB;
    bLoss[1] = lastLoss;
    env->SetFloatArrayRegion(deltaW, 0, hiddenDim, wVec.data());
    env->SetFloatArrayRegion(outDeltaBAndLoss, 0, 2, bLoss);
}

// Sweeps ARM64 NEON dot products across all N resident image + caption vectors in <0.3 ms,
// subtracting per-caption boilerplate hubness so verbose captions never steamroll visual matches.
JNIEXPORT jint JNICALL
Java_com_memeget_nativeapp_inference_NativeBridge_nativeBatchDenseScores(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray queryVec,
    jfloatArray protoVec,
    jfloatArray imageMat,
    jbooleanArray hasImage,
    jfloatArray captionMat,
    jbooleanArray hasCaption,
    jfloatArray capHubness,
    jint count,
    jint dim,
    jfloatArray outScores) {
    if (count <= 0 || dim <= 0 || dim % 16 != 0) return 0;
    std::vector<float> q(dim);
    env->GetFloatArrayRegion(queryVec, 0, dim, q.data());
    const bool useProto = (protoVec != nullptr && env->GetArrayLength(protoVec) == dim);
    std::vector<float> p;
    if (useProto) {
        p.resize(dim);
        env->GetFloatArrayRegion(protoVec, 0, dim, p.data());
    }
    std::vector<jboolean> imgValid(count);
    std::vector<jboolean> capValid(count);
    std::vector<float> hub(count);
    env->GetBooleanArrayRegion(hasImage, 0, count, imgValid.data());
    env->GetBooleanArrayRegion(hasCaption, 0, count, capValid.data());
    env->GetFloatArrayRegion(capHubness, 0, count, hub.data());

    std::vector<float> out(count, 0.0f);
    auto* imgPtr = static_cast<const float*>(env->GetPrimitiveArrayCritical(imageMat, nullptr));
    if (imgPtr != nullptr) {
        for (int i = 0; i < count; ++i) {
            if (!imgValid[i]) continue;
            const float* row = imgPtr + static_cast<size_t>(i) * dim;
            float s = dot(q.data(), row, dim);
            if (useProto) {
                const float ps = dot(p.data(), row, dim);
                if (ps > 0.50f) s += 0.45f * (ps - 0.50f);
            }
            out[i] = s;
        }
        env->ReleasePrimitiveArrayCritical(imageMat, const_cast<float*>(imgPtr), JNI_ABORT);
    }
    auto* capPtr = static_cast<const float*>(env->GetPrimitiveArrayCritical(captionMat, nullptr));
    if (capPtr != nullptr) {
        for (int i = 0; i < count; ++i) {
            if (!capValid[i]) continue;
            const float* row = capPtr + static_cast<size_t>(i) * dim;
            const float capCos = dot(q.data(), row, dim);
            const float hubPenalty = 0.35f * std::max(0.0f, hub[i] - 0.68f);
            const float cAdj = capCos - hubPenalty;
            if (cAdj > 0.60f) {
                out[i] += std::min(0.14f, 0.45f * (cAdj - 0.60f));
            }
        }
        env->ReleasePrimitiveArrayCritical(captionMat, const_cast<float*>(capPtr), JNI_ABORT);
    }
    env->SetFloatArrayRegion(outScores, 0, count, out.data());
    return count;
}

JNIEXPORT void JNICALL
Java_com_memeget_nativeapp_inference_NativeBridge_nativeQuantizeVec512(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray srcFp32,
    jbyteArray dstInt8,
    jlongArray dstRabit64) {
    if (!srcFp32 || !dstInt8 || env->GetArrayLength(srcFp32) < 512 || env->GetArrayLength(dstInt8) < 512) return;
    float src[512];
    int8_t q[512];
    uint64_t rabit[8];
    env->GetFloatArrayRegion(srcFp32, 0, 512, src);
    memeget::core::quantizeUnitVec512(src, q, dstRabit64 ? rabit : nullptr);
    env->SetByteArrayRegion(dstInt8, 0, 512, reinterpret_cast<const jbyte*>(q));
    if (dstRabit64 && env->GetArrayLength(dstRabit64) >= 8) {
        env->SetLongArrayRegion(dstRabit64, 0, 8, reinterpret_cast<const jlong*>(rabit));
    }
}

JNIEXPORT jfloat JNICALL
Java_com_memeget_nativeapp_inference_NativeBridge_nativeComputeCslsHubness(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray imageVec,
    jfloatArray queryBankFlat,
    jint bankCount,
    jint topK) {
    if (!imageVec || !queryBankFlat || bankCount <= 0) return 0.0f;
    float img[512];
    env->GetFloatArrayRegion(imageVec, 0, 512, img);
    // The same 256×512 bank arrives for every meme of an index build: borrow it in place rather than allocate and
    // copy 512 KB per call (≈1.2 s of a 2,344-meme cold build). The kernel makes no JNI calls while it holds it.
    auto* bank = static_cast<const float*>(env->GetPrimitiveArrayCritical(queryBankFlat, nullptr));
    if (!bank) return 0.0f;
    const float hubness = memeget::core::computeCandidateCslsHubness(img, bank, bankCount, 512, topK);
    env->ReleasePrimitiveArrayCritical(queryBankFlat, const_cast<float*>(bank), JNI_ABORT);
    return hubness;
}

JNIEXPORT jint JNICALL
Java_com_memeget_nativeapp_inference_NativeBridge_nativeBatchDenseScoresQuantized(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray queryVec,
    jfloatArray protoVec,
    jbyteArray imageMatInt8,
    jlongArray imageRabit64,
    jbooleanArray hasImage,
    jfloatArray imgCslsHubness,
    jbyteArray scenePoolInt8,
    jintArray sceneOffsets,
    jbyteArray captionMatInt8,
    jbooleanArray hasCaption,
    jfloatArray capHubness,
    jint count,
    jfloat cslsWeight,
    jfloat cslsFloor,
    jfloatArray outScores) {
    if (count <= 0 || !queryVec || !imageMatInt8 || !hasImage || !outScores) return 0;
    float q[512];
    env->GetFloatArrayRegion(queryVec, 0, 512, q);
    const bool useProto = (protoVec != nullptr && env->GetArrayLength(protoVec) == 512);
    float p[512];
    if (useProto) env->GetFloatArrayRegion(protoVec, 0, 512, p);

    std::vector<uint8_t> imgValid(count);
    env->GetBooleanArrayRegion(hasImage, 0, count, reinterpret_cast<jboolean*>(imgValid.data()));
    std::vector<float> imgHub(count, 0.0f);
    if (imgCslsHubness != nullptr && env->GetArrayLength(imgCslsHubness) >= count) {
        env->GetFloatArrayRegion(imgCslsHubness, 0, count, imgHub.data());
    }
    std::vector<uint8_t> capValid(count, 0);
    if (hasCaption != nullptr && env->GetArrayLength(hasCaption) >= count) {
        env->GetBooleanArrayRegion(hasCaption, 0, count, reinterpret_cast<jboolean*>(capValid.data()));
    }
    std::vector<float> cHub(count, 0.0f);
    if (capHubness != nullptr && env->GetArrayLength(capHubness) >= count) {
        env->GetFloatArrayRegion(capHubness, 0, count, cHub.data());
    }
    std::vector<int32_t> sOffsets;
    if (sceneOffsets != nullptr && env->GetArrayLength(sceneOffsets) >= count + 1) {
        sOffsets.resize(count + 1);
        env->GetIntArrayRegion(sceneOffsets, 0, count + 1, reinterpret_cast<jint*>(sOffsets.data()));
    }
    std::vector<int8_t> sPool;
    if (scenePoolInt8 != nullptr && !sOffsets.empty() && sOffsets.back() > 0) {
        const jsize sBytes = env->GetArrayLength(scenePoolInt8);
        sPool.resize(sBytes);
        env->GetByteArrayRegion(scenePoolInt8, 0, sBytes, reinterpret_cast<jbyte*>(sPool.data()));
    }
    std::vector<uint64_t> rabit;
    if (imageRabit64 != nullptr && count > 8192 && env->GetArrayLength(imageRabit64) >= count * 8) {
        rabit.resize(static_cast<size_t>(count) * 8);
        env->GetLongArrayRegion(imageRabit64, 0, count * 8, reinterpret_cast<jlong*>(rabit.data()));
    }
    std::vector<int8_t> capMat;
    if (captionMatInt8 != nullptr && env->GetArrayLength(captionMatInt8) >= count * 512) {
        capMat.resize(static_cast<size_t>(count) * 512);
        env->GetByteArrayRegion(captionMatInt8, 0, count * 512, reinterpret_cast<jbyte*>(capMat.data()));
    }

    std::vector<float> out(count, 0.0f);
    auto* imgPtr = static_cast<const int8_t*>(env->GetPrimitiveArrayCritical(imageMatInt8, nullptr));
    if (imgPtr != nullptr) {
        memeget::core::batchDenseScoresQuantized512(
            q,
            useProto ? p : nullptr,
            imgPtr,
            rabit.empty() ? nullptr : rabit.data(),
            imgValid.data(),
            imgCslsHubness != nullptr ? imgHub.data() : nullptr,
            sPool.empty() ? nullptr : sPool.data(),
            sOffsets.empty() ? nullptr : sOffsets.data(),
            capMat.empty() ? nullptr : capMat.data(),
            hasCaption != nullptr ? capValid.data() : nullptr,
            capHubness != nullptr ? cHub.data() : nullptr,
            count,
            cslsWeight,
            cslsFloor,
            out.data());
        env->ReleasePrimitiveArrayCritical(imageMatInt8, const_cast<int8_t*>(imgPtr), JNI_ABORT);
    }
    env->SetFloatArrayRegion(outScores, 0, count, out.data());
    return count;
}

}  // extern "C"
