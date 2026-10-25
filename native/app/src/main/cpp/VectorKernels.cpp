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

}  // extern "C"
