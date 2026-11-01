// Builds the library globe's tile mesh for one frame. The same loop in Kotlin took ~14 ms a frame for 2,344 memes
// on a Pixel 5a running the debug app; here it is a small fraction of a millisecond.
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <vector>

namespace {

constexpr float kPi = 3.14159265358979f;

inline float wrapf(float x, int m) {
    const float r = std::fmod(x, static_cast<float>(m));
    return r < 0.f ? r + m : r;
}

// [x] wrapped into [-m/2, m/2): the shortest way round.
inline float wrapHalf(float x, int m) { return wrapf(x + m / 2.f, m) - m / 2.f; }

inline int floorMod(int x, int m) {
    const int r = x % m;
    return r < 0 ? r + m : r;
}

struct Out {
    const float* tex;
    float* verts;
    float* texs;
    jint* items;
    jint* cells;
    int capacity;
    int quads = 0;

    // Corners clockwise from top-left.
    void add(int item, int col, int row, float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
        if (quads >= capacity) return;
        float* v = verts + quads * 8;
        v[0] = x0; v[1] = y0; v[2] = x1; v[3] = y1; v[4] = x2; v[5] = y2; v[6] = x3; v[7] = y3;
        std::copy(tex + item * 8, tex + item * 8 + 8, texs + quads * 8);
        items[quads] = item;
        cells[quads * 2] = col;
        cells[quads * 2 + 1] = row;
        ++quads;
    }
};

}  // namespace

extern "C" {

// Fills [verts] (x, y per corner), [texs] (copied from [tex], the atlas corners of each item), [items] and [cellsOut]
// (column, row) for every tile on screen, and returns how many. [view] is centerU, centerV, cell size, flatness,
// centre x, centre y, width, height, tile half-size in cells. Flatness 0 wraps the grid round a sphere of
// circumference cols × cell; 1 lays it flat, repeating where it's smaller than the screen; between, each corner
// is part way. Away-facing columns are skipped while the ball is still mostly round.
JNIEXPORT jint JNICALL
Java_com_memeget_nativeapp_inference_NativeBridge_nativeGlobeMesh(
    JNIEnv* env, jobject /* thiz */, jintArray cellsArr, jint gridCols, jint gridRows, jint cols, jint rows,
    jfloatArray texArr, jfloatArray viewArr, jfloatArray vertsArr, jfloatArray texsArr, jintArray itemsArr,
    jintArray cellsOutArr) {
    if (gridCols <= 0 || gridRows <= 0 || cols <= 0 || rows <= 0) return 0;
    float view[9];
    env->GetFloatArrayRegion(viewArr, 0, 9, view);
    const float cu = view[0], cv = view[1], s = view[2], t = view[3];
    const float cx = view[4], cy = view[5], w = view[6], h = view[7], half = view[8];
    const int capacity = std::min({
        static_cast<int>(env->GetArrayLength(itemsArr)),
        static_cast<int>(env->GetArrayLength(vertsArr) / 8),
        static_cast<int>(env->GetArrayLength(texsArr) / 8),
        static_cast<int>(env->GetArrayLength(cellsOutArr) / 2),
    });
    const int texItems = env->GetArrayLength(texArr) / 8;

    // Per-column scratch, sized before taking the arrays: nothing that may allocate or call into the VM runs while
    // they're held.
    static thread_local std::vector<int> frontCols;
    static thread_local std::vector<float> sphereLeft, sphereRight, flatLeft, flatRight;
    frontCols.resize(cols);
    sphereLeft.resize(cols);
    sphereRight.resize(cols);
    flatLeft.resize(cols);
    flatRight.resize(cols);
    const float radius = s * cols / (2.f * kPi);

    auto* cells = static_cast<jint*>(env->GetPrimitiveArrayCritical(cellsArr, nullptr));
    auto* tex = static_cast<float*>(env->GetPrimitiveArrayCritical(texArr, nullptr));
    auto* verts = static_cast<float*>(env->GetPrimitiveArrayCritical(vertsArr, nullptr));
    auto* texs = static_cast<float*>(env->GetPrimitiveArrayCritical(texsArr, nullptr));
    auto* items = static_cast<jint*>(env->GetPrimitiveArrayCritical(itemsArr, nullptr));
    auto* cellsOut = static_cast<jint*>(env->GetPrimitiveArrayCritical(cellsOutArr, nullptr));
    Out out{tex, verts, texs, items, cellsOut, capacity};

    if (cells && tex && verts && texs && items && cellsOut) {
        if (t >= 0.999f) {
            const float span = 2.f * half * s;
            const int k0 = static_cast<int>(std::floor(cu - cx / s)) - 1;
            const int k1 = static_cast<int>(std::ceil(cu + (w - cx) / s)) + 1;
            const int j0 = static_cast<int>(std::floor(cv - cy / s)) - 1;
            const int j1 = static_cast<int>(std::ceil(cv + (h - cy) / s)) + 1;
            for (int j = j0; j <= j1; ++j) {
                const float top = cy + (j + 0.5f - cv - half) * s;
                const float bottom = top + span;
                if (bottom < 0.f || top > h) continue;
                const int rowBase = floorMod(j, gridRows) * gridCols;
                const int row = floorMod(j, rows);
                for (int k = k0; k <= k1; ++k) {
                    const int item = cells[rowBase + floorMod(k, gridCols)];
                    if (item < 0 || item >= texItems) continue;
                    const float left = cx + (k + 0.5f - cu - half) * s;
                    const float right = left + span;
                    out.add(item, floorMod(k, cols), row, left, top, right, top, right, bottom, left, bottom);
                }
            }
        } else {
            const float sphereWeight = 1.f - t;
            const float step = 2.f * kPi / cols;
            int fronts = 0;
            for (int c = 0; c < cols; ++c) {
                const float du = wrapHalf(c + 0.5f - cu, cols);
                if (t < 0.9f && std::cos(step * du) < 0.f) continue;
                frontCols[fronts++] = c;
                sphereLeft[c] = radius * std::sin(step * (du - half)) * sphereWeight;
                sphereRight[c] = radius * std::sin(step * (du + half)) * sphereWeight;
                flatLeft[c] = cx + (du - half) * s * t;
                flatRight[c] = cx + (du + half) * s * t;
            }
            const float halfPi = kPi / 2.f;
            const float latStep = kPi / rows;
            for (int r = 0; r < rows; ++r) {
                const float dv = wrapHalf(r + 0.5f - cv, rows);
                const float latTop = std::clamp(latStep * (dv - half), -halfPi, halfPi);
                const float latBottom = std::clamp(latStep * (dv + half), -halfPi, halfPi);
                const float yTop = cy + radius * std::sin(latTop) * sphereWeight + (dv - half) * s * t;
                const float yBottom = cy + radius * std::sin(latBottom) * sphereWeight + (dv + half) * s * t;
                if (yBottom < 0.f || yTop > h) continue;
                const float cosTop = std::cos(latTop);
                const float cosBottom = std::cos(latBottom);
                const int rowBase = (r % gridRows) * gridCols;
                for (int n = 0; n < fronts; ++n) {
                    const int c = frontCols[n];
                    const int item = cells[rowBase + c % gridCols];
                    if (item < 0 || item >= texItems) continue;
                    const float xTopLeft = cosTop * sphereLeft[c] + flatLeft[c];
                    const float xBottomLeft = cosBottom * sphereLeft[c] + flatLeft[c];
                    const float xTopRight = cosTop * sphereRight[c] + flatRight[c];
                    const float xBottomRight = cosBottom * sphereRight[c] + flatRight[c];
                    if (std::max(xTopRight, xBottomRight) < 0.f || std::min(xTopLeft, xBottomLeft) > w) continue;
                    out.add(item, c, r, xTopLeft, yTop, xTopRight, yTop, xBottomRight, yBottom, xBottomLeft, yBottom);
                }
            }
        }
    }

    if (cellsOut) env->ReleasePrimitiveArrayCritical(cellsOutArr, cellsOut, 0);
    if (items) env->ReleasePrimitiveArrayCritical(itemsArr, items, 0);
    if (texs) env->ReleasePrimitiveArrayCritical(texsArr, texs, 0);
    if (verts) env->ReleasePrimitiveArrayCritical(vertsArr, verts, 0);
    if (tex) env->ReleasePrimitiveArrayCritical(texArr, tex, JNI_ABORT);
    if (cells) env->ReleasePrimitiveArrayCritical(cellsArr, cells, JNI_ABORT);
    return out.quads;
}

}  // extern "C"
