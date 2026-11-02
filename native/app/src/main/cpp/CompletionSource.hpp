// Where each covered pixel of logo removal takes its colour from, and which pixels the camera-motion pass may
// rework. Kept free of OpenCV so it can be tested on its own.
#pragma once

#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <limits>
#include <utility>
#include <vector>

namespace memeget {

/**
 * Mean colour difference (of 255, per channel) between RGB frames [a] and [b] of [count] pixels, over the pixels
 * where [skip] is 0; infinity when every pixel is skipped. Colour, not brightness: two frames of equal brightness
 * and different hue are not the same picture.
 */
inline float colourDifference(const uint8_t* a, const uint8_t* b, const uint8_t* skip, size_t count) {
    uint64_t sum = 0;
    size_t counted = 0;
    for (size_t i = 0; i < count; ++i) {
        if (skip[i]) continue;
        for (int c = 0; c < 3; ++c) sum += static_cast<uint64_t>(std::abs(a[i * 3 + c] - b[i * 3 + c]));
        ++counted;
    }
    return counted ? static_cast<float>(static_cast<double>(sum) / (counted * 3.0)) : std::numeric_limits<float>::infinity();
}

/** Where a covered pixel's colour comes from: a frame and a position there, in 1/16 pixel. */
struct Source {
    int16_t frame;
    int16_t x16;
    int16_t y16;
    uint8_t age;   // frames between the pixel and its source
    uint8_t found; // 0: nothing yet, 1: real background
};
static_assert(sizeof(Source) == 8, "Source is packed into scratch space");

/** Any source beats none; between two, the nearer frame wins. */
inline bool better(const Source& candidate, const Source& current) {
    return !current.found || candidate.age < current.age;
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
