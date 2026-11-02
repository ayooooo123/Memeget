// The choice of source frame for each covered frame in logo removal: a path through a cost table that keeps
// playing a source forward frame by frame and jumps only when another fits clearly better. Kept free of OpenCV
// so it can be tested on its own.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <vector>

namespace memeget {

// [cost] holds [rows] rows of [columns] costs: row i is how badly each candidate source frame stands in for covered
// frame i (infinity where it can't). [continues][i] says row i follows row i - 1 directly (the next frame, same
// shot). Moving from source s - 1 to s between consecutive rows is free; jumping to any source costs [jumpCost].
// Returns the chosen source per row, -1 where none can be. A row with no usable source ends the run before it,
// which keeps its choices, and the run after it starts afresh.
inline std::vector<int> matchPath(const std::vector<float>& cost, size_t rows, int columns,
                                  const std::vector<bool>& continues, float jumpCost) {
    constexpr float kNone = std::numeric_limits<float>::infinity();
    const size_t n = static_cast<size_t>(columns);
    std::vector<int> choice(rows, -1);
    if (rows == 0 || columns <= 0) return choice;
    std::vector<float> best(n, kNone);
    std::vector<float> next(n);
    std::vector<int32_t> back(rows * n, -1);
    size_t chainStart = 0;
    bool inChain = false;

    // Backtracks the run [chainStart, end) from its cheapest last source.
    auto finishChain = [&](size_t end) {
        if (!inChain || end == chainStart) return;
        int s = static_cast<int>(std::min_element(best.begin(), best.end()) - best.begin());
        if (!std::isfinite(best[s])) return;
        for (size_t i = end; i-- > chainStart;) {
            choice[i] = s;
            if (i > chainStart) s = back[i * n + s];
        }
    };

    for (size_t i = 0; i < rows; ++i) {
        const float* c = &cost[i * n];
        const bool usable = std::any_of(c, c + n, [](float v) { return std::isfinite(v); });
        if (!usable) {
            // Nothing stands in here: what came before is a finished run, and nothing carries across.
            finishChain(i);
            inChain = false;
            continue;
        }
        if (!inChain || !continues[i]) {
            finishChain(i);
            chainStart = i;
            inChain = true;
            std::copy(c, c + n, next.begin());
        } else {
            const size_t argMin = static_cast<size_t>(std::min_element(best.begin(), best.end()) - best.begin());
            const float jump = best[argMin] + jumpCost;
            for (size_t s = 0; s < n; ++s) {
                if (!std::isfinite(c[s])) {
                    next[s] = kNone;
                    continue;
                }
                const float stay = s > 0 ? best[s - 1] : kNone;
                if (stay <= jump) {
                    next[s] = stay + c[s];
                    back[i * n + s] = static_cast<int32_t>(s) - 1;
                } else {
                    next[s] = jump + c[s];
                    back[i * n + s] = static_cast<int32_t>(argMin);
                }
            }
        }
        best.swap(next);
    }
    finishChain(rows);
    return choice;
}

} // namespace memeget
