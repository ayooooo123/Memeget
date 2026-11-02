// The fill shared by still images (LogoRemoval.cpp) and video completion (VideoCompletion.cpp).
#include "LogoFill.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace memeget {
namespace {

constexpr int kFillDirections = 16;
constexpr int kFillSteps = 48;
// Samples this close (of 255, per channel) count as one colour when looking for a flat background.
constexpr int kSameColour = 40;

}  // namespace

// Reads only original pixels: every fill is computed before any is written.
void fillFromSurroundings(cv::Mat& frame, const cv::Mat& hole, int holeX, int holeY, int extent) {
    const int frameW = frame.cols;
    const int frameH = frame.rows;
    const int channels = frame.channels();
    const float stride = std::max(1.0f, extent / static_cast<float>(kFillSteps));
    auto inHole = [&](int x, int y) {
        const int hx = x - holeX;
        const int hy = y - holeY;
        return hx >= 0 && hy >= 0 && hx < hole.cols && hy < hole.rows && hole.at<uint8_t>(hy, hx) != 0;
    };
    float dirX[kFillDirections];
    float dirY[kFillDirections];
    for (int d = 0; d < kFillDirections; ++d) {
        const float angle = static_cast<float>(d) * 0.39269908f;
        dirX[d] = std::cos(angle);
        dirY[d] = std::sin(angle);
    }
    struct Fill { int x; int y; uint8_t rgb[3]; };
    std::vector<Fill> fills;
    fills.reserve(static_cast<size_t>(cv::countNonZero(hole)));
    float colour[kFillDirections][3];
    float weight[kFillDirections];
    for (int hy = 0; hy < hole.rows; ++hy) {
        for (int hx = 0; hx < hole.cols; ++hx) {
            if (!hole.at<uint8_t>(hy, hx)) continue;
            const int px = holeX + hx;
            const int py = holeY + hy;
            for (int d = 0; d < kFillDirections; ++d) {
                weight[d] = 0.0f;
                for (int s = 1; s <= kFillSteps; ++s) {
                    const float dist = static_cast<float>(s) * stride;
                    const int qx = static_cast<int>(std::lround(px + dirX[d] * dist));
                    const int qy = static_cast<int>(std::lround(py + dirY[d] * dist));
                    if (qx < 0 || qy < 0 || qx >= frameW || qy >= frameH) break;
                    if (inHole(qx, qy)) continue;
                    const int sx = std::clamp(static_cast<int>(std::lround(qx + dirX[d])), 0, frameW - 1);
                    const int sy = std::clamp(static_cast<int>(std::lround(qy + dirY[d])), 0, frameH - 1);
                    const auto* sample = frame.ptr<uint8_t>(inHole(sx, sy) ? qy : sy) + (inHole(sx, sy) ? qx : sx) * channels;
                    for (int c = 0; c < 3; ++c) colour[d][c] = sample[c];
                    weight[d] = 1.0f / (dist * dist);
                    break;
                }
            }
            int valid = 0;
            int bestCount = 0;
            float best[3] = {0, 0, 0};
            float blend[3] = {0, 0, 0};
            float weights = 0.0f;
            for (int i = 0; i < kFillDirections; ++i) {
                if (weight[i] <= 0.0f) continue;
                ++valid;
                weights += weight[i];
                for (int c = 0; c < 3; ++c) blend[c] += weight[i] * colour[i][c];
                int count = 0;
                float sum[3] = {0, 0, 0};
                for (int j = 0; j < kFillDirections; ++j) {
                    if (weight[j] <= 0.0f) continue;
                    if (std::abs(colour[i][0] - colour[j][0]) < kSameColour &&
                        std::abs(colour[i][1] - colour[j][1]) < kSameColour &&
                        std::abs(colour[i][2] - colour[j][2]) < kSameColour) {
                        ++count;
                        for (int c = 0; c < 3; ++c) sum[c] += colour[j][c];
                    }
                }
                if (count > bestCount) {
                    bestCount = count;
                    for (int c = 0; c < 3; ++c) best[c] = sum[c] / count;
                }
            }
            if (valid == 0) continue;
            Fill fill{px, py, {0, 0, 0}};
            const bool flat = bestCount >= std::max(1.0f, 0.6f * valid);
            for (int c = 0; c < 3; ++c) {
                const float value = flat ? best[c] : blend[c] / weights;
                fill.rgb[c] = static_cast<uint8_t>(std::clamp(value, 0.0f, 255.0f) + 0.5f);
            }
            fills.push_back(fill);
        }
    }
    for (const auto& fill : fills) {
        auto* dst = frame.ptr<uint8_t>(fill.y) + fill.x * channels;
        dst[0] = fill.rgb[0];
        dst[1] = fill.rgb[1];
        dst[2] = fill.rgb[2];
    }
}

}  // namespace memeget
