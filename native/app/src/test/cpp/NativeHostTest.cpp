// Host-side checks of the OpenCV-free pieces of logo removal. Built and run by the nativeHostTest Gradle task.
#include "CompletionSource.hpp"
#include "MatchPath.hpp"
#include "SampleFootprint.hpp"

#include <cmath>
#include <cstdio>
#include <limits>
#include <vector>

namespace {

int failures = 0;

void expect(bool ok, const char* what) {
    if (!ok) {
        std::printf("FAIL: %s\n", what);
        ++failures;
    }
}

constexpr float kNone = std::numeric_limits<float>::infinity();

// A covered frame with no usable source in the middle of a run must not throw away the choices on either side.
void gapKeepsBothSides() {
    const int columns = 6;
    // Rows 0-1 fit sources 2, 3 best; row 2 has none; rows 3-4 fit 4, 5 best.
    std::vector<float> cost = {
        9, 9, 1, 9, 9, 9,
        9, 9, 9, 1, 9, 9,
        kNone, kNone, kNone, kNone, kNone, kNone,
        9, 9, 9, 9, 1, 9,
        9, 9, 9, 9, 9, 1,
    };
    const std::vector<bool> continues = {false, true, true, true, true};
    const std::vector<int> choice = memeget::matchPath(cost, 5, columns, continues, 6.0f);
    expect(choice[0] == 2 && choice[1] == 3, "the run before the gap keeps its sources");
    expect(choice[2] == -1, "the frame with no source gets none");
    expect(choice[3] == 4 && choice[4] == 5, "the run after the gap keeps its sources");
}

// Playing a source forward is preferred to a slightly better jump; a clearly better one wins.
void playsForwardUnlessClearlyBetter() {
    const int columns = 10;
    std::vector<float> cost(3 * columns, 5.0f);
    cost[0 * columns + 2] = 1;          // row 0: source 2
    cost[1 * columns + 3] = 2;          // row 1: continuing to 3 costs 2
    cost[1 * columns + 8] = 1;          // ... a jump to 8 is only a little better
    cost[2 * columns + 4] = 2;
    std::vector<int> choice = memeget::matchPath(cost, 3, columns, {false, true, true}, 6.0f);
    expect(choice[0] == 2 && choice[1] == 3 && choice[2] == 4, "keeps playing forward over a small gain");

    cost[2 * columns + 9] = 0;          // row 2: source 9 now fits far better than 4 ...
    cost[2 * columns + 4] = 30;         // ... and 4 fits badly
    choice = memeget::matchPath(cost, 3, columns, {false, true, true}, 6.0f);
    expect(choice[2] != 4, "jumps when another source fits clearly better");
}

// A run broken by a shot change starts afresh instead of being forced to continue.
void shotChangeStartsAfresh() {
    const int columns = 4;
    std::vector<float> cost = {
        1, 9, 9, 9,
        9, 9, 9, 1,
    };
    const std::vector<int> choice = memeget::matchPath(cost, 2, columns, {false, false}, 100.0f);
    expect(choice[0] == 0 && choice[1] == 3, "each shot picks its own best source");
}

// A bicubic read touches a 4x4 block: a fractional position beside the covered area reads into it.
void footprintSeesTheWholeRead() {
    const int w = 16;
    const int h = 16;
    std::vector<uint8_t> hole(w * h, 0);
    for (int y = 4; y < 12; ++y) {
        for (int x = 8; x < 12; ++x) hole[y * w + x] = 1;
    }
    expect(!memeget::footprintClear(hole.data(), w, h, 6.5f, 6.0f), "a fraction beside the hole reads into it");
    expect(memeget::footprintClear(hole.data(), w, h, 7.0f, 6.0f), "a whole pixel beside it does not");
    expect(memeget::footprintClear(hole.data(), w, h, 5.5f, 6.0f), "two pixels away is clear");
    expect(!memeget::footprintClear(hole.data(), w, h, 9.0f, 2.5f), "vertical fractions count too");
    expect(memeget::footprintClear(nullptr, w, h, 9.0f, 6.0f), "nothing covered is always clear");
}

// The camera pass offers nearer per-pixel sources only to open pixels: a frame taken whole from a distant frame
// keeps that source, while a long flow chain in an unmatched frame gives way to the nearer one.
void cameraPassKeepsWholeFrameMatches() {
    using memeget::Source;
    const Source distant{50, 160, 160, 50, 1};
    const Source nearer{3, 160, 160, 3, 1};
    auto run = [&](bool matched, Source* sources, int count) {
        std::vector<int> open;
        std::vector<std::pair<int, Source>> setAside;
        memeget::planCameraPass(sources, count, matched, 3, open, setAside);
        for (int i : open) {
            if (memeget::better(nearer, sources[i])) sources[i] = nearer;
        }
        for (const auto& [i, kept] : setAside) {
            if (memeget::better(kept, sources[i])) sources[i] = kept;
        }
    };
    Source matchedFrame[3] = {distant, distant, Source{}};
    run(true, matchedFrame, 3);
    expect(matchedFrame[0].frame == 50 && matchedFrame[1].frame == 50, "a whole-frame match keeps its distant source");
    expect(matchedFrame[2].frame == 3, "a matched frame's unfilled pixel still gets a camera source");

    Source flowFrame[1] = {distant};
    run(false, flowFrame, 1);
    expect(flowFrame[0].frame == 3, "a long flow chain gives way to a nearer camera source");
}

// A repeated frame is told by colour: equal brightness in another hue is a different picture, and pixels under the
// skip mask (the logo and the band around it) don't count.
void repeatsAreToldByColour() {
    // Reddish and greenish grey of the same brightness (0.299 R + 0.587 G + 0.114 B = 129.9 for both).
    const uint8_t reddish[3] = {200, 100, 100};
    const uint8_t greenish[3] = {100, 151, 100};
    std::vector<uint8_t> a(4 * 3), b(4 * 3);
    for (int i = 0; i < 4; ++i) {
        for (int c = 0; c < 3; ++c) {
            a[i * 3 + c] = reddish[c];
            b[i * 3 + c] = greenish[c];
        }
    }
    const std::vector<uint8_t> none(4, 0);
    expect(memeget::colourDifference(a.data(), b.data(), none.data(), 4) > 30.0f,
           "equal brightness in another colour is not a repeat");
    std::vector<uint8_t> same = a;
    same[0] = 0;                        // only a skipped pixel differs
    const std::vector<uint8_t> skipFirst = {1, 0, 0, 0};
    expect(memeget::colourDifference(a.data(), same.data(), skipFirst.data(), 4) == 0.0f,
           "skipped pixels don't count");
    const std::vector<uint8_t> all(4, 1);
    expect(std::isinf(memeget::colourDifference(a.data(), b.data(), all.data(), 4)),
           "nothing left to compare is never a repeat");
}

} // namespace

int main() {
    gapKeepsBothSides();
    playsForwardUnlessClearlyBetter();
    shotChangeStartsAfresh();
    footprintSeesTheWholeRead();
    cameraPassKeepsWholeFrameMatches();
    repeatsAreToldByColour();
    if (failures == 0) std::printf("native host tests passed\n");
    return failures == 0 ? 0 : 1;
}
