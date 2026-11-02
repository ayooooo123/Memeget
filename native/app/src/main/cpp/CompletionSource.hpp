// Where each covered pixel of logo removal takes its colour from, and which pixels the camera-motion pass may
// rework. Kept free of OpenCV so it can be tested on its own.
#pragma once

#include <cstdint>
#include <utility>
#include <vector>

namespace memeget {

/** Where a covered pixel's colour comes from: a frame and a position there, in 1/16 pixel. */
struct Source {
    int16_t frame;
    int16_t x16;
    int16_t y16;
    uint8_t age;   // frames between the pixel and its source
    uint8_t found; // 0: nothing yet, 1: real background, 2: invented (an inpainted keyframe)
};
static_assert(sizeof(Source) == 8, "Source is packed into scratch space");

/** Real background beats invented; between two of a kind, the nearer frame wins. */
inline bool better(const Source& candidate, const Source& current) {
    if (!current.found) return true;
    if (candidate.found != current.found) return candidate.found == 1;
    return candidate.age < current.age;
}

/**
 * Which of a frame's [count] covered pixels the camera-motion pass works on ([open]), and which real sources it
 * takes out of the comparison first ([setAside], restored afterwards where nothing better is found). A source
 * reached by following flow for more than [trustedSteps] frames is set aside, so a camera-motion source from the
 * same distance wins over it. A frame whose area was taken whole from one other frame ([matched]) keeps those
 * sources however far that frame is: replacing them pixel by pixel would break the one coherent piece back into a
 * patchwork. Only its pixels with no real source are open.
 */
inline void planCameraPass(Source* sources, int count, bool matched, int trustedSteps,
                           std::vector<int>& open, std::vector<std::pair<int, Source>>& setAside) {
    open.clear();
    setAside.clear();
    for (int i = 0; i < count; ++i) {
        if (!matched && sources[i].found == 1 && sources[i].age > trustedSteps) {
            setAside.emplace_back(i, sources[i]);
            sources[i] = Source{};
        }
        if (sources[i].found != 1) open.push_back(i);
    }
}

} // namespace memeget
