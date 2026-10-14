// Cosine work over unit vectors for the Label tab and the look-alike vote. The same loops in Kotlin take
// ~11 s for 1,700 memes on a Pixel 5a running the debug app; here they take a fraction of a second.
#include <jni.h>

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

}  // extern "C"
