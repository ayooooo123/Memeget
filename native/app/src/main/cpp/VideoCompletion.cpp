// Video completion for logo removal: rebuilds what a logo covers in every frame. Real background comes first: whole
// matching frames, optical flow and camera motion find the real pixel each covered pixel lines up with elsewhere in
// the clip. A frame keeps that real fill only where it continues the footage across the edge of the area; the rest
// is invented per frame by a learned inpainting model (run by the caller), or the plain directional fill without
// one. The finished fill is then smoothed through time along the flow, so it moves with the footage and doesn't
// flicker.
//
// All frames are crops of one region around the logo at native resolution, held in memory-mapped scratch files.
#include <jni.h>
#include <opencv2/imgproc.hpp>
#include <opencv2/photo.hpp>
#include <opencv2/video/tracking.hpp>

#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <exception>
#include <memory>
#include <stdexcept>
#include <string>
#include <limits>
#include <unordered_map>
#include <vector>

#include "JniStrings.hpp"
#include "CompletionSource.hpp"
#include "LogoFill.hpp"
#include "MatchPath.hpp"
#include "SampleFootprint.hpp"

namespace {

void throwJava(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/lang/RuntimeException");
    if (type) env->ThrowNew(type, message);
}

/** A scratch file mapped into memory; unlinked at once, so it disappears with the process even after a crash. */
class Scratch {
public:
    Scratch(const std::string& path, size_t bytes) : bytes_(std::max<size_t>(bytes, 1)) {
        const int fd = ::open(path.c_str(), O_RDWR | O_CREAT | O_TRUNC, 0600);
        if (fd < 0) throw std::runtime_error("Couldn't create scratch space for the video");
        ::unlink(path.c_str());
        if (::ftruncate(fd, static_cast<off_t>(bytes_)) != 0) {
            ::close(fd);
            throw std::runtime_error("Not enough storage to rebuild the background");
        }
        void* data = ::mmap(nullptr, bytes_, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        ::close(fd);
        if (data == MAP_FAILED) throw std::runtime_error("Couldn't map scratch space for the video");
        data_ = static_cast<uint8_t*>(data);
    }
    ~Scratch() { ::munmap(data_, bytes_); }
    Scratch(const Scratch&) = delete;
    Scratch& operator=(const Scratch&) = delete;
    uint8_t* data() const { return data_; }

private:
    size_t bytes_;
    uint8_t* data_ = nullptr;
};

using memeget::Source;
using memeget::better;

constexpr float kConsistencyPx = 2.0f;
// Flow near a static logo follows the logo, not the footage: everything this far from it is replaced by the
// region's camera motion (full-resolution pixels).
constexpr int kFlowHoleGrowPx = 12;
// Flow is computed at half resolution and kept for every frame pair.
constexpr int kFlowScale = 2;
constexpr int kRansacIterations = 200;
constexpr float kRansacInlierPx = 0.75f;
// Corners tracked to align two frames, and how far (half-resolution pixels) a corner may land from where it started
// when tracked there and back.
constexpr int kTrackCorners = 300;
constexpr float kTrackRoundTripPx = 0.5f;
// Following flow for more frames than this drifts; past it, composed camera motion is preferred.
constexpr int kTrustedFlowSteps = 3;
// The footage around the covered area moves on its own (a person, something passing in front) when warping it by
// the camera motion misses by more than this ratio of its own flow's miss plus this much brightness (of 255) per
// pixel. Then camera motion no longer says what is behind the logo.
constexpr double kOwnMotionRatio = 1.25;
constexpr double kOwnMotionLevel = 1.0;
// It also moves on its own when the camera motion misses by this much more than the flow (brightness, of 255) on
// more than this share of the pixels around the area (a hand or a curtain over part of a still background), or on
// more than the second share in the median frame (then throughout the video). Measured: a camera pan over fine
// texture missed on 0.6 % of them in the median frame and 1.7 % at most in nine frames of ten; a person moving
// in front of a still camera, and a curtain passing a window, on 2.3 % and 2.9 %.
constexpr float kOwnMotionMissLevel = 6.0f;
constexpr double kOwnMotionMissShare = 0.05;
constexpr double kOwnMotionVideoShare = 0.015;
// A frame's real fill is used only when it continues the footage across the edge of the covered area: the median
// step from a filled pixel to the real pixel next to it may exceed the footage's own step between the next two
// real pixels by at most this much (brightness, of 255), over at least this share of the area. Real fill that is
// shifted or comes from a frame where something else was there (a leg, a sign seen from elsewhere) jumps at the
// edge; on a ski clip, Sintel and a camera pan, frames below the level were 2.2 from the truth against the fill
// model's 3.8, frames above it 17.3 against 12.1.
constexpr float kTrustSeamLevel = 2.5f;
constexpr double kTrustRealShare = 0.5;
// Smoothing of the finished fill through time, both ways. A neighbour that repeats the frame (a duplicate from a
// frame-rate change: mean colour difference outside the area at most kRepeatLevel, full resolution; 51 of a
// re-encoded clip's duplicates fell below it, a slow pan over flat sky never below 0.38) is carried whole, so the
// repeat shows the same fill instead of a twitch. Any other neighbour is carried unmoved or along the flow, whichever
// lines up better with the frame in a band around the area (mean brightness miss, of 255, half resolution):
// kCloseShare of it when that misses by at most kCloseMiss, kFarShare up to kFarMiss, and not at all beyond (a cut,
// or motion the flow can't follow). Carrying along the flow where the footage holds still drags the fill about.
constexpr float kRepeatLevel = 0.25f;
constexpr float kCloseMiss = 3.0f;
constexpr float kCloseShare = 0.6f;
constexpr float kFarMiss = 12.0f;
constexpr float kFarShare = 0.3f;
// Each separate piece of a frame's covered area may move this far (full-resolution pixels) onto where that piece of
// the logo really is (see alignHoles), when that matches the piece's usual look by this factor better than where
// tracking put it; pieces smaller than the last value are left alone. On a sticker that slides 4 px into place over
// its first frames, next to a still part of the same watermark, exactly those frames moved, and only the sticker.
constexpr int kHoleShiftPx = 5;
constexpr float kHoleShiftGain = 0.7f;
constexpr int kHoleShiftMinPx = 30;
// Frames this far apart are also aligned directly, so long jumps compose few motions and drift less.
constexpr int kAnchorStride = 8;
// Neighbouring frames whose flows there and back agree on less than this share of the picture are a hard cut.
constexpr float kCutConsistentShare = 0.2f;
// How many frames away, at most, camera motion looks for a frame that shows a covered pixel. Footage where the
// logo's area is never shown would otherwise check every pair of frames, which on a minute-long clip takes hours;
// what is that far away has usually changed anyway, and the fill model covers it.
constexpr int kMaxCameraReach = 90;
// Matching whole frames as sources (see matchFrames): the ring around the covered area compared (pixels wide, full
// resolution), how many ring and area points are sampled, how much a brightness change counts against a match,
// what a jump to another source costs (brightness levels, of 255), and how far a match may be nudged (pixels).
constexpr int kMatchRingPx = 8;
constexpr int kMatchRingSamples = 240;
constexpr int kMatchAreaSamples = 80;
constexpr float kMatchBrightnessWeight = 0.5f;
constexpr float kMatchJumpCost = 12.0f;
constexpr int kMatchShiftPx = 4;
// An edge sample for the seam blend further than this (brightness, of 255) from the typical one is left out.
constexpr float kSeamOutlier = 18.0f;

/** x' = a x - b y + c, y' = b x + a y + d: pan, zoom and roll of the camera over the region. */
struct Similarity {
    double a = 1, b = 0, c = 0, d = 0;
    void apply(double x, double y, double& ox, double& oy) const {
        ox = a * x - b * y + c;
        oy = b * x + a * y + d;
    }
};

/** The similarity that moves the most points within kRansacInlierPx of where their flow says; false if none. */
bool fitSimilarity(const std::vector<cv::Point2f>& from, const std::vector<cv::Point2f>& to, Similarity& out) {
    const int n = static_cast<int>(from.size());
    if (n < 12) return false;
    uint32_t seed = 0x9e3779b9u;
    auto next = [&]() { seed ^= seed << 13; seed ^= seed >> 17; seed ^= seed << 5; return seed; };
    int bestCount = 0;
    Similarity best;
    for (int it = 0; it < kRansacIterations; ++it) {
        const int i = static_cast<int>(next() % n);
        const int j = static_cast<int>(next() % n);
        const double dx = from[j].x - from[i].x;
        const double dy = from[j].y - from[i].y;
        const double ex = to[j].x - to[i].x;
        const double ey = to[j].y - to[i].y;
        const double norm = dx * dx + dy * dy;
        if (norm < 16.0) continue;
        Similarity s;
        s.a = (dx * ex + dy * ey) / norm;
        s.b = (dx * ey - dy * ex) / norm;
        s.c = to[i].x - (s.a * from[i].x - s.b * from[i].y);
        s.d = to[i].y - (s.b * from[i].x + s.a * from[i].y);
        int count = 0;
        for (int k = 0; k < n; ++k) {
            double px, py;
            s.apply(from[k].x, from[k].y, px, py);
            if (std::abs(px - to[k].x) + std::abs(py - to[k].y) < kRansacInlierPx) ++count;
        }
        if (count > bestCount) {
            bestCount = count;
            best = s;
        }
    }
    if (bestCount < std::max(12, n / 4)) return false;
    // Least squares over the inliers.
    cv::Mat A(0, 4, CV_64F);
    cv::Mat B(0, 1, CV_64F);
    for (int k = 0; k < n; ++k) {
        double px, py;
        best.apply(from[k].x, from[k].y, px, py);
        if (std::abs(px - to[k].x) + std::abs(py - to[k].y) >= kRansacInlierPx) continue;
        const double rowX[4] = {from[k].x, -from[k].y, 1, 0};
        const double rowY[4] = {from[k].y, from[k].x, 0, 1};
        A.push_back(cv::Mat(1, 4, CV_64F, const_cast<double*>(rowX)).clone());
        B.push_back(static_cast<double>(to[k].x));
        A.push_back(cv::Mat(1, 4, CV_64F, const_cast<double*>(rowY)).clone());
        B.push_back(static_cast<double>(to[k].y));
    }
    cv::Mat solution;
    if (!cv::solve(A, B, solution, cv::DECOMP_SVD)) {
        out = best;
        return true;
    }
    out.a = solution.at<double>(0);
    out.b = solution.at<double>(1);
    out.c = solution.at<double>(2);
    out.d = solution.at<double>(3);
    return true;
}

/** Affine camera motion: x' = m0 x + m1 y + m2, y' = m3 x + m4 y + m5. */
struct Motion {
    double m[6] = {1, 0, 0, 0, 1, 0};
    void apply(double x, double y, double& ox, double& oy) const {
        ox = m[0] * x + m[1] * y + m[2];
        oy = m[3] * x + m[4] * y + m[5];
    }
    static Motion of(const Similarity& s) {
        Motion out;
        out.m[0] = s.a; out.m[1] = -s.b; out.m[2] = s.c;
        out.m[3] = s.b; out.m[4] = s.a; out.m[5] = s.d;
        return out;
    }
    /** The same motion with coordinates multiplied by [factor]. */
    Motion scaled(double factor) const {
        Motion out = *this;
        out.m[2] *= factor;
        out.m[5] *= factor;
        return out;
    }
    /** [outer] after [inner]. */
    static Motion compose(const Motion& outer, const Motion& inner) {
        Motion o;
        o.m[0] = outer.m[0] * inner.m[0] + outer.m[1] * inner.m[3];
        o.m[1] = outer.m[0] * inner.m[1] + outer.m[1] * inner.m[4];
        o.m[2] = outer.m[0] * inner.m[2] + outer.m[1] * inner.m[5] + outer.m[2];
        o.m[3] = outer.m[3] * inner.m[0] + outer.m[4] * inner.m[3];
        o.m[4] = outer.m[3] * inner.m[1] + outer.m[4] * inner.m[4];
        o.m[5] = outer.m[3] * inner.m[2] + outer.m[4] * inner.m[5] + outer.m[5];
        return o;
    }
    Motion inverse() const {
        const double det = m[0] * m[4] - m[1] * m[3];
        Motion o;
        if (std::abs(det) < 1e-9) return o;
        o.m[0] = m[4] / det; o.m[1] = -m[1] / det;
        o.m[3] = -m[3] / det; o.m[4] = m[0] / det;
        o.m[2] = -(o.m[0] * m[2] + o.m[1] * m[5]);
        o.m[5] = -(o.m[3] * m[2] + o.m[4] * m[5]);
        return o;
    }
};

/**
 * The affine motion that moves the most points within kRansacInlierPx of their matches, refit by least squares
 * over those; false if too few agree. Written out here because OpenCV's estimators live in a module this library
 * doesn't link.
 */
bool fitAffine(const std::vector<cv::Point2f>& from, const std::vector<cv::Point2f>& to, Motion& out) {
    const int n = static_cast<int>(from.size());
    if (n < 12) return false;
    uint32_t seed = 0x2545f491u;
    auto next = [&]() { seed ^= seed << 13; seed ^= seed >> 17; seed ^= seed << 5; return seed; };
    auto solveRows = [](const std::vector<int>& picks, const std::vector<cv::Point2f>& a,
                        const std::vector<cv::Point2f>& b, Motion& m) {
        cv::Mat A(static_cast<int>(picks.size()), 3, CV_64F);
        cv::Mat bx(static_cast<int>(picks.size()), 1, CV_64F);
        cv::Mat by(static_cast<int>(picks.size()), 1, CV_64F);
        for (int r = 0; r < static_cast<int>(picks.size()); ++r) {
            const int k = picks[r];
            A.at<double>(r, 0) = a[k].x;
            A.at<double>(r, 1) = a[k].y;
            A.at<double>(r, 2) = 1.0;
            bx.at<double>(r) = b[k].x;
            by.at<double>(r) = b[k].y;
        }
        cv::Mat sx;
        cv::Mat sy;
        if (!cv::solve(A, bx, sx, cv::DECOMP_SVD) || !cv::solve(A, by, sy, cv::DECOMP_SVD)) return false;
        for (int i = 0; i < 3; ++i) {
            m.m[i] = sx.at<double>(i);
            m.m[3 + i] = sy.at<double>(i);
        }
        return std::isfinite(m.m[0]) && std::isfinite(m.m[4]);
    };
    std::vector<int> picks(3);
    std::vector<int> bestInliers;
    for (int it = 0; it < kRansacIterations; ++it) {
        for (int& p : picks) p = static_cast<int>(next() % n);
        // Three points spread out enough to pin an affine motion down.
        const double area = std::abs((from[picks[1]].x - from[picks[0]].x) * (from[picks[2]].y - from[picks[0]].y) -
                                     (from[picks[2]].x - from[picks[0]].x) * (from[picks[1]].y - from[picks[0]].y));
        if (area < 64.0) continue;
        Motion m;
        if (!solveRows(picks, from, to, m)) continue;
        std::vector<int> inliers;
        for (int k = 0; k < n; ++k) {
            double px, py;
            m.apply(from[k].x, from[k].y, px, py);
            if (std::abs(px - to[k].x) + std::abs(py - to[k].y) < kRansacInlierPx) inliers.push_back(k);
        }
        if (inliers.size() > bestInliers.size()) bestInliers.swap(inliers);
    }
    if (static_cast<int>(bestInliers.size()) < std::max(12, n / 4)) return false;
    return solveRows(bestInliers, from, to, out);
}

/**
 * [initial] refined by tracking corners of [from] into [to] (same size, half resolution) over [valid] pixels and
 * fitting an affine motion to the tracks that hold both ways. A tight fit matters: motions are composed over dozens
 * of frames. Keeps [initial] when too little can be tracked or the result wanders off. (OpenCV's ECC alignment was
 * used here, but it raises an error on footage it can't align, and this build of OpenCV turns errors into crashes.)
 */
Motion refineAlignment(const cv::Mat& from, const cv::Mat& to, const cv::Mat& valid, const Motion& initial) {
    std::vector<cv::Point2f> corners;
    cv::goodFeaturesToTrack(from, corners, kTrackCorners, 0.01, 4.0, valid);
    if (corners.size() < 12) return initial;
    std::vector<cv::Point2f> ahead(corners.size());
    for (size_t i = 0; i < corners.size(); ++i) {
        double x, y;
        initial.apply(corners[i].x, corners[i].y, x, y);
        ahead[i] = cv::Point2f(static_cast<float>(x), static_cast<float>(y));
    }
    const cv::TermCriteria criteria(cv::TermCriteria::COUNT | cv::TermCriteria::EPS, 30, 0.01);
    std::vector<uchar> status;
    std::vector<float> error;
    cv::calcOpticalFlowPyrLK(from, to, corners, ahead, status, error, cv::Size(21, 21), 3, criteria,
                             cv::OPTFLOW_USE_INITIAL_FLOW);
    std::vector<cv::Point2f> back = corners;
    std::vector<uchar> backStatus;
    cv::calcOpticalFlowPyrLK(to, from, ahead, back, backStatus, error, cv::Size(21, 21), 3, criteria,
                             cv::OPTFLOW_USE_INITIAL_FLOW);
    std::vector<cv::Point2f> a;
    std::vector<cv::Point2f> b;
    for (size_t i = 0; i < corners.size(); ++i) {
        if (!status[i] || !backStatus[i]) continue;
        if (std::abs(back[i].x - corners[i].x) + std::abs(back[i].y - corners[i].y) > kTrackRoundTripPx) continue;
        a.push_back(corners[i]);
        b.push_back(ahead[i]);
    }
    Motion refined;
    if (!fitAffine(a, b, refined)) return initial;
    double ix, iy, rx, ry;
    initial.apply(from.cols / 2.0, from.rows / 2.0, ix, iy);
    refined.apply(from.cols / 2.0, from.rows / 2.0, rx, ry);
    return std::abs(ix - rx) + std::abs(iy - ry) < 3.0 ? refined : initial;
}

class Completion {
public:
    Completion(int width, int height, int frames, const std::string& dir)
        : w_(width), h_(height), n_(frames), px_(static_cast<size_t>(width) * height), dir_(dir),
          rgb_(dir + "/completion_rgb", static_cast<size_t>(frames) * px_ * 3),
          holes_(dir + "/completion_holes", static_cast<size_t>(frames) * px_),
          complete_(frames, 0), holeCount_(frames, 0) {
        flow_ = cv::DISOpticalFlow::create(cv::DISOpticalFlow::PRESET_MEDIUM);
    }

    void addFrame(int t, const uint8_t* rgb, const uint8_t* hole) {
        std::memcpy(frameRgb(t), rgb, px_ * 3);
        int count = 0;
        uint8_t* dst = holes_.data() + static_cast<size_t>(t) * px_;
        for (size_t i = 0; i < px_; ++i) {
            dst[i] = hole[i] ? 1 : 0;
            count += dst[i];
        }
        holeCount_[t] = count;
    }

    /** Lays out the per-pixel sources once every frame is in (after moving the covered areas, see alignHoles). */
    void seal(const std::string& dir, const std::atomic<bool>* cancelled) {
        alignHoles(cancelled);
        offsets_.assign(n_ + 1, 0);
        for (int t = 0; t < n_; ++t) offsets_[t + 1] = offsets_[t] + static_cast<size_t>(holeCount_[t]);
        indices_ = std::make_unique<Scratch>(dir + "/completion_index", offsets_[n_] * sizeof(int32_t));
        sources_ = std::make_unique<Scratch>(dir + "/completion_sources", offsets_[n_] * sizeof(Source));
        auto* index = reinterpret_cast<int32_t*>(indices_->data());
        for (int t = 0; t < n_; ++t) {
            const uint8_t* hole = holes_.data() + static_cast<size_t>(t) * px_;
            size_t slot = offsets_[t];
            for (size_t i = 0; i < px_; ++i) {
                if (hole[i]) index[slot++] = static_cast<int32_t>(i);
            }
        }
        std::memset(sources_->data(), 0, offsets_[n_] * sizeof(Source));
    }

    /**
     * Moves each separate piece of every frame's covered area, within kHoleShiftPx, onto where that piece of the logo
     * really is in the frame. Tracking places the whole logo at once, so a piece that slides in on its own (an
     * animated sticker beside a still handle) leaves its edge uncovered. An overlay's pixels look the same in every
     * frame: a piece's usual look is the median over the frames covering it at the same place, and a frame takes the
     * shift that matches that look clearly better (kHoleShiftGain) than where tracking put it.
     */
    void alignHoles(const std::atomic<bool>* cancelled) {
        // Frames covered at the same place, by their covered area's contents.
        std::unordered_map<uint64_t, std::vector<int>> groups;
        for (int t = 0; t < n_; ++t) {
            if (holeCount_[t] == 0) continue;
            const uint8_t* hole = holeOf(t);
            uint64_t hash = 1469598103934665603ull;
            for (size_t i = 0; i < px_; ++i) hash = (hash ^ hole[i]) * 1099511628211ull;
            auto& frames = groups[hash];
            if (!frames.empty() && std::memcmp(holeOf(frames.front()), hole, px_) != 0) continue;
            frames.push_back(t);
        }
        std::vector<uint8_t> moved(px_);
        for (const auto& entry : groups) {
            if (cancelled && cancelled->load()) throw std::runtime_error("cancelled");
            const std::vector<int>& frames = entry.second;
            cv::Mat labels;
            const int pieces = cv::connectedComponents(
                cv::Mat(h_, w_, CV_8UC1, const_cast<uint8_t*>(holeOf(frames.front()))), labels, 8, CV_32S);
            std::vector<std::vector<int>> pixels(pieces);
            for (int i = 0; i < static_cast<int>(px_); ++i) {
                const int label = labels.at<int>(i / w_, i % w_);
                if (label > 0) pixels[label].push_back(i);
            }
            std::vector<std::array<int, 2>> shift(frames.size() * pieces, {0, 0});
            bool any = false;
            for (int piece = 1; piece < pieces; ++piece) {
                const std::vector<int>& at = pixels[piece];
                if (static_cast<int>(at.size()) < kHoleShiftMinPx) continue;
                // The usual look: per pixel and channel, the median over up to 64 frames spread through the group.
                const size_t step = std::max<size_t>(1, frames.size() / 64);
                std::vector<uint8_t> values;
                std::vector<float> look(at.size() * 3);
                for (size_t k = 0; k < at.size() * 3; ++k) {
                    values.clear();
                    for (size_t f = 0; f < frames.size(); f += step) {
                        values.push_back(frameRgb(frames[f])[static_cast<size_t>(at[k / 3]) * 3 + k % 3]);
                    }
                    std::nth_element(values.begin(), values.begin() + values.size() / 2, values.end());
                    look[k] = values[values.size() / 2];
                }
                int left = w_, top = h_, right = -1, bottom = -1;
                for (int i : at) {
                    left = std::min(left, i % w_); right = std::max(right, i % w_);
                    top = std::min(top, i / w_); bottom = std::max(bottom, i / w_);
                }
                for (size_t f = 0; f < frames.size(); ++f) {
                    const uint8_t* frame = frameRgb(frames[f]);
                    auto miss = [&](int dx, int dy) {
                        double sum = 0;
                        for (size_t k = 0; k < at.size(); ++k) {
                            const uint8_t* p = frame + static_cast<size_t>(at[k] + dy * w_ + dx) * 3;
                            for (int c = 0; c < 3; ++c) sum += std::abs(p[c] - look[k * 3 + c]);
                        }
                        return sum;
                    };
                    const double inPlace = miss(0, 0);
                    double best = inPlace;
                    std::array<int, 2> bestShift = {0, 0};
                    for (int dy = -kHoleShiftPx; dy <= kHoleShiftPx; ++dy) {
                        for (int dx = -kHoleShiftPx; dx <= kHoleShiftPx; ++dx) {
                            if ((dx == 0 && dy == 0) || left + dx < 0 || top + dy < 0 || right + dx >= w_ ||
                                bottom + dy >= h_) {
                                continue;
                            }
                            const double m = miss(dx, dy);
                            if (m < best) {
                                best = m;
                                bestShift = {dx, dy};
                            }
                        }
                    }
                    if (best < kHoleShiftGain * inPlace) {
                        shift[f * pieces + piece] = bestShift;
                        any = true;
                    }
                }
            }
            if (!any) continue;
            for (size_t f = 0; f < frames.size(); ++f) {
                bool shifted = false;
                for (int piece = 1; piece < pieces; ++piece) shifted = shifted || shift[f * pieces + piece] != std::array<int, 2>{0, 0};
                if (!shifted) continue;
                std::fill(moved.begin(), moved.end(), 0);
                for (int piece = 1; piece < pieces; ++piece) {
                    const auto [dx, dy] = shift[f * pieces + piece];
                    for (int i : pixels[piece]) moved[i + dy * w_ + dx] = 1;
                }
                uint8_t* hole = holes_.data() + static_cast<size_t>(frames[f]) * px_;
                std::memcpy(hole, moved.data(), px_);
                holeCount_[frames[f]] = static_cast<int>(std::count(moved.begin(), moved.end(), 1));
            }
        }
    }

    /** Every pixel any frame covers (after alignHoles), into [out]. */
    void coveredUnion(uint8_t* out) const {
        std::memset(out, 0, px_);
        for (int t = 0; t < n_; ++t) {
            const uint8_t* hole = holeOf(t);
            for (size_t i = 0; i < px_; ++i) out[i] |= hole[i];
        }
    }

    /** Flow between every pair of neighbouring frames, both ways, kept at half resolution. */
    void computeFlows(const std::string& dir, const std::atomic<bool>* cancelled) {
        fw_ = (w_ + kFlowScale - 1) / kFlowScale;
        fh_ = (h_ + kFlowScale - 1) / kFlowScale;
        const size_t flowBytes = static_cast<size_t>(fw_) * fh_ * 2 * sizeof(float);
        flows_ = std::make_unique<Scratch>(dir + "/completion_flows", static_cast<size_t>(std::max(n_ - 1, 1)) * 2 * flowBytes);
        forwardMotion_.assign(n_, Motion());
        movesOnOwn_.assign(n_, 0);
        missedShare_.assign(n_, 0.0f);
        slotMotion_.assign(static_cast<size_t>(std::max(n_ - 1, 1)) * 2, Motion());
        slotGood_.assign(static_cast<size_t>(std::max(n_ - 1, 1)) * 2, false);
        backwardMotion_.assign(n_, Motion());
        forwardGood_.assign(n_, false);
        backwardGood_.assign(n_, false);
        cutAfter_.assign(n_, false);
        matched_.assign(n_, 0);
        matchFrom_.assign(n_, -1);
        cv::Mat previous = smallGrey(0);
        for (int t = 0; t + 1 < n_; ++t) {
            if (cancelled && cancelled->load()) throw std::runtime_error("cancelled");
            cv::Mat current = smallGrey(t + 1);
            bool good = false;
            cv::Mat forward = flowOf(t, true, previous, current, forwardMotion_[t], good, true);
            forwardGood_[t] = good;
            // The way back is the inverse motion (aligned once); its flow is still computed for consistency checks.
            Motion unused;
            cv::Mat backward = flowOf(t + 1, false, current, previous, unused, good, false);
            backwardGood_[t + 1] = forwardGood_[t];
            backwardMotion_[t + 1] = forwardMotion_[t].inverse();
            // A hard cut: the flows there and back don't agree on most of the picture. Nothing is carried across
            // one, by flow, camera motion or the smoothing.
            cutAfter_[t] = consistentShare(forward, backward, nearHole(t) | nearHole(t + 1)) < kCutConsistentShare;
            if (cutAfter_[t]) {
                forwardGood_[t] = false;
                backwardGood_[t + 1] = false;
            }
            forward.copyTo(flowAt(t, true));
            backward.copyTo(flowAt(t + 1, false));
            previous = current;
        }
        // Footage that moves on its own in most frames (a person in front of a still camera) does so throughout;
        // a share of misses that small elsewhere is texture the flow overfits.
        std::vector<float> shares;
        for (int t = 0; t < n_; ++t) {
            if (!complete_[t] && holeCount_[t] > 0) shares.push_back(missedShare_[t]);
        }
        if (!shares.empty()) {
            std::nth_element(shares.begin(), shares.begin() + shares.size() / 2, shares.end());
            const bool throughout = shares[shares.size() / 2] > kOwnMotionVideoShare;
            for (int t = 0; t < n_; ++t) {
                if (throughout || missedShare_[t] > kOwnMotionMissShare) movesOnOwn_[t] = 1;
            }
        }
        for (int t = 0; t < n_; ++t) {
            if (t + 1 < n_) completeHoleFlow(t, true);
            if (t > 0) completeHoleFlow(t, false);
        }
        // Frames kAnchorStride apart, aligned directly (they still overlap) starting from the composed motion.
        anchorMotion_.assign(n_, Motion());
        anchorGood_.assign(n_, false);
        for (int a = 0; a + kAnchorStride < n_; a += kAnchorStride) {
            if (cancelled && cancelled->load()) throw std::runtime_error("cancelled");
            const int b = a + kAnchorStride;
            Motion composed;
            bool good = true;
            for (int k = a; k < b; ++k) {
                good = good && forwardGood_[k];
                composed = Motion::compose(forwardMotion_[k], composed);
            }
            if (!good) continue;
            cv::Mat valid;
            cv::bitwise_not(nearHole(a) | nearHole(b), valid);
            anchorMotion_[a] = refineAlignment(smallGrey(a), smallGrey(b), valid, composed.scaled(1.0 / kFlowScale)).scaled(kFlowScale);
            anchorGood_[a] = true;
        }
        // Each frame's camera motion from the first frame of its shot (anchor jumps where they fitted, so it drifts
        // little), so the motion between any two frames of a shot is one composition.
        fromShotStart_.assign(n_, Motion());
        shotOf_.assign(n_, 0);
        for (int t = 1; t < n_; ++t) {
            if (!forwardGood_[t - 1]) {
                shotOf_[t] = shotOf_[t - 1] + 1;
                continue;
            }
            shotOf_[t] = shotOf_[t - 1];
            const int a = t - kAnchorStride;
            if (t % kAnchorStride == 0 && a >= 0 && anchorGood_[a] && shotOf_[a] == shotOf_[t]) {
                fromShotStart_[t] = Motion::compose(anchorMotion_[a], fromShotStart_[a]);
            } else {
                fromShotStart_[t] = Motion::compose(forwardMotion_[t - 1], fromShotStart_[t - 1]);
            }
        }
    }

    /** Half-resolution mask of the pixels near frame [t]'s covered area (where flow follows the logo). */
    cv::Mat nearHole(int t) const {
        cv::Mat near = cv::Mat::zeros(fh_, fw_, CV_8UC1);
        if (holeCount_[t] == 0) return near;
        cv::Mat hole(h_, w_, CV_8UC1, const_cast<uint8_t*>(holeOf(t)));
        cv::Mat smallHole;
        cv::resize(hole, smallHole, cv::Size(fw_, fh_), 0, 0, cv::INTER_NEAREST);
        const int grow = kFlowHoleGrowPx / kFlowScale;
        cv::dilate(smallHole, near, cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(2 * grow + 1, 2 * grow + 1)));
        return near > 0;
    }

    /**
     * Share of the pixels outside [skip] (half resolution) whose flow [forward] and the flow [backward] at its end
     * lead back to within a pixel of where they started. Footage seen in both frames scores high, whatever moves;
     * two unrelated pictures (a cut) score low.
     */
    float consistentShare(const cv::Mat& forward, const cv::Mat& backward, const cv::Mat& skip) const {
        int counted = 0;
        int consistent = 0;
        for (int y = 0; y < fh_; y += 2) {
            for (int x = 0; x < fw_; x += 2) {
                if (skip.at<uint8_t>(y, x)) continue;
                ++counted;
                const auto f = forward.at<cv::Vec2f>(y, x);
                const float qx = x + f[0];
                const float qy = y + f[1];
                if (qx < 0 || qy < 0 || qx > fw_ - 1 || qy > fh_ - 1) continue;
                const auto g = backward.at<cv::Vec2f>(static_cast<int>(qy + 0.5f), static_cast<int>(qx + 0.5f));
                if (std::abs(qx + g[0] - x) + std::abs(qy + g[1] - y) <= 1.0f) ++consistent;
            }
        }
        return counted ? static_cast<float>(consistent) / counted : 1.0f;
    }

    /**
     * Camera motion from frame [t] to frame [s] (full resolution): neighbour motions to the nearest anchor, anchor
     * jumps, then neighbour motions again. False when a step on the way didn't fit.
     */
    bool transformBetween(int t, int s, Motion& out) const {
        Motion total;
        int at = t;
        const int dir = s > t ? 1 : -1;
        auto stepPair = [&](int from) -> bool {
            const bool good = dir > 0 ? forwardGood_[from] : backwardGood_[from];
            if (!good) return false;
            total = Motion::compose(dir > 0 ? forwardMotion_[from] : backwardMotion_[from], total);
            return true;
        };
        // Neighbour steps until an anchor (or the target) is reached.
        while (at != s && (at % kAnchorStride != 0 || std::abs(s - at) < kAnchorStride)) {
            if (!stepPair(at)) return false;
            at += dir;
        }
        // Anchor jumps.
        while (std::abs(s - at) >= kAnchorStride && at % kAnchorStride == 0) {
            const int a = dir > 0 ? at : at - kAnchorStride;
            if (a < 0 || !anchorGood_[a]) break;
            total = Motion::compose(dir > 0 ? anchorMotion_[a] : anchorMotion_[a].inverse(), total);
            at += dir * kAnchorStride;
        }
        while (at != s) {
            if (!stepPair(at)) return false;
            at += dir;
        }
        out = total;
        return true;
    }

    /**
     * Each covered frame takes its whole area from one frame of the same shot that shows all of it and whose
     * surroundings of the area look most like its own (aligned by the camera motion). Taken whole, the fill is one
     * coherent piece of footage; pixels drawn each from the nearest frame that shows them form a patchwork once
     * anything in the picture moves on its own, and flow carried over many frames smears into a soft patch. The
     * choices form a path that keeps playing a source forward frame by frame and jumps only when another fits
     * clearly better, so the fill moves like footage. Frames with no such source are left to the other passes.
     */
    void matchFrames(const std::atomic<bool>* cancelled) {
        std::vector<int> covered;
        for (int t = 0; t < n_; ++t) {
            if (!complete_[t] && holeCount_[t] > 0) covered.push_back(t);
        }
        if (covered.empty()) return;
        constexpr float kNone = std::numeric_limits<float>::infinity();
        const size_t m = covered.size();
        // Sparse full-resolution samples of each covered frame's area and of a ring around it, and the ring's
        // brightness at half resolution.
        std::vector<std::vector<cv::Point>> areaPts(m);
        std::vector<std::vector<cv::Point>> ringPts(m);
        std::vector<std::vector<float>> ringGrey(m);
        std::vector<float> ringMean(m, 0.0f);
        const cv::Mat ringKernel = cv::getStructuringElement(
            cv::MORPH_ELLIPSE, cv::Size(2 * kMatchRingPx + 1, 2 * kMatchRingPx + 1));
        for (size_t i = 0; i < m; ++i) {
            const int t = covered[i];
            cv::Mat hole(h_, w_, CV_8UC1, const_cast<uint8_t*>(holeOf(t)));
            cv::Mat ring;
            cv::dilate(hole, ring, ringKernel);
            ring &= ~(hole > 0);
            const int32_t* index = indicesOf(t);
            const int areaStep = std::max(1, holeCount_[t] / kMatchAreaSamples);
            for (int k = 0; k < holeCount_[t]; k += areaStep) areaPts[i].emplace_back(index[k] % w_, index[k] / w_);
            std::vector<cv::Point> all;
            cv::findNonZero(ring, all);
            const size_t ringStep = std::max<size_t>(1, all.size() / kMatchRingSamples);
            const cv::Mat grey = smallGrey(t);
            for (size_t k = 0; k < all.size(); k += ringStep) {
                ringPts[i].push_back(all[k]);
                ringGrey[i].push_back(grey.at<uint8_t>(std::min(fh_ - 1, all[k].y / kFlowScale),
                                                       std::min(fw_ - 1, all[k].x / kFlowScale)));
                ringMean[i] += ringGrey[i].back();
            }
            if (!ringGrey[i].empty()) ringMean[i] /= ringGrey[i].size();
        }
        std::vector<Motion> toShotStart(m);
        for (size_t i = 0; i < m; ++i) toShotStart[i] = fromShotStart_[covered[i]].inverse();

        // How badly each frame s stands in for each covered frame (mean brightness difference of the ring, with
        // the overall brightness change taken out); none when s doesn't show all of the area.
        std::vector<float> cost(m * static_cast<size_t>(n_), kNone);
        for (int s = 0; s < n_; ++s) {
            if (cancelled && cancelled->load()) throw std::runtime_error("cancelled");
            const uint8_t* sHole = complete_[s] ? nullptr : holeOf(s);
            cv::Mat grey;
            for (size_t i = 0; i < m; ++i) {
                const int t = covered[i];
                if (t == s || shotOf_[t] != shotOf_[s] || ringGrey[i].empty()) continue;
                const Motion motion = Motion::compose(fromShotStart_[s], toShotStart[i]);
                bool shown = true;
                for (const cv::Point& p : areaPts[i]) {
                    double qx, qy;
                    motion.apply(p.x, p.y, qx, qy);
                    const int rx = static_cast<int>(std::lround(qx));
                    const int ry = static_cast<int>(std::lround(qy));
                    if (rx < 1 || ry < 1 || rx >= w_ - 1 || ry >= h_ - 1 ||
                        (sHole && !memeget::footprintClear(sHole, w_, h_, static_cast<float>(qx), static_cast<float>(qy)))) {
                        shown = false;
                        break;
                    }
                }
                if (!shown) continue;
                if (grey.empty()) grey = smallGrey(s);
                float sum = 0;
                float mean = 0;
                std::vector<float>& values = matchScratch_;
                values.resize(ringPts[i].size());
                for (size_t k = 0; k < ringPts[i].size(); ++k) {
                    double qx, qy;
                    motion.apply(ringPts[i][k].x, ringPts[i][k].y, qx, qy);
                    const int sx = std::clamp(static_cast<int>(qx / kFlowScale), 0, fw_ - 1);
                    const int sy = std::clamp(static_cast<int>(qy / kFlowScale), 0, fh_ - 1);
                    values[k] = grey.at<uint8_t>(sy, sx);
                    mean += values[k];
                }
                mean /= values.size();
                const float shift = ringMean[i] - mean;
                for (size_t k = 0; k < values.size(); ++k) sum += std::abs(ringGrey[i][k] - values[k] - shift);
                cost[i * n_ + s] = sum / values.size() + kMatchBrightnessWeight * std::abs(shift);
            }
        }

        // The path: playing on from s - 1 to s is free, jumping anywhere costs kMatchJumpCost.
        std::vector<bool> continues(m, false);
        for (size_t i = 1; i < m; ++i) {
            continues[i] = covered[i] == covered[i - 1] + 1 && shotOf_[covered[i]] == shotOf_[covered[i - 1]];
        }
        const std::vector<int> choice = memeget::matchPath(cost, m, n_, continues, kMatchJumpCost);

        // Sources from the chosen frames, nudged by up to kMatchShiftPx where the ring lines up better.
        for (size_t i = 0; i < m; ++i) {
            const int s = choice[i];
            if (s < 0) continue;
            const int t = covered[i];
            const Motion motion = Motion::compose(fromShotStart_[s], toShotStart[i]);
            const cv::Mat grey = smallGrey(s);
            const uint8_t* sHole = complete_[s] ? nullptr : holeOf(s);
            int bestDx = 0;
            int bestDy = 0;
            float bestCost = kNone;
            for (int dy = -kMatchShiftPx; dy <= kMatchShiftPx; dy += kFlowScale) {
                for (int dx = -kMatchShiftPx; dx <= kMatchShiftPx; dx += kFlowScale) {
                    float sum = 0;
                    float mean = 0;
                    std::vector<float>& values = matchScratch_;
                    values.resize(ringPts[i].size());
                    for (size_t k = 0; k < ringPts[i].size(); ++k) {
                        double qx, qy;
                        motion.apply(ringPts[i][k].x, ringPts[i][k].y, qx, qy);
                        const int sx = std::clamp(static_cast<int>((qx + dx) / kFlowScale), 0, fw_ - 1);
                        const int sy = std::clamp(static_cast<int>((qy + dy) / kFlowScale), 0, fh_ - 1);
                        values[k] = grey.at<uint8_t>(sy, sx);
                        mean += values[k];
                    }
                    mean /= values.size();
                    for (size_t k = 0; k < values.size(); ++k) sum += std::abs(ringGrey[i][k] - values[k] - (ringMean[i] - mean));
                    if (sum < bestCost) {
                        bestCost = sum;
                        bestDx = dx;
                        bestDy = dy;
                    }
                }
            }
            Source* sources = sourcesOf(t);
            const int32_t* index = indicesOf(t);
            for (int k = 0; k < holeCount_[t]; ++k) {
                double qx, qy;
                motion.apply(index[k] % w_, index[k] / w_, qx, qy);
                qx += bestDx;
                qy += bestDy;
                if (qx < 0 || qy < 0 || qx > w_ - 1 || qy > h_ - 1) continue;
                if (sHole && !memeget::footprintClear(sHole, w_, h_, static_cast<float>(qx), static_cast<float>(qy))) continue;
                const int x16 = static_cast<int>(std::lround(qx * 16));
                const int y16 = static_cast<int>(std::lround(qy * 16));
                sources[k] = Source{static_cast<int16_t>(s), static_cast<int16_t>(x16), static_cast<int16_t>(y16),
                                    static_cast<uint8_t>(std::min(std::abs(s - t), 254)),
                                    1};
            }
            matched_[t] = 1;
            matchFrom_[t] = s;
        }
    }

    /**
     * Real background for each covered pixel (whole matched frames, flow, camera motion). A frame whose real fill
     * doesn't continue the footage across the area's edge (see trusted) drops it: the fill model does better there.
     */
    void propagate(const std::atomic<bool>* cancelled) {
        matchFrames(cancelled);
        for (int t = 1; t < n_; ++t) {
            if (cancelled && cancelled->load()) throw std::runtime_error("cancelled");
            step(t, t - 1);
        }
        for (int t = n_ - 2; t >= 0; --t) {
            if (cancelled && cancelled->load()) throw std::runtime_error("cancelled");
            step(t, t + 1);
        }
        sampleAlongCameraMotion(cancelled);
        // Judged once every frame has its sources (frames read each other's sources while propagating).
        std::vector<uint8_t> keep(n_, 1);
        for (int t = 0; t < n_; ++t) keep[t] = complete_[t] || holeCount_[t] == 0 || trusted(t);
        for (int t = 0; t < n_; ++t) {
            if (!keep[t]) std::fill(sourcesOf(t), sourcesOf(t) + holeCount_[t], Source{});
        }
    }

    /** Frame [t] with every covered pixel that has a source filled in, and [holeOut] marking those still open. */
    void composite(int t, uint8_t* rgbOut, uint8_t* holeOut) const {
        std::memcpy(rgbOut, frameRgb(t), px_ * 3);
        std::memset(holeOut, 0, px_);
        if (complete_[t]) return;
        const Source* src = sourcesOf(t);
        const int32_t* index = indicesOf(t);
        for (int i = 0; i < holeCount_[t]; ++i) {
            const int p = index[i];
            if (src[i].found) {
                sample(src[i], rgbOut + static_cast<size_t>(p) * 3);
            } else {
                holeOut[p] = 1;
            }
        }
    }

    /**
     * What frame [t] needs from the fill model: [rgbOut] with its real fill blended in, [holeOut] marking what is
     * still open. False when nothing is open (or the frame is already complete).
     */
    bool fillRequest(int t, uint8_t* rgbOut, uint8_t* holeOut) const {
        composite(t, rgbOut, holeOut);
        if (complete_[t]) return false;
        matchSeams(t, rgbOut);
        return std::any_of(holeOut, holeOut + px_, [](uint8_t open) { return open != 0; });
    }

    /** [rgb] is frame [t] with nothing covered any more (the fill model's output, or the plain fill). */
    void setComplete(int t, const uint8_t* rgb) {
        std::memcpy(frameRgb(t), rgb, px_ * 3);
        // The covered mask stays: everything that reads it checks complete_ first, and the smoothing needs it.
        complete_[t] = 1;
    }

    /**
     * Completes every frame the fill model didn't (real fill, the rest filled from its surroundings), then smooths
     * the fill through time.
     */
    void finish(const std::atomic<bool>* cancelled) {
        std::vector<uint8_t> rgb(px_ * 3);
        std::vector<uint8_t> open(px_);
        for (int t = 0; t < n_; ++t) {
            if (cancelled && cancelled->load()) throw std::runtime_error("cancelled");
            if (complete_[t]) continue;
            composite(t, rgb.data(), open.data());
            matchSeams(t, rgb.data());
            cv::Mat image(h_, w_, CV_8UC3, rgb.data());
            cv::Mat hole(h_, w_, CV_8UC1, open.data());
            if (cv::countNonZero(hole) > 0) memeget::fillFromSurroundings(image, hole, 0, 0, std::max(w_, h_));
            setComplete(t, rgb.data());
        }
        smooth(cancelled);
    }

    /**
     * Takes the seam out of frame [t]'s fill ([rgb], composited): along the edge of the covered area, how far each
     * real pixel just outside differs from what its neighbour's source would have put there is spread smoothly
     * across the area and added, so a source frame with other lighting or a slightly different picture blends in
     * without a visible edge (a membrane blend, as in gradient-domain pasting).
     */
    void matchSeams(int t, uint8_t* rgb) const {
        if (holeCount_[t] == 0) return;
        const uint8_t* hole = holeOf(t);
        const Source* src = sourcesOf(t);
        const int32_t* index = indicesOf(t);
        int left = w_, top = h_, right = -1, bottom = -1;
        for (int i = 0; i < holeCount_[t]; ++i) {
            const int x = index[i] % w_;
            const int y = index[i] / w_;
            left = std::min(left, x); right = std::max(right, x);
            top = std::min(top, y); bottom = std::max(bottom, y);
        }
        left = std::max(0, left - 2); top = std::max(0, top - 2);
        right = std::min(w_ - 1, right + 2); bottom = std::min(h_ - 1, bottom + 2);
        const int bw = right - left + 1;
        const int bh = bottom - top + 1;
        cv::Mat sum = cv::Mat::zeros(bh, bw, CV_32FC3);
        cv::Mat count = cv::Mat::zeros(bh, bw, CV_32FC1);
        const uint8_t* frame = frameRgb(t);
        static const int kDx[4] = {1, -1, 0, 0};
        static const int kDy[4] = {0, 0, 1, -1};
        for (int i = 0; i < holeCount_[t]; ++i) {
            if (src[i].found != 1) continue;
            const int x = index[i] % w_;
            const int y = index[i] / w_;
            for (int d = 0; d < 4; ++d) {
                const int bx = x + kDx[d];
                const int by = y + kDy[d];
                if (bx < 0 || by < 0 || bx >= w_ || by >= h_ || hole[by * w_ + bx]) continue;
                Source shifted = src[i];
                shifted.x16 = static_cast<int16_t>(shifted.x16 + kDx[d] * 16);
                shifted.y16 = static_cast<int16_t>(shifted.y16 + kDy[d] * 16);
                uint8_t predicted[3];
                sample(shifted, predicted);
                const uint8_t* actual = frame + (static_cast<size_t>(by) * w_ + bx) * 3;
                cv::Vec3f& s = sum.at<cv::Vec3f>(by - top, bx - left);
                for (int c = 0; c < 3; ++c) s[c] += static_cast<float>(actual[c]) - predicted[c];
                count.at<float>(by - top, bx - left) += 1.0f;
            }
        }
        // Known offsets at the edge; everything else in the box is filled in smoothly from them. An edge pixel far
        // off the typical offset is not lighting but a different picture (a bit of the logo the mask didn't reach,
        // a strand that moved): it is left out, or it would spread into the fill as a bright or dark blot.
        std::vector<float> perChannel[3];
        for (int y = 0; y < bh; ++y) {
            for (int x = 0; x < bw; ++x) {
                const float n = count.at<float>(y, x);
                if (n <= 0) continue;
                for (int c = 0; c < 3; ++c) perChannel[c].push_back(sum.at<cv::Vec3f>(y, x)[c] / n);
            }
        }
        if (perChannel[0].empty()) return;
        float typical[3];
        for (int c = 0; c < 3; ++c) {
            auto& v = perChannel[c];
            std::nth_element(v.begin(), v.begin() + v.size() / 2, v.end());
            typical[c] = v[v.size() / 2];
        }
        cv::Mat unknown(bh, bw, CV_8UC1);
        std::vector<cv::Mat> channels(3);
        for (auto& ch : channels) ch.create(bh, bw, CV_32FC1);
        bool any = false;
        for (int y = 0; y < bh; ++y) {
            for (int x = 0; x < bw; ++x) {
                const float n = count.at<float>(y, x);
                bool keep = n > 0;
                float offset[3] = {0, 0, 0};
                for (int c = 0; c < 3 && keep; ++c) {
                    offset[c] = sum.at<cv::Vec3f>(y, x)[c] / n;
                    keep = std::abs(offset[c] - typical[c]) <= kSeamOutlier;
                }
                unknown.at<uint8_t>(y, x) = keep ? 0 : 255;
                for (int c = 0; c < 3; ++c) channels[c].at<float>(y, x) = keep ? offset[c] : 0.0f;
                any = any || keep;
            }
        }
        if (!any) return;
        for (auto& ch : channels) {
            cv::Mat filled;
            cv::inpaint(ch, unknown, filled, 3.0, cv::INPAINT_TELEA);
            ch = filled;
        }
        for (int i = 0; i < holeCount_[t]; ++i) {
            if (src[i].found != 1) continue;
            const int x = index[i] % w_ - left;
            const int y = index[i] / w_ - top;
            uint8_t* p = rgb + static_cast<size_t>(index[i]) * 3;
            for (int c = 0; c < 3; ++c) {
                p[c] = static_cast<uint8_t>(std::clamp(p[c] + channels[c].at<float>(y, x), 0.0f, 255.0f) + 0.5f);
            }
        }
    }

    /** Frame [t], finished (see finish). */
    void result(int t, uint8_t* rgbOut) const { std::memcpy(rgbOut, frameRgb(t), px_ * 3); }

    /**
     * Whether frame [t]'s real fill continues the footage across the edge of its covered area (see
     * kTrustSeamLevel): real sources for at least kTrustRealShare of the area, and a median step from a filled
     * pixel into the real pixel next to it no larger than the footage's own step between the next two, give or
     * take the level.
     */
    bool trusted(int t) const {
        const Source* src = sourcesOf(t);
        const int32_t* index = indicesOf(t);
        const uint8_t* hole = holeOf(t);
        const uint8_t* frame = frameRgb(t);
        static const int kDx[4] = {1, -1, 0, 0};
        static const int kDy[4] = {0, 0, 1, -1};
        int real = 0;
        std::vector<float> excess;
        for (int i = 0; i < holeCount_[t]; ++i) {
            if (src[i].found != 1) continue;
            ++real;
            const int x = index[i] % w_;
            const int y = index[i] / w_;
            uint8_t filled[3];
            bool sampled = false;
            for (int d = 0; d < 4; ++d) {
                const int x1 = x + kDx[d], y1 = y + kDy[d];
                const int x2 = x + 2 * kDx[d], y2 = y + 2 * kDy[d];
                if (x2 < 0 || y2 < 0 || x2 >= w_ || y2 >= h_ || hole[y1 * w_ + x1] || hole[y2 * w_ + x2]) continue;
                if (!sampled) {
                    sample(src[i], filled);
                    sampled = true;
                }
                const uint8_t* a = frame + (static_cast<size_t>(y1) * w_ + x1) * 3;
                const uint8_t* b = frame + (static_cast<size_t>(y2) * w_ + x2) * 3;
                int jump = 0;
                int own = 0;
                for (int c = 0; c < 3; ++c) {
                    jump += std::abs(filled[c] - a[c]);
                    own += std::abs(a[c] - b[c]);
                }
                excess.push_back((jump - own) / 3.0f);
            }
        }
        if (real < kTrustRealShare * holeCount_[t] || excess.size() < 20) return false;
        std::nth_element(excess.begin(), excess.begin() + excess.size() / 2, excess.end());
        return excess[excess.size() / 2] < kTrustSeamLevel;
    }

    /** How a neighbour's fill is carried into a frame when smoothing: its share, and whether it stays unmoved. */
    struct Carry {
        float share = 0;
        bool still = true;
    };

    /** How frame [u]'s fill is carried into its neighbour [t] (see kRepeatLevel). */
    Carry carryOf(int t, int u) const {
        if (cutAfter_[std::min(t, u)]) return {};
        if (repeatDifference(t, u) <= kRepeatLevel) return {1.0f, true};
        const float still = ringMiss(t, u, true);
        const float moved = ringMiss(t, u, false);
        const float miss = std::min(still, moved);
        const float share = miss <= kCloseMiss ? kCloseShare : miss <= kFarMiss ? kFarShare : 0.0f;
        return {share, still <= moved};
    }

    /**
     * Blends each frame's fill with its neighbours' fills carried into it (see carryOf), one pass forwards and one
     * backwards, averaged: the fill model invents every frame on its own, and this takes out the flicker without
     * trailing the footage.
     */
    void smooth(const std::atomic<bool>* cancelled) {
        if (offsets_[n_] == 0) return;
        std::vector<Carry> fromPrevious(n_);
        std::vector<Carry> fromNext(n_);
        for (int t = 0; t < n_; ++t) {
            if (cancelled && cancelled->load()) throw std::runtime_error("cancelled");
            if (holeCount_[t] == 0) continue;
            if (t > 0) fromPrevious[t] = carryOf(t, t - 1);
            if (t + 1 < n_) fromNext[t] = carryOf(t, t + 1);
        }
        // The forward pass's covered pixels, for the average.
        Scratch forward(dir_ + "/completion_smooth", offsets_[n_] * 3);
        std::vector<float> previous(px_ * 3);
        std::vector<float> current(px_ * 3);
        // Frame [t]'s fill (dense) into [out], blended with [carried] (frame u's pass result) as [carry] says.
        auto pass = [&](int t, int u, const Carry& carry, const std::vector<float>& carried, std::vector<float>& out) {
            const uint8_t* frame = frameRgb(t);
            for (size_t i = 0; i < px_ * 3; ++i) out[i] = frame[i];
            if (carry.share <= 0) return;
            const cv::Mat flow = carry.still ? cv::Mat() : flowAt(t, u > t);
            const int32_t* index = indicesOf(t);
            for (int i = 0; i < holeCount_[t]; ++i) {
                const int p = index[i];
                const int x = p % w_;
                const int y = p / w_;
                float qx = static_cast<float>(x);
                float qy = static_cast<float>(y);
                if (!carry.still) {
                    const cv::Vec2f f = flowSample(flow, qx, qy);
                    qx = std::clamp(qx + f[0], 0.0f, static_cast<float>(w_ - 1));
                    qy = std::clamp(qy + f[1], 0.0f, static_cast<float>(h_ - 1));
                }
                const int x0 = static_cast<int>(qx);
                const int y0 = static_cast<int>(qy);
                const int x1 = std::min(x0 + 1, w_ - 1);
                const int y1 = std::min(y0 + 1, h_ - 1);
                const float fx = qx - x0;
                const float fy = qy - y0;
                for (int c = 0; c < 3; ++c) {
                    const float top = carried[(static_cast<size_t>(y0) * w_ + x0) * 3 + c] * (1 - fx) +
                                      carried[(static_cast<size_t>(y0) * w_ + x1) * 3 + c] * fx;
                    const float bottom = carried[(static_cast<size_t>(y1) * w_ + x0) * 3 + c] * (1 - fx) +
                                         carried[(static_cast<size_t>(y1) * w_ + x1) * 3 + c] * fx;
                    float& v = out[static_cast<size_t>(p) * 3 + c];
                    v = v * (1 - carry.share) + (top * (1 - fy) + bottom * fy) * carry.share;
                }
            }
        };
        for (int t = 0; t < n_; ++t) {
            if (cancelled && cancelled->load()) throw std::runtime_error("cancelled");
            pass(t, t - 1, fromPrevious[t], previous, current);
            uint8_t* kept = forward.data() + offsets_[t] * 3;
            const int32_t* index = indicesOf(t);
            for (int i = 0; i < holeCount_[t]; ++i) {
                for (int c = 0; c < 3; ++c) {
                    kept[i * 3 + c] = static_cast<uint8_t>(std::clamp(current[static_cast<size_t>(index[i]) * 3 + c], 0.0f, 255.0f) + 0.5f);
                }
            }
            std::swap(previous, current);
        }
        // The backward pass reads each frame's own fill before it is replaced by the average.
        for (int t = n_ - 1; t >= 0; --t) {
            if (cancelled && cancelled->load()) throw std::runtime_error("cancelled");
            pass(t, t + 1, fromNext[t], previous, current);
            uint8_t* frame = frameRgb(t);
            const uint8_t* kept = forward.data() + offsets_[t] * 3;
            const int32_t* index = indicesOf(t);
            for (int i = 0; i < holeCount_[t]; ++i) {
                const size_t p = static_cast<size_t>(index[i]) * 3;
                for (int c = 0; c < 3; ++c) {
                    frame[p + c] = static_cast<uint8_t>(std::clamp((kept[i * 3 + c] + current[p + c]) * 0.5f, 0.0f, 255.0f) + 0.5f);
                }
            }
            std::swap(previous, current);
        }
    }

    /**
     * Mean colour difference (of 255, full resolution) between frames [t] and [u] outside t's covered area and the
     * band around it where flow follows the logo.
     */
    float repeatDifference(int t, int u) const {
        cv::Mat near;
        cv::dilate(cv::Mat(h_, w_, CV_8UC1, const_cast<uint8_t*>(holeOf(t))), near,
                   cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(2 * kFlowHoleGrowPx + 1, 2 * kFlowHoleGrowPx + 1)));
        return memeget::colourDifference(frameRgb(t), frameRgb(u), near.data, px_);
    }

    /**
     * How far (mean brightness, of 255, half resolution) frame [u], carried to its neighbour [t] along the flow (or
     * left where it is: [still]), misses the footage in a band just outside t's covered area.
     */
    float ringMiss(int t, int u, bool still) const {
        const cv::Mat near = nearHole(t);
        cv::Mat ring;
        cv::dilate(near, ring, cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(9, 9)));
        ring &= ~near;
        const cv::Mat flow = still ? cv::Mat() : flowAt(t, u > t);
        const cv::Mat here = smallGrey(t);
        const cv::Mat there = smallGrey(u);
        double sum = 0;
        int count = 0;
        for (int y = 0; y < fh_; ++y) {
            for (int x = 0; x < fw_; ++x) {
                if (!ring.at<uint8_t>(y, x)) continue;
                float qx = static_cast<float>(x);
                float qy = static_cast<float>(y);
                if (!still) {
                    const auto f = flow.at<cv::Vec2f>(y, x);
                    qx = std::clamp(qx + f[0], 0.0f, static_cast<float>(fw_ - 1));
                    qy = std::clamp(qy + f[1], 0.0f, static_cast<float>(fh_ - 1));
                }
                const int x0 = static_cast<int>(qx);
                const int y0 = static_cast<int>(qy);
                const int x1 = std::min(x0 + 1, fw_ - 1);
                const int y1 = std::min(y0 + 1, fh_ - 1);
                const float fx = qx - x0;
                const float fy = qy - y0;
                const float carried = (there.at<uint8_t>(y0, x0) * (1 - fx) + there.at<uint8_t>(y0, x1) * fx) * (1 - fy) +
                                      (there.at<uint8_t>(y1, x0) * (1 - fx) + there.at<uint8_t>(y1, x1) * fx) * fy;
                sum += std::abs(carried - here.at<uint8_t>(y, x));
                ++count;
            }
        }
        return count ? static_cast<float>(sum / count) : 0.0f;
    }

    int frames() const { return n_; }
    size_t pixels() const { return px_; }

private:
    uint8_t* frameRgb(int t) const { return rgb_.data() + static_cast<size_t>(t) * px_ * 3; }
    const uint8_t* holeOf(int t) const { return holes_.data() + static_cast<size_t>(t) * px_; }
    Source* sourcesOf(int t) const { return reinterpret_cast<Source*>(sources_->data()) + offsets_[t]; }
    const int32_t* indicesOf(int t) const { return reinterpret_cast<const int32_t*>(indices_->data()) + offsets_[t]; }

    /** The source pixel, read with Catmull-Rom (bilinear reads soften fine texture into a visible blurry patch). */
    void sample(const Source& s, uint8_t* out) const {
        const float x = std::clamp(s.x16 / 16.0f, 0.0f, static_cast<float>(w_ - 1));
        const float y = std::clamp(s.y16 / 16.0f, 0.0f, static_cast<float>(h_ - 1));
        const int x0 = static_cast<int>(x);
        const int y0 = static_cast<int>(y);
        float wx[4];
        float wy[4];
        memeget::catmullRom(x - x0, wx);
        memeget::catmullRom(y - y0, wy);
        const uint8_t* frame = frameRgb(s.frame);
        float sum[3] = {0, 0, 0};
        for (int j = 0; j < 4; ++j) {
            const int yy = std::clamp(y0 - 1 + j, 0, h_ - 1);
            for (int i = 0; i < 4; ++i) {
                const int xx = std::clamp(x0 - 1 + i, 0, w_ - 1);
                const float weight = wx[i] * wy[j];
                const uint8_t* p = frame + (static_cast<size_t>(yy) * w_ + xx) * 3;
                sum[0] += p[0] * weight;
                sum[1] += p[1] * weight;
                sum[2] += p[2] * weight;
            }
        }
        for (int c = 0; c < 3; ++c) out[c] = static_cast<uint8_t>(std::clamp(sum[c], 0.0f, 255.0f) + 0.5f);
    }

    /** Whether a read of frame [frame] at ([x16], [y16]) in 1/16 pixel touches none of its covered pixels. */
    bool clearRead(int frame, int x16, int y16) const {
        return memeget::footprintClear(complete_[frame] ? nullptr : holeOf(frame), w_, h_, x16 / 16.0f, y16 / 16.0f);
    }

    cv::Mat smallGrey(int t) const {
        cv::Mat rgb(h_, w_, CV_8UC3, frameRgb(t));
        cv::Mat grey;
        cv::cvtColor(rgb, grey, cv::COLOR_RGB2GRAY);
        cv::Mat small;
        cv::resize(grey, small, cv::Size(fw_, fh_), 0, 0, cv::INTER_AREA);
        return small;
    }

    /** The cached flow from frame [t] forward (to t + 1) or backward (to t - 1), half resolution. */
    cv::Mat flowAt(int t, bool forward) const {
        const size_t flowBytes = static_cast<size_t>(fw_) * fh_ * 2 * sizeof(float);
        const size_t slot = slotOf(t, forward);
        return cv::Mat(fh_, fw_, CV_32FC2, flows_->data() + slot * flowBytes);
    }

    /**
     * Raw flow from frame [t] ([from], half resolution) to [to], with the camera motion fitted to its textured part
     * (kept per slot). Around [t]'s covered area it also measures whether the footage moves with the camera or on
     * its own (see completeHoleFlow, which rebuilds the flow there once every frame is measured).
     */
    cv::Mat flowOf(int t, bool forward, const cv::Mat& from, const cv::Mat& to, Motion& motionOut, bool& goodOut, bool refine) {
        cv::Mat flow;
        flow_->calc(from, to, flow);
        const bool covered = !complete_[t] && holeCount_[t] > 0;
        cv::Mat near = cv::Mat::zeros(fh_, fw_, CV_8UC1);
        if (covered) {
            cv::Mat hole(h_, w_, CV_8UC1, const_cast<uint8_t*>(holeOf(t)));
            cv::Mat smallHole;
            cv::resize(hole, smallHole, cv::Size(fw_, fh_), 0, 0, cv::INTER_NEAREST);
            const int grow = kFlowHoleGrowPx / kFlowScale;
            cv::dilate(smallHole, near, cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(2 * grow + 1, 2 * grow + 1)));
            near = near > 0;
        }
        cv::Mat texture;
        cv::Laplacian(from, texture, CV_16S);
        std::vector<cv::Point2f> a;
        std::vector<cv::Point2f> b;
        const int step = std::max(2, static_cast<int>(std::sqrt(static_cast<double>(fw_) * fh_ / 3000.0)));
        for (int y = 0; y < fh_; y += step) {
            for (int x = 0; x < fw_; x += step) {
                if (near.at<uint8_t>(y, x) || std::abs(texture.at<int16_t>(y, x)) < 4) continue;
                const auto f = flow.at<cv::Vec2f>(y, x);
                a.emplace_back(static_cast<float>(x), static_cast<float>(y));
                b.emplace_back(x + f[0], y + f[1]);
            }
        }
        Similarity fitted;
        goodOut = fitSimilarity(a, b, fitted);
        Motion motion = Motion::of(fitted);
        if (goodOut && refine) {
            cv::Mat valid;
            cv::bitwise_not(near, valid);
            motion = refineAlignment(from, to, valid, motion);
        }
        // Kept in full-resolution pixels for direct sampling across many frames.
        motionOut = motion.scaled(kFlowScale);
        slotMotion_[slotOf(t, forward)] = motion;
        slotGood_[slotOf(t, forward)] = goodOut;
        if (!covered) return flow;
        // Whether the camera motion explains the footage just outside the area as well as its own flow does. A
        // photometric test: flow on fine texture is noisy even where everything moves with the camera.
        cv::Mat ring;
        cv::dilate(near, ring, cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(9, 9)));
        ring &= ~near;
        auto grey = [&](float x, float y) {
            x = std::clamp(x, 0.0f, static_cast<float>(fw_ - 1));
            y = std::clamp(y, 0.0f, static_cast<float>(fh_ - 1));
            const int x0 = static_cast<int>(x);
            const int y0 = static_cast<int>(y);
            const int x1 = std::min(x0 + 1, fw_ - 1);
            const int y1 = std::min(y0 + 1, fh_ - 1);
            const float fx = x - x0;
            const float fy = y - y0;
            return (to.at<uint8_t>(y0, x0) * (1 - fx) + to.at<uint8_t>(y0, x1) * fx) * (1 - fy) +
                   (to.at<uint8_t>(y1, x0) * (1 - fx) + to.at<uint8_t>(y1, x1) * fx) * fy;
        };
        double cameraError = 0;
        double flowError = 0;
        int ringCount = 0;
        int missed = 0;
        for (int y = 0; y < fh_; ++y) {
            for (int x = 0; x < fw_; ++x) {
                if (!ring.at<uint8_t>(y, x)) continue;
                double px = x, py = y;
                if (goodOut) motion.apply(x, y, px, py);
                const auto f = flow.at<cv::Vec2f>(y, x);
                const float here = from.at<uint8_t>(y, x);
                const float byCamera = std::abs(grey(static_cast<float>(px), static_cast<float>(py)) - here);
                const float byFlow = std::abs(grey(x + f[0], y + f[1]) - here);
                cameraError += byCamera;
                flowError += byFlow;
                if (byCamera > byFlow + kOwnMotionMissLevel) ++missed;
                ++ringCount;
            }
        }
        if (!goodOut || (ringCount > 0 && cameraError > flowError * kOwnMotionRatio + kOwnMotionLevel * ringCount)) {
            movesOnOwn_[t] = 1;
        }
        if (ringCount > 0) missedShare_[t] = std::max(missedShare_[t], static_cast<float>(missed) / ringCount);
        return flow;
    }

    /** Index of the cached flow from frame [t] forward or backward (see flowAt). */
    static size_t slotOf(int t, bool forward) {
        return forward ? static_cast<size_t>(t) * 2 : static_cast<size_t>(t - 1) * 2 + 1;
    }

    /**
     * Rebuilds the flow of frame [t] (forward or backward) around its covered area, where it follows the static
     * logo instead of the footage. Footage that moves with the camera gets the camera motion. Footage that moves
     * on its own (a face turning, a hand passing) gets the camera motion plus the way the footage right around the
     * area strays from it, carried in smoothly from the edge, so the fill moves with what surrounds it.
     */
    void completeHoleFlow(int t, bool forward) {
        if (complete_[t] || holeCount_[t] == 0) return;
        cv::Mat flow = flowAt(t, forward);
        const Motion& motion = slotMotion_[slotOf(t, forward)];
        const bool good = slotGood_[slotOf(t, forward)];
        const cv::Mat near = nearHole(t);
        std::vector<cv::Mat> deviation(2);
        deviation[0].create(fh_, fw_, CV_32FC1);
        deviation[1].create(fh_, fw_, CV_32FC1);
        for (int y = 0; y < fh_; ++y) {
            for (int x = 0; x < fw_; ++x) {
                double px = x, py = y;
                if (good) motion.apply(x, y, px, py);
                const auto f = flow.at<cv::Vec2f>(y, x);
                deviation[0].at<float>(y, x) = f[0] - static_cast<float>(px - x);
                deviation[1].at<float>(y, x) = f[1] - static_cast<float>(py - y);
            }
        }
        if (movesOnOwn_[t] || !good) {
            for (auto& part : deviation) {
                cv::Mat filled;
                cv::inpaint(part, near, filled, 3.0, cv::INPAINT_TELEA);
                part = filled;
            }
        } else {
            for (auto& part : deviation) part.setTo(0);
        }
        for (int y = 0; y < fh_; ++y) {
            for (int x = 0; x < fw_; ++x) {
                if (!near.at<uint8_t>(y, x)) continue;
                double px = x, py = y;
                if (good) motion.apply(x, y, px, py);
                flow.at<cv::Vec2f>(y, x) = cv::Vec2f(static_cast<float>(px - x) + deviation[0].at<float>(y, x),
                                                     static_cast<float>(py - y) + deviation[1].at<float>(y, x));
            }
        }
    }

    /**
     * Covered pixels that flow didn't reach in a few steps take the pixel the camera motion maps them to in the
     * nearest frame that shows it. Composing the per-pair motions avoids the drift of following flow through
     * dozens of frames, which blurs and shifts fine texture. That holds only where the footage around the area
     * moves with the camera. Where it moves on its own (a person, a curtain passing in front), camera motion points
     * at whatever was behind it, not at what is there now, so those frames are left to flow and the fill model.
     */
    void sampleAlongCameraMotion(const std::atomic<bool>* cancelled) {
        for (int t = 0; t < n_; ++t) {
            if (cancelled && cancelled->load()) throw std::runtime_error("cancelled");
            if (complete_[t] || holeCount_[t] == 0 || movesOnOwn_[t]) continue;
            Source* sources = sourcesOf(t);
            const int32_t* index = indicesOf(t);
            std::vector<int> open;
            std::vector<std::pair<int, Source>> setAside;
            memeget::planCameraPass(sources, holeCount_[t], matched_[t] != 0, kTrustedFlowSteps, open, setAside);
            bool backValid = true;
            bool aheadValid = true;
            const int reach = std::min(n_, kMaxCameraReach + 1);
            for (int k = 1; k < reach && !open.empty() && (backValid || aheadValid); ++k) {
                for (int dir = -1; dir <= 1; dir += 2) {
                    const int s = t + dir * k;
                    bool& valid = dir < 0 ? backValid : aheadValid;
                    if (!valid || s < 0 || s >= n_) {
                        valid = false;
                        continue;
                    }
                    Motion total;
                    if (!transformBetween(t, s, total)) {
                        valid = false;
                        continue;
                    }
                    for (size_t j = 0; j < open.size();) {
                        const int i = open[j];
                        const int p = index[i];
                        double qx, qy;
                        total.apply(p % w_, p / w_, qx, qy);
                        const int rx = static_cast<int>(std::lround(qx));
                        const int ry = static_cast<int>(std::lround(qy));
                        const int qx16 = static_cast<int>(std::lround(qx * 16));
                        const int qy16 = static_cast<int>(std::lround(qy * 16));
                        const bool shown = rx >= 0 && ry >= 0 && rx < w_ && ry < h_ && clearRead(s, qx16, qy16);
                        if (shown) {
                            const Source candidate{static_cast<int16_t>(s), static_cast<int16_t>(qx16),
                                                   static_cast<int16_t>(qy16),
                                                   static_cast<uint8_t>(std::min(k, 254)),
                                                   1};
                            if (better(candidate, sources[i])) sources[i] = candidate;
                        }
                        if (sources[i].found == 1 && sources[i].age <= k) {
                            open[j] = open.back();
                            open.pop_back();
                        } else {
                            ++j;
                        }
                    }
                }
            }
            for (const auto& [i, flowSource] : setAside) {
                if (better(flowSource, sources[i])) sources[i] = flowSource;
            }
        }
    }


    /** Full-resolution flow at ([x], [y]) from a half-resolution field. */
    cv::Vec2f flowSample(const cv::Mat& flow, float x, float y) const {
        const float sx = std::clamp(x / kFlowScale, 0.0f, static_cast<float>(fw_ - 1));
        const float sy = std::clamp(y / kFlowScale, 0.0f, static_cast<float>(fh_ - 1));
        const int x0 = static_cast<int>(sx);
        const int y0 = static_cast<int>(sy);
        const int x1 = std::min(x0 + 1, fw_ - 1);
        const int y1 = std::min(y0 + 1, fh_ - 1);
        const float fx = sx - x0;
        const float fy = sy - y0;
        const cv::Vec2f top = flow.at<cv::Vec2f>(y0, x0) * (1 - fx) + flow.at<cv::Vec2f>(y0, x1) * fx;
        const cv::Vec2f bottom = flow.at<cv::Vec2f>(y1, x0) * (1 - fx) + flow.at<cv::Vec2f>(y1, x1) * fx;
        return (top * (1 - fy) + bottom * fy) * static_cast<float>(kFlowScale);
    }

    /** Sources for frame [t]'s covered pixels from neighbour [u] (one frame earlier or later). */
    void step(int t, int u) {
        if (complete_[t] || holeCount_[t] == 0 || cutAfter_[std::min(t, u)] || matched_[t]) return;
        const bool forward = u > t;
        const cv::Mat toNeighbour = flowAt(t, forward);
        const cv::Mat back = flowAt(u, !forward);
        // Slot of each covered pixel in the neighbour, to read its source; -1 where the neighbour shows the pixel.
        slots_.assign(px_, -1);
        if (!complete_[u]) {
            const int32_t* uIndex = indicesOf(u);
            for (int i = 0; i < holeCount_[u]; ++i) slots_[uIndex[i]] = i;
        }
        const Source* uSources = complete_[u] ? nullptr : sourcesOf(u);
        Source* sources = sourcesOf(t);
        const int32_t* index = indicesOf(t);
        for (int i = 0; i < holeCount_[t]; ++i) {
            const int p = index[i];
            const int px = p % w_;
            const int py = p / w_;
            const cv::Vec2f f = flowSample(toNeighbour, static_cast<float>(px), static_cast<float>(py));
            const float qx = px + f[0];
            const float qy = py + f[1];
            if (qx < 0 || qy < 0 || qx > w_ - 1 || qy > h_ - 1) continue;
            const cv::Vec2f g = flowSample(back, qx, qy);
            if (std::abs(qx + g[0] - px) + std::abs(qy + g[1] - py) > kConsistencyPx) continue;
            const int rx = static_cast<int>(std::lround(qx));
            const int ry = static_cast<int>(std::lround(qy));
            Source candidate{};
            const int slot = slots_[ry * w_ + rx];
            if (slot < 0) {
                candidate = {static_cast<int16_t>(u), static_cast<int16_t>(std::lround(qx * 16)),
                             static_cast<int16_t>(std::lround(qy * 16)), 1, 1};
            } else {
                const Source& via = uSources[slot];
                if (!via.found || via.age == 255) continue;
                candidate = via;
                candidate.x16 = static_cast<int16_t>(via.x16 + std::lround((qx - rx) * 16));
                candidate.y16 = static_cast<int16_t>(via.y16 + std::lround((qy - ry) * 16));
                candidate.age = static_cast<uint8_t>(via.age + 1);
            }
            // The read must stay clear of the source frame's covered pixels, all taps of it, not just the centre.
            if (!clearRead(candidate.frame, candidate.x16, candidate.y16)) continue;
            candidate.found = 1;
            if (better(candidate, sources[i])) sources[i] = candidate;
        }
    }

    int w_;
    int h_;
    int n_;
    size_t px_;
    std::string dir_;
    Scratch rgb_;
    Scratch holes_;
    std::unique_ptr<Scratch> indices_;
    std::unique_ptr<Scratch> sources_;
    std::vector<uint8_t> complete_;
    std::vector<int> holeCount_;
    std::vector<size_t> offsets_;
    std::vector<int> slots_;
    std::unique_ptr<Scratch> flows_;
    // Camera motion between neighbouring frames (full-resolution pixels) and whether it fitted.
    std::vector<Motion> forwardMotion_;
    std::vector<Motion> backwardMotion_;
    // Direct alignment of frame a onto a + kAnchorStride (full resolution), for a a multiple of the stride.
    std::vector<Motion> anchorMotion_;
    std::vector<bool> anchorGood_;
    std::vector<bool> forwardGood_;
    std::vector<bool> backwardGood_;
    // Per frame, whether a hard cut follows it (frames t and t + 1 show different shots).
    std::vector<bool> cutAfter_;
    // Per frame, each one's camera motion from the first frame of its shot, and which shot it is in.
    std::vector<Motion> fromShotStart_;
    std::vector<int> shotOf_;
    // Per frame, whether its covered area was taken whole from another frame (see matchFrames), and from which.
    std::vector<uint8_t> matched_;
    std::vector<int> matchFrom_;
    std::vector<float> matchScratch_;
    // Per frame, whether the footage around the covered area moves on its own rather than with the camera.
    std::vector<uint8_t> movesOnOwn_;
    // Per frame, the largest share (either direction) of pixels around the covered area the camera motion misses.
    std::vector<float> missedShare_;
    // Per cached flow (see slotOf), the camera motion fitted to it at half resolution and whether it fitted.
    std::vector<Motion> slotMotion_;
    std::vector<bool> slotGood_;
    int fw_ = 0;
    int fh_ = 0;
    cv::Ptr<cv::DISOpticalFlow> flow_;
};

struct Job {
    std::unique_ptr<Completion> completion;
    std::string dir;
    std::atomic<bool> cancelled{false};
};

Job* job(jlong handle) {
    if (!handle) throw std::runtime_error("Video completion is closed");
    return reinterpret_cast<Job*>(handle);
}

std::vector<uint8_t> bytes(JNIEnv* env, jbyteArray array, size_t expected) {
    if (!array || static_cast<size_t>(env->GetArrayLength(array)) < expected) {
        throw std::runtime_error("A frame buffer has the wrong size");
    }
    std::vector<uint8_t> out(expected);
    env->GetByteArrayRegion(array, 0, static_cast<jsize>(expected), reinterpret_cast<jbyte*>(out.data()));
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_memeget_nativeapp_editor_VideoCompletionNative_nativeCreate(
    JNIEnv* env, jobject, jint width, jint height, jint frames, jstring scratchDir) {
    try {
        if (width <= 0 || height <= 0 || frames <= 0 || width > 2047 || height > 2047) {
            throw std::runtime_error("The logo region is too large to rebuild");
        }
        auto created = std::make_unique<Job>();
        created->dir = JStringUtf(env, scratchDir).str();
        created->completion = std::make_unique<Completion>(width, height, frames, created->dir);
        return reinterpret_cast<jlong>(created.release());
    } catch (const std::exception& e) {
        throwJava(env, e.what());
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_memeget_nativeapp_editor_VideoCompletionNative_nativeAddFrame(
    JNIEnv* env, jobject, jlong handle, jint index, jbyteArray rgb, jbyteArray hole) {
    try {
        auto* j = job(handle);
        const size_t px = j->completion->pixels();
        if (index < 0 || index >= j->completion->frames()) throw std::runtime_error("Frame index out of range");
        auto colour = bytes(env, rgb, px * 3);
        auto covered = bytes(env, hole, px);
        j->completion->addFrame(index, colour.data(), covered.data());
    } catch (const std::exception& e) {
        throwJava(env, e.what());
    }
}

JNIEXPORT void JNICALL
Java_com_memeget_nativeapp_editor_VideoCompletionNative_nativeSeal(JNIEnv* env, jobject, jlong handle) {
    try {
        auto* j = job(handle);
        j->completion->seal(j->dir, &j->cancelled);
        j->completion->computeFlows(j->dir, &j->cancelled);
    } catch (const std::exception& e) {
        throwJava(env, e.what());
    }
}

JNIEXPORT void JNICALL
Java_com_memeget_nativeapp_editor_VideoCompletionNative_nativeCoveredUnion(
    JNIEnv* env, jobject, jlong handle, jbyteArray holeOut) {
    try {
        auto* j = job(handle);
        std::vector<uint8_t> covered(j->completion->pixels());
        j->completion->coveredUnion(covered.data());
        env->SetByteArrayRegion(holeOut, 0, static_cast<jsize>(covered.size()), reinterpret_cast<const jbyte*>(covered.data()));
    } catch (const std::exception& e) {
        throwJava(env, e.what());
    }
}

JNIEXPORT void JNICALL
Java_com_memeget_nativeapp_editor_VideoCompletionNative_nativePropagate(JNIEnv* env, jobject, jlong handle) {
    try {
        auto* j = job(handle);
        j->completion->propagate(&j->cancelled);
    } catch (const std::exception& e) {
        throwJava(env, e.what());
    }
}

JNIEXPORT jboolean JNICALL
Java_com_memeget_nativeapp_editor_VideoCompletionNative_nativeFillRequest(
    JNIEnv* env, jobject, jlong handle, jint index, jbyteArray rgbOut, jbyteArray holeOut) {
    try {
        auto* j = job(handle);
        if (index < 0 || index >= j->completion->frames()) throw std::runtime_error("Frame index out of range");
        const size_t px = j->completion->pixels();
        std::vector<uint8_t> rgb(px * 3);
        std::vector<uint8_t> hole(px);
        const bool open = j->completion->fillRequest(index, rgb.data(), hole.data());
        env->SetByteArrayRegion(rgbOut, 0, static_cast<jsize>(rgb.size()), reinterpret_cast<const jbyte*>(rgb.data()));
        env->SetByteArrayRegion(holeOut, 0, static_cast<jsize>(hole.size()), reinterpret_cast<const jbyte*>(hole.data()));
        return open ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        throwJava(env, e.what());
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_memeget_nativeapp_editor_VideoCompletionNative_nativeSetComplete(
    JNIEnv* env, jobject, jlong handle, jint index, jbyteArray rgb) {
    try {
        auto* j = job(handle);
        auto colour = bytes(env, rgb, j->completion->pixels() * 3);
        j->completion->setComplete(index, colour.data());
    } catch (const std::exception& e) {
        throwJava(env, e.what());
    }
}

JNIEXPORT void JNICALL
Java_com_memeget_nativeapp_editor_VideoCompletionNative_nativeFinish(JNIEnv* env, jobject, jlong handle) {
    try {
        auto* j = job(handle);
        j->completion->finish(&j->cancelled);
    } catch (const std::exception& e) {
        throwJava(env, e.what());
    }
}

JNIEXPORT void JNICALL
Java_com_memeget_nativeapp_editor_VideoCompletionNative_nativeResult(
    JNIEnv* env, jobject, jlong handle, jint index, jbyteArray rgbOut) {
    try {
        auto* j = job(handle);
        std::vector<uint8_t> rgb(j->completion->pixels() * 3);
        j->completion->result(index, rgb.data());
        env->SetByteArrayRegion(rgbOut, 0, static_cast<jsize>(rgb.size()), reinterpret_cast<const jbyte*>(rgb.data()));
    } catch (const std::exception& e) {
        throwJava(env, e.what());
    }
}

JNIEXPORT void JNICALL
Java_com_memeget_nativeapp_editor_VideoCompletionNative_nativeCancel(JNIEnv*, jobject, jlong handle) {
    if (handle) reinterpret_cast<Job*>(handle)->cancelled.store(true);
}

JNIEXPORT void JNICALL
Java_com_memeget_nativeapp_editor_VideoCompletionNative_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Job*>(handle);
}

namespace {

/** Upright pixel back to the decoded (stored) orientation. */
inline void stored(int ux, int uy, int codedW, int codedH, int rotation, int& cx, int& cy) {
    switch (rotation) {
        case 90: cx = uy; cy = codedH - 1 - ux; break;
        case 180: cx = codedW - 1 - ux; cy = codedH - 1 - uy; break;
        case 270: cx = codedW - 1 - uy; cy = ux; break;
        default: cx = ux; cy = uy; break;
    }
}

const uint8_t* direct(JNIEnv* env, jobject buffer) {
    auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!data) throw std::runtime_error("The decoder's frame buffer isn't readable");
    return data;
}

} // namespace

// RGB of an upright region of a YUV 4:2:0 frame, sampled to outW x outH, with the frame's colour constants.
JNIEXPORT void JNICALL
Java_com_memeget_nativeapp_editor_VideoCompletionNative_nativeYuvToRgb(
    JNIEnv* env, jobject,
    jobject yBuffer, jobject uBuffer, jobject vBuffer,
    jint yRowStride, jint yPixelStride, jint uvRowStride, jint uvPixelStride,
    jint cropLeft, jint cropTop, jint codedW, jint codedH, jint rotation,
    jfloat regionLeft, jfloat regionTop, jfloat regionW, jfloat regionH,
    jint outW, jint outH, jboolean bt709, jboolean fullRange, jbyteArray out) {
    try {
        const uint8_t* y = direct(env, yBuffer);
        const uint8_t* u = direct(env, uBuffer);
        const uint8_t* v = direct(env, vBuffer);
        const bool quarter = rotation == 90 || rotation == 270;
        const int uprightW = quarter ? codedH : codedW;
        const int uprightH = quarter ? codedW : codedH;
        const float kr = bt709 ? 0.2126f : 0.299f;
        const float kb = bt709 ? 0.0722f : 0.114f;
        const float kg = 1.0f - kr - kb;
        const float rV = 2.0f * (1.0f - kr);
        const float bU = 2.0f * (1.0f - kb);
        const float gU = -bU * kb / kg;
        const float gV = -rV * kr / kg;
        const float yScale = fullRange ? 1.0f : 255.0f / 219.0f;
        const float cScale = fullRange ? 1.0f : 255.0f / 224.0f;
        const float yOffset = fullRange ? 0.0f : 16.0f;
        std::vector<uint8_t> rgb(static_cast<size_t>(outW) * outH * 3);
        for (int oy = 0; oy < outH; ++oy) {
            const int uy = std::clamp(static_cast<int>(regionTop + (oy + 0.5f) * regionH / outH), 0, uprightH - 1);
            for (int ox = 0; ox < outW; ++ox) {
                const int ux = std::clamp(static_cast<int>(regionLeft + (ox + 0.5f) * regionW / outW), 0, uprightW - 1);
                int cx, cy;
                stored(ux, uy, codedW, codedH, rotation, cx, cy);
                const int px = cropLeft + cx;
                const int py = cropTop + cy;
                const float luma = (y[py * yRowStride + px * yPixelStride] - yOffset) * yScale;
                const int ci = (py / 2) * uvRowStride + (px / 2) * uvPixelStride;
                const float cu = (u[ci] - 128.0f) * cScale;
                const float cv = (v[ci] - 128.0f) * cScale;
                uint8_t* o = rgb.data() + (static_cast<size_t>(oy) * outW + ox) * 3;
                o[0] = static_cast<uint8_t>(std::clamp(luma + rV * cv, 0.0f, 255.0f) + 0.5f);
                o[1] = static_cast<uint8_t>(std::clamp(luma + gU * cu + gV * cv, 0.0f, 255.0f) + 0.5f);
                o[2] = static_cast<uint8_t>(std::clamp(luma + bU * cu, 0.0f, 255.0f) + 0.5f);
            }
        }
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(rgb.size()), reinterpret_cast<const jbyte*>(rgb.data()));
    } catch (const std::exception& e) {
        throwJava(env, e.what());
    }
}

// The upright Y plane sampled to outW x outH.
JNIEXPORT void JNICALL
Java_com_memeget_nativeapp_editor_VideoCompletionNative_nativeLuma(
    JNIEnv* env, jobject, jobject yBuffer, jint rowStride, jint pixelStride,
    jint cropLeft, jint cropTop, jint codedW, jint codedH, jint rotation, jint outW, jint outH, jbyteArray out) {
    try {
        const uint8_t* y = direct(env, yBuffer);
        const bool quarter = rotation == 90 || rotation == 270;
        const int uprightW = quarter ? codedH : codedW;
        const int uprightH = quarter ? codedW : codedH;
        std::vector<uint8_t> grey(static_cast<size_t>(outW) * outH);
        for (int oy = 0; oy < outH; ++oy) {
            const int uy = std::clamp(static_cast<int>((oy + 0.5f) * uprightH / outH), 0, uprightH - 1);
            for (int ox = 0; ox < outW; ++ox) {
                const int ux = std::clamp(static_cast<int>((ox + 0.5f) * uprightW / outW), 0, uprightW - 1);
                int cx, cy;
                stored(ux, uy, codedW, codedH, rotation, cx, cy);
                grey[static_cast<size_t>(oy) * outW + ox] = y[(cropTop + cy) * rowStride + (cropLeft + cx) * pixelStride];
            }
        }
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(grey.size()), reinterpret_cast<const jbyte*>(grey.data()));
    } catch (const std::exception& e) {
        throwJava(env, e.what());
    }
}

} // extern "C"
