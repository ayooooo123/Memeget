// Logo removal kernels for the editor: find a selected logo in a video frame, and fill a rectangle of a still
// image from its surroundings. Both run on OpenCV, which this library already links.
#include <jni.h>
#include <android/bitmap.h>
#include <opencv2/imgproc.hpp>
#include <opencv2/photo.hpp>

#include "LogoFill.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <exception>
#include <vector>

namespace {

void throwJava(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/lang/RuntimeException");
    if (type) env->ThrowNew(type, message);
}

std::vector<uint8_t> copyBytes(JNIEnv* env, jbyteArray array, size_t expected) {
    if (!array || static_cast<size_t>(env->GetArrayLength(array)) < expected) return {};
    std::vector<uint8_t> bytes(expected);
    env->GetByteArrayRegion(array, 0, static_cast<jsize>(expected), reinterpret_cast<jbyte*>(bytes.data()));
    return bytes;
}

class LockedPixels {
public:
    LockedPixels(JNIEnv* env, jobject bitmap) : env_(env), bitmap_(bitmap) {
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels_) != ANDROID_BITMAP_RESULT_SUCCESS) pixels_ = nullptr;
    }
    ~LockedPixels() {
        if (pixels_) AndroidBitmap_unlockPixels(env_, bitmap_);
    }
    LockedPixels(const LockedPixels&) = delete;
    LockedPixels& operator=(const LockedPixels&) = delete;
    void* get() const { return pixels_; }

private:
    JNIEnv* env_;
    jobject bitmap_;
    void* pixels_ = nullptr;
};

constexpr double kInpaintRadiusPx = 5.0;
// A painted pixel is logo when it differs from the filled-in background by more than this (of 255).
constexpr int kStandOutLevel = 16;
constexpr double kMinStandOutShare = 0.03;
// A shadow or glow pixel differs from the wider filled-in background by more than this, within kGlowPx.
constexpr int kFaintLevel = 6;
constexpr int kGlowPx = 6;

// Colours are compared in bins of kColourBins per channel. A logo colour covers at least kMinColourShare of the
// paint and is kConcentration times more common under the paint than in the whole picture; bins next to one count
// when kNearConcentration times more common (anti-aliased edges).
constexpr int kColourBins = 16;
constexpr double kMinColourShare = 0.01;
constexpr double kConcentration = 6.0;
constexpr double kNearConcentration = 2.0;
// Logo-coloured pixels must cover this share of the paint to be trusted.
constexpr double kMinSeedShare = 0.02;
// Smallest gap (pixels) bridged between the shapes of one logo; wider for larger letters (each side reaches this
// share of a letter's height, so lines of a watermark a letter and a half apart join).
constexpr int kLinkPx = 6;
constexpr double kLinkPerLetterHeight = 0.8;

int colourBin(const uint8_t* p) {
    return ((p[0] * kColourBins / 256) * kColourBins + p[1] * kColourBins / 256) * kColourBins + p[2] * kColourBins / 256;
}

// The logo found by colour: the colours far more common under the paint than in the whole picture ([whole], RGB,
// any size), grouped into shapes. Groups that overlap the paint and stay off the border of [rgb] are kept, so the
// parts of the logo the paint missed are found too. Empty when the paint has no such colours.
cv::Mat logoByColour(const cv::Mat& rgb, const cv::Mat& painted, const cv::Mat& whole) {
    const int bins = kColourBins * kColourBins * kColourBins;
    std::vector<double> underPaint(bins, 0.0);
    std::vector<double> inPicture(bins, 0.0);
    int paintCount = 0;
    for (int y = 0; y < rgb.rows; ++y) {
        const uint8_t* p = rgb.ptr<uint8_t>(y);
        const uint8_t* m = painted.ptr<uint8_t>(y);
        for (int x = 0; x < rgb.cols; ++x) {
            if (!m[x]) continue;
            underPaint[colourBin(p + 3 * x)] += 1.0;
            ++paintCount;
        }
    }
    for (int y = 0; y < whole.rows; ++y) {
        const uint8_t* p = whole.ptr<uint8_t>(y);
        for (int x = 0; x < whole.cols; ++x) inPicture[colourBin(p + 3 * x)] += 1.0;
    }
    if (paintCount == 0 || whole.empty()) return {};
    const double pictureCount = static_cast<double>(whole.total());
    std::vector<double> ratio(bins);
    std::vector<uint8_t> logo(bins, 0);
    bool any = false;
    for (int b = 0; b < bins; ++b) {
        underPaint[b] /= paintCount;
        ratio[b] = underPaint[b] / (inPicture[b] / pictureCount + 1e-4);
    }
    for (int b = 0; b < bins; ++b) {
        if (underPaint[b] < kMinColourShare || ratio[b] < kConcentration) continue;
        any = true;
        const int r = b / (kColourBins * kColourBins);
        const int g = (b / kColourBins) % kColourBins;
        const int bl = b % kColourBins;
        for (int dr = -1; dr <= 1; ++dr) for (int dg = -1; dg <= 1; ++dg) for (int db = -1; db <= 1; ++db) {
            const int nr = r + dr, ng = g + dg, nb = bl + db;
            if (nr < 0 || ng < 0 || nb < 0 || nr >= kColourBins || ng >= kColourBins || nb >= kColourBins) continue;
            const int n = (nr * kColourBins + ng) * kColourBins + nb;
            if (n == b || ratio[n] >= kNearConcentration) logo[n] = 1;
        }
    }
    if (!any) return {};

    cv::Mat distinct(rgb.rows, rgb.cols, CV_8UC1);
    for (int y = 0; y < rgb.rows; ++y) {
        const uint8_t* p = rgb.ptr<uint8_t>(y);
        uint8_t* d = distinct.ptr<uint8_t>(y);
        for (int x = 0; x < rgb.cols; ++x) d[x] = logo[colourBin(p + 3 * x)] ? 255 : 0;
    }
    cv::Mat seed = distinct & painted;
    if (cv::countNonZero(seed) < paintCount * kMinSeedShare) return {};

    // Letters of a word and lines of a watermark sit about a letter apart: bridge gaps in proportion to their size.
    cv::Mat labels;
    cv::Mat stats;
    cv::Mat centroids;
    const int seedShapes = cv::connectedComponentsWithStats(seed, labels, stats, centroids, 8, CV_32S);
    std::vector<int> heights;
    for (int i = 1; i < seedShapes; ++i) {
        if (stats.at<int>(i, cv::CC_STAT_AREA) >= 4) heights.push_back(stats.at<int>(i, cv::CC_STAT_HEIGHT));
    }
    int link = kLinkPx;
    if (!heights.empty()) {
        std::nth_element(heights.begin(), heights.begin() + heights.size() / 2, heights.end());
        link = std::max(kLinkPx, static_cast<int>(std::lround(kLinkPerLetterHeight * heights[heights.size() / 2])));
    }
    cv::Mat groups;
    cv::dilate(distinct, groups, cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(2 * link + 1, 2 * link + 1)));
    const int count = cv::connectedComponents(groups, labels, 8, CV_32S);
    std::vector<uint8_t> keep(count, 0);
    std::vector<uint8_t> atBorder(count, 0);
    for (int y = 0; y < rgb.rows; ++y) {
        const int32_t* l = labels.ptr<int32_t>(y);
        const uint8_t* s = seed.ptr<uint8_t>(y);
        const uint8_t* d = distinct.ptr<uint8_t>(y);
        const bool edgeRow = y == 0 || y == rgb.rows - 1;
        for (int x = 0; x < rgb.cols; ++x) {
            if (s[x]) keep[l[x]] = 1;
            if (d[x] && (edgeRow || x == 0 || x == rgb.cols - 1)) atBorder[l[x]] = 1;
        }
    }
    // A group that reaches the border carries on beyond it, so it is the picture; its pixels under the paint still
    // count, since the person painted them.
    cv::Mat found = cv::Mat::zeros(rgb.rows, rgb.cols, CV_8UC1);
    for (int y = 0; y < rgb.rows; ++y) {
        const int32_t* l = labels.ptr<int32_t>(y);
        const uint8_t* d = distinct.ptr<uint8_t>(y);
        const uint8_t* s = seed.ptr<uint8_t>(y);
        uint8_t* f = found.ptr<uint8_t>(y);
        for (int x = 0; x < rgb.cols; ++x) {
            if (d[x] && l[x] > 0 && keep[l[x]] && (!atBorder[l[x]] || s[x])) f[x] = 255;
        }
    }
    cv::morphologyEx(found, found, cv::MORPH_CLOSE, cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(5, 5)));
    if (cv::countNonZero(found) == 0) return {};
    return found;
}

} // namespace

extern "C" {

// [image] and [templ] are 8-bit grey rows; [mask] (optional, templ-sized, nonzero = compare) limits the match to
// the logo's own pixels. Writes the template's best top-left corner and its normalized correlation (-1..1) to
// [out]; false when the inputs are unusable.
JNIEXPORT jboolean JNICALL
Java_com_memeget_nativeapp_editor_LogoRemovalNative_nativeMatchTemplate(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray image,
    jint width,
    jint height,
    jbyteArray templ,
    jint templWidth,
    jint templHeight,
    jbyteArray mask,
    jfloatArray out) {
    try {
        if (width <= 0 || height <= 0 || templWidth <= 0 || templHeight <= 0 ||
            templWidth > width || templHeight > height || !out || env->GetArrayLength(out) < 3) {
            return JNI_FALSE;
        }
        auto frame = copyBytes(env, image, static_cast<size_t>(width) * height);
        auto patch = copyBytes(env, templ, static_cast<size_t>(templWidth) * templHeight);
        if (frame.empty() || patch.empty()) return JNI_FALSE;
        std::vector<uint8_t> weights;
        if (mask) {
            weights = copyBytes(env, mask, patch.size());
            if (weights.empty()) return JNI_FALSE;
        }
        cv::Mat frameMat(height, width, CV_8UC1, frame.data());
        cv::Mat patchMat(templHeight, templWidth, CV_8UC1, patch.data());
        cv::Mat scores;
        if (weights.empty()) {
            cv::matchTemplate(frameMat, patchMat, scores, cv::TM_CCOEFF_NORMED);
        } else {
            cv::Mat maskMat(templHeight, templWidth, CV_8UC1, weights.data());
            cv::matchTemplate(frameMat, patchMat, scores, cv::TM_CCOEFF_NORMED, maskMat);
        }
        // A flat window has no correlation; OpenCV reports NaN or infinity there.
        for (int row = 0; row < scores.rows; ++row) {
            auto* value = scores.ptr<float>(row);
            for (int col = 0; col < scores.cols; ++col) {
                if (!std::isfinite(value[col])) value[col] = -1.0f;
            }
        }
        double best = 0.0;
        cv::Point at;
        cv::minMaxLoc(scores, nullptr, &best, nullptr, &at);
        const jfloat result[3] = {static_cast<jfloat>(at.x), static_cast<jfloat>(at.y), static_cast<jfloat>(best)};
        env->SetFloatArrayRegion(out, 0, 3, result);
        return JNI_TRUE;
    } catch (const std::exception& e) {
        throwJava(env, e.what());
        return JNI_FALSE;
    }
}

// Fills the pixels of [left, right) x [top, bottom) of an RGBA_8888 bitmap in place from the pixels around them
// (see fillFromSurroundings; the video effect does the same on the GPU). [mask] ([maskWidth] x [maskHeight],
// stretched over the rectangle, nonzero = replace) limits the fill to the logo's own pixels; without it the
// whole rectangle is filled. The rectangle may run past the bitmap. Alpha is kept. False when the bitmap or the
// rectangle is unusable.
JNIEXPORT jboolean JNICALL
Java_com_memeget_nativeapp_editor_LogoRemovalNative_nativeInpaint(
    JNIEnv* env,
    jobject /* thiz */,
    jobject bitmap,
    jint left,
    jint top,
    jint right,
    jint bottom,
    jbyteArray mask,
    jint maskWidth,
    jint maskHeight) {
    try {
        AndroidBitmapInfo info{};
        if (!bitmap || AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || right <= left || bottom <= top) {
            return JNI_FALSE;
        }
        const int frameW = static_cast<int>(info.width);
        const int frameH = static_cast<int>(info.height);
        const int x0 = std::clamp(static_cast<int>(left), 0, frameW);
        const int y0 = std::clamp(static_cast<int>(top), 0, frameH);
        const int x1 = std::clamp(static_cast<int>(right), x0, frameW);
        const int y1 = std::clamp(static_cast<int>(bottom), y0, frameH);
        if (x1 <= x0 || y1 <= y0) return JNI_FALSE;

        // The hole: the logo's pixels when a mask is given, else the whole rectangle.
        cv::Mat holeMask(y1 - y0, x1 - x0, CV_8UC1, cv::Scalar(255));
        if (mask) {
            if (maskWidth <= 0 || maskHeight <= 0) return JNI_FALSE;
            auto bytes = copyBytes(env, mask, static_cast<size_t>(maskWidth) * maskHeight);
            if (bytes.empty()) return JNI_FALSE;
            cv::Mat source(maskHeight, maskWidth, CV_8UC1, bytes.data());
            cv::Mat stretched;
            cv::resize(source, stretched, cv::Size(right - left, bottom - top), 0, 0, cv::INTER_LINEAR);
            holeMask = stretched(cv::Rect(x0 - left, y0 - top, x1 - x0, y1 - y0)) > 64;
        }
        if (cv::countNonZero(holeMask) == 0) return JNI_TRUE;
        // Nothing around the hole to fill it from.
        if (!mask && x1 - x0 == frameW && y1 - y0 == frameH) return JNI_FALSE;

        LockedPixels pixels(env, bitmap);
        if (!pixels.get()) return JNI_FALSE;
        cv::Mat whole(frameH, frameW, CV_8UC4, pixels.get(), info.stride);
        memeget::fillFromSurroundings(whole, holeMask, x0, y0, std::max(right - left, bottom - top));
        return JNI_TRUE;
    } catch (const std::exception& e) {
        throwJava(env, e.what());
        return JNI_FALSE;
    }
}

// Narrows a painted [mask] (one byte per pixel of the RGBA_8888 [bitmap], nonzero = painted) to the logo's own
// pixels, and reaches past the paint for the parts of the logo it missed. When the paint holds colours far more
// common there than in the whole picture ([whole], RGBA_8888, scaled down), the logo is the shapes of those colours.
// Otherwise it is the painted pixels that stand out from what the painted area would look like filled in from around
// it. Either way grown into faint shadow or glow, then widened by two pixels for soft edges.
// Returns null when too little stands out to trust (a faint logo), so the caller keeps the paint as it is.
JNIEXPORT jbyteArray JNICALL
Java_com_memeget_nativeapp_editor_LogoRemovalNative_nativeRefineMask(
    JNIEnv* env,
    jobject /* thiz */,
    jobject bitmap,
    jbyteArray mask,
    jobject wholeBitmap) {
    try {
        AndroidBitmapInfo info{};
        if (!bitmap || AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            return nullptr;
        }
        const int w = static_cast<int>(info.width);
        const int h = static_cast<int>(info.height);
        auto bytes = copyBytes(env, mask, static_cast<size_t>(w) * h);
        if (bytes.empty()) return nullptr;
        cv::Mat painted = cv::Mat(h, w, CV_8UC1, bytes.data()) > 127;
        const int paintedCount = cv::countNonZero(painted);
        if (paintedCount == 0) return nullptr;

        cv::Mat rgb;
        {
            LockedPixels pixels(env, bitmap);
            if (!pixels.get()) return nullptr;
            cv::Mat rgba(h, w, CV_8UC4, pixels.get(), info.stride);
            cv::cvtColor(rgba, rgb, cv::COLOR_RGBA2RGB);
        }
        cv::Mat wholeRgb;
        {
            AndroidBitmapInfo wholeInfo{};
            if (wholeBitmap && AndroidBitmap_getInfo(env, wholeBitmap, &wholeInfo) == ANDROID_BITMAP_RESULT_SUCCESS &&
                wholeInfo.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
                LockedPixels pixels(env, wholeBitmap);
                if (pixels.get()) {
                    cv::Mat rgba(static_cast<int>(wholeInfo.height), static_cast<int>(wholeInfo.width), CV_8UC4, pixels.get(), wholeInfo.stride);
                    cv::cvtColor(rgba, wholeRgb, cv::COLOR_RGBA2RGB);
                }
            }
        }
        const cv::Mat byColour = wholeRgb.empty() ? cv::Mat() : logoByColour(rgb, painted, wholeRgb);

        cv::Mat seeds;
        cv::Mat area;
        if (!byColour.empty()) {
            seeds = byColour;
            area = byColour;
        } else {
            cv::Mat background;
            cv::inpaint(rgb, painted, background, kInpaintRadiusPx, cv::INPAINT_TELEA);
            cv::Mat difference;
            cv::absdiff(rgb, background, difference);
            std::vector<cv::Mat> channels;
            cv::split(difference, channels);
            cv::Mat strongest = cv::max(channels[0], cv::max(channels[1], channels[2]));
            seeds = (strongest > kStandOutLevel) & painted;
            if (cv::countNonZero(seeds) < paintedCount * kMinStandOutShare) return nullptr;
            area = painted;
        }

        // Shadows and glows fade below the stand-out level and would be left as a ghost of the logo: grow into
        // pixels that differ faintly from a wider filled-in background, up to kGlowPx past the logo (and the paint).
        const cv::Mat step = cv::getStructuringElement(cv::MORPH_RECT, cv::Size(3, 3));
        cv::Mat reach;
        cv::dilate(area, reach, cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(2 * kGlowPx + 1, 2 * kGlowPx + 1)));
        cv::Mat wideBackground;
        cv::inpaint(rgb, reach, wideBackground, kInpaintRadiusPx, cv::INPAINT_TELEA);
        cv::Mat wideDifference;
        cv::absdiff(rgb, wideBackground, wideDifference);
        std::vector<cv::Mat> channels;
        cv::split(wideDifference, channels);
        cv::Mat faint = (cv::max(channels[0], cv::max(channels[1], channels[2])) > kFaintLevel) & reach;
        cv::Mat grown = seeds.clone();
        for (int i = 0; i < kGlowPx; ++i) {
            cv::Mat next;
            cv::dilate(grown, next, step);
            grown = (next & faint) | grown;
        }
        cv::Mat refined;
        cv::dilate(grown, refined, cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(5, 5)));
        refined &= reach;

        jbyteArray out = env->NewByteArray(w * h);
        if (out) env->SetByteArrayRegion(out, 0, w * h, reinterpret_cast<const jbyte*>(refined.data));
        return out;
    } catch (const std::exception& e) {
        throwJava(env, e.what());
        return nullptr;
    }
}

// MI-GAN's input for [rgb] ([w]×[h], 3 bytes a pixel) with [hole] (nonzero: fill) in [tensor] (4 × size × size):
// the picture padded to a square by repeating its edge pixels (the padding is kept, not filled), scaled to the
// model's size (area-averaged down, bilinear up), as concat(mask − 0.5, rgb · mask) channels first with rgb in
// [−1, 1] and mask 1 to keep. A scaled pixel any part of the hole reaches is filled.
JNIEXPORT void JNICALL
Java_com_memeget_nativeapp_editor_LogoRemovalNative_nativeMiganInput(
    JNIEnv* env, jobject /* thiz */, jbyteArray rgbArray, jint w, jint h, jbyteArray holeArray, jint size,
    jfloatArray tensorArray) {
    try {
        const int side = std::max(w, h);
        const int ox = (side - w) / 2;
        const int oy = (side - h) / 2;
        std::vector<uint8_t> rgb(static_cast<size_t>(w) * h * 3);
        std::vector<uint8_t> hole(static_cast<size_t>(w) * h);
        env->GetByteArrayRegion(rgbArray, 0, static_cast<jsize>(rgb.size()), reinterpret_cast<jbyte*>(rgb.data()));
        env->GetByteArrayRegion(holeArray, 0, static_cast<jsize>(hole.size()), reinterpret_cast<jbyte*>(hole.data()));
        cv::Mat square;
        cv::copyMakeBorder(cv::Mat(h, w, CV_8UC3, rgb.data()), square, oy, side - h - oy, ox, side - w - ox,
                           cv::BORDER_REPLICATE);
        cv::Mat squareHole;
        cv::copyMakeBorder(cv::Mat(h, w, CV_8UC1, hole.data()) != 0, squareHole, oy, side - h - oy, ox, side - w - ox,
                           cv::BORDER_CONSTANT, cv::Scalar(0));
        const int interpolation = side > size ? cv::INTER_AREA : cv::INTER_LINEAR;
        cv::Mat scaled;
        cv::Mat scaledHole;
        cv::resize(square, scaled, cv::Size(size, size), 0, 0, interpolation);
        cv::resize(squareHole, scaledHole, cv::Size(size, size), 0, 0, interpolation);
        const size_t plane = static_cast<size_t>(size) * size;
        std::vector<float> tensor(4 * plane);
        for (size_t i = 0; i < plane; ++i) {
            const float keep = scaledHole.data[i] > 0 ? 0.0f : 1.0f;
            tensor[i] = keep - 0.5f;
            for (int c = 0; c < 3; ++c) tensor[(c + 1) * plane + i] = (scaled.data[i * 3 + c] / 127.5f - 1.0f) * keep;
        }
        env->SetFloatArrayRegion(tensorArray, 0, static_cast<jsize>(tensor.size()), tensor.data());
    } catch (const std::exception& e) {
        throwJava(env, e.what());
    }
}

// [rgb] ([w]×[h]) with its [hole] pixels taken from MI-GAN's output [generated] (3 × size × size, channels first, in
// [−1, 1]), scaled back over the padded square of nativeMiganInput, into [out].
JNIEXPORT void JNICALL
Java_com_memeget_nativeapp_editor_LogoRemovalNative_nativeMiganOutput(
    JNIEnv* env, jobject /* thiz */, jfloatArray generatedArray, jint size, jbyteArray rgbArray, jint w, jint h,
    jbyteArray holeArray, jbyteArray outArray) {
    try {
        const int side = std::max(w, h);
        const int ox = (side - w) / 2;
        const int oy = (side - h) / 2;
        const size_t plane = static_cast<size_t>(size) * size;
        std::vector<float> generated(3 * plane);
        env->GetFloatArrayRegion(generatedArray, 0, static_cast<jsize>(generated.size()), generated.data());
        cv::Mat picture(size, size, CV_8UC3);
        for (size_t i = 0; i < plane; ++i) {
            for (int c = 0; c < 3; ++c) {
                picture.data[i * 3 + c] = static_cast<uint8_t>(std::clamp(static_cast<int>((generated[c * plane + i] + 1.0f) * 127.5f), 0, 255));
            }
        }
        cv::Mat back;
        cv::resize(picture, back, cv::Size(side, side), 0, 0, side > size ? cv::INTER_LINEAR : cv::INTER_AREA);
        std::vector<uint8_t> rgb(static_cast<size_t>(w) * h * 3);
        std::vector<uint8_t> hole(static_cast<size_t>(w) * h);
        env->GetByteArrayRegion(rgbArray, 0, static_cast<jsize>(rgb.size()), reinterpret_cast<jbyte*>(rgb.data()));
        env->GetByteArrayRegion(holeArray, 0, static_cast<jsize>(hole.size()), reinterpret_cast<jbyte*>(hole.data()));
        for (int y = 0; y < h; ++y) {
            const uint8_t* row = back.ptr<uint8_t>(y + oy) + static_cast<size_t>(ox) * 3;
            for (int x = 0; x < w; ++x) {
                const size_t i = static_cast<size_t>(y) * w + x;
                if (!hole[i]) continue;
                std::memcpy(rgb.data() + i * 3, row + static_cast<size_t>(x) * 3, 3);
            }
        }
        env->SetByteArrayRegion(outArray, 0, static_cast<jsize>(rgb.size()), reinterpret_cast<const jbyte*>(rgb.data()));
    } catch (const std::exception& e) {
        throwJava(env, e.what());
    }
}

} // extern "C"
