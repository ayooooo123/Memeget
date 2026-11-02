// Catmull-Rom reads of a source frame, and the check that such a read stays clear of the frame's covered area.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace memeget {

// Weights of the four taps at offsets -1..+2 from the whole pixel below a position with fraction [t].
inline void catmullRom(float t, float* w) {
    const float t2 = t * t;
    const float t3 = t2 * t;
    w[0] = 0.5f * (-t3 + 2 * t2 - t);
    w[1] = 0.5f * (3 * t3 - 5 * t2 + 2);
    w[2] = 0.5f * (-3 * t3 + 4 * t2 + t);
    w[3] = 0.5f * (t3 - t2);
}

// True when no tap that a Catmull-Rom read at ([x], [y]) weighs lands on a covered pixel of [hole] ([w]×[h], nonzero
// = covered; null = nothing covered). Positions and taps are clamped to the frame as the read clamps them, so a read
// that only its centre tap shows clean can't pull logo pixels in from beside it.
inline bool footprintClear(const uint8_t* hole, int w, int h, float x, float y) {
    if (!hole) return true;
    x = std::clamp(x, 0.0f, static_cast<float>(w - 1));
    y = std::clamp(y, 0.0f, static_cast<float>(h - 1));
    const int x0 = static_cast<int>(x);
    const int y0 = static_cast<int>(y);
    float wx[4];
    float wy[4];
    catmullRom(x - x0, wx);
    catmullRom(y - y0, wy);
    constexpr float kNoWeight = 1e-6f;
    for (int j = 0; j < 4; ++j) {
        if (std::abs(wy[j]) < kNoWeight) continue;
        const int yy = std::clamp(y0 - 1 + j, 0, h - 1);
        for (int i = 0; i < 4; ++i) {
            if (std::abs(wx[i]) < kNoWeight) continue;
            const int xx = std::clamp(x0 - 1 + i, 0, w - 1);
            if (hole[static_cast<size_t>(yy) * w + xx]) return false;
        }
    }
    return true;
}

} // namespace memeget
