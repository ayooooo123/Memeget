#pragma once

#include <opencv2/core.hpp>

namespace memeget {

/**
 * Replaces each nonzero pixel of [hole] (a mask whose top-left sits at ([holeX], [holeY]) in [frame], an 8-bit RGB
 * or RGBA image) with colour from the nearest non-hole pixel in each of sixteen directions, marching up to
 * [extent] pixels. When most samples are one colour (a flat background such as a caption bar) that colour is
 * used, so letters cut by the hole end cleanly; otherwise the samples are blended by inverse squared distance.
 * Matches VideoInpaintEffect's shader.
 */
void fillFromSurroundings(cv::Mat& frame, const cv::Mat& hole, int holeX, int holeY, int extent);

}  // namespace memeget
