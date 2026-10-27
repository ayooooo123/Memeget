#import <CoreGraphics/CoreGraphics.h>
#import <Foundation/Foundation.h>
#import <ImageIO/ImageIO.h>

#include <cmath>
#include <iostream>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include <executorch/extension/module/module.h>
#include <executorch/extension/tensor/tensor_ptr_maker.h>
#include <executorch/runtime/platform/runtime.h>

// Runs MobileCLIP-S2 (`mobileclip_s2_image_xnnpack_fp32.pte`, `5c7311ce...`) natively on Apple Silicon
// via `xcrun simctl spawn` against ExecutorchLib.xcframework (`ios-arm64-simulator`), outputting exact
// `recipe-et0.9.2` 512-d vectors at M-series speed with zero Python dependencies.
int main(int argc, char **argv) {
    @autoreleasepool {
        if (argc < 2) {
            std::cerr << "Usage: sim_s2_embedder <mobileclip_s2_image.pte>\n";
            return 1;
        }
        executorch::runtime::runtime_init();
        const bool textMode = (argc >= 3 && std::string(argv[1]) == "--text");
        std::string modelPath = textMode ? argv[2] : argv[1];
        auto mod = std::make_unique<executorch::extension::module::Module>(
            modelPath, executorch::extension::module::Module::LoadMode::MmapUseMlockIgnoreErrors);
        if (mod->load() != executorch::runtime::Error::Ok) {
            std::cerr << "Failed loading .pte: " << modelPath << "\n";
            return 2;
        }

        if (textMode) {
            std::string line;
            while (std::getline(std::cin, line)) {
                if (line.empty()) continue;
                std::vector<int64_t> ids(77, 0);
                std::stringstream ss(line);
                for (int i = 0; i < 77 && (ss >> ids[i]); ++i) {
                    if (ss.peek() == ',') ss.ignore();
                }
                std::vector<int64_t> mask(77, 0);
                for (int i = 0; i < 77; ++i) mask[i] = (ids[i] != 0) ? 1 : 0;
                auto tokTensor = executorch::extension::from_blob(
                    ids.data(), {1, 77}, executorch::aten::ScalarType::Long);
                auto maskTensor = executorch::extension::from_blob(
                    mask.data(), {1, 77}, executorch::aten::ScalarType::Long);
                std::vector<executorch::runtime::EValue> inputs;
                inputs.emplace_back(tokTensor);
                inputs.emplace_back(maskTensor);
                auto res = mod->forward(inputs);
                if (!res.ok() || res->empty() || !res->at(0).isTensor()) {
                    std::cout << "{\"ok\":false}\n" << std::flush;
                    continue;
                }
                const float *raw = res->at(0).toTensor().const_data_ptr<float>();
                double normSq = 0.0;
                for (int i = 0; i < 512; ++i) normSq += static_cast<double>(raw[i]) * raw[i];
                const float inv = normSq > 1e-12 ? static_cast<float>(1.0 / std::sqrt(normSq)) : 1.0f;
                std::ostringstream oss;
                oss << "{\"ok\":true,\"vec\":[";
                for (int i = 0; i < 512; ++i) {
                    if (i > 0) oss << ",";
                    oss << (raw[i] * inv);
                }
                oss << "]}\n";
                std::cout << oss.str() << std::flush;
            }
            return 0;
        }
        const int side = 256;
        const int plane = side * side;
        std::vector<uint8_t> rgba(plane * 4, 0);
        std::vector<float> planar(3 * plane, 0.0f);

        std::string path;
        while (std::getline(std::cin, path)) {
            @autoreleasepool {
                if (path.empty()) continue;
                NSString *nsPath = [NSString stringWithUTF8String:path.c_str()];
                NSURL *url = nsPath ? [NSURL fileURLWithPath:nsPath] : nil;
                CGImageSourceRef src = url ? CGImageSourceCreateWithURL((__bridge CFURLRef)url, nullptr) : nullptr;
                if (!src) {
                    std::cout << "{\"ok\":false}\n" << std::flush;
                    continue;
                }
                NSDictionary *opts = @{
                    (__bridge NSString *)kCGImageSourceCreateThumbnailFromImageAlways: @YES,
                    (__bridge NSString *)kCGImageSourceCreateThumbnailWithTransform: @YES,
                    (__bridge NSString *)kCGImageSourceThumbnailMaxPixelSize: @512
                };
                CGImageRef cgImg = CGImageSourceCreateThumbnailAtIndex(src, 0, (__bridge CFDictionaryRef)opts);
                CFRelease(src);
                if (!cgImg) {
                    std::cout << "{\"ok\":false}\n" << std::flush;
                    continue;
                }

                CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
                CGContextRef ctx = CGBitmapContextCreate(
                    rgba.data(), side, side, 8, side * 4, cs,
                    kCGImageAlphaNoneSkipLast | kCGBitmapByteOrder32Big);
                CGColorSpaceRelease(cs);
                CGContextSetInterpolationQuality(ctx, kCGInterpolationHigh);
                CGContextDrawImage(ctx, CGRectMake(0, 0, side, side), cgImg);
                CGContextRelease(ctx);
                CGImageRelease(cgImg);

                const float inv255 = 1.0f / 255.0f;
                for (int p = 0; p < plane; ++p) {
                    const int off = p * 4;
                    planar[p] = rgba[off] * inv255;
                    planar[plane + p] = rgba[off + 1] * inv255;
                    planar[2 * plane + p] = rgba[off + 2] * inv255;
                }

                auto tensor = executorch::extension::from_blob(
                    planar.data(), {1, 3, side, side}, executorch::aten::ScalarType::Float);
                auto res = mod->forward(tensor);
                if (!res.ok() || res->empty() || !res->at(0).isTensor()) {
                    std::cout << "{\"ok\":false}\n" << std::flush;
                    continue;
                }
                const auto &outT = res->at(0).toTensor();
                const float *raw = outT.const_data_ptr<float>();
                double normSq = 0.0;
                for (int i = 0; i < 512; ++i) normSq += static_cast<double>(raw[i]) * raw[i];
                const float inv = normSq > 1e-12 ? static_cast<float>(1.0 / std::sqrt(normSq)) : 1.0f;

                std::ostringstream oss;
                oss << "{\"ok\":true,\"vec\":[";
                for (int i = 0; i < 512; ++i) {
                    if (i > 0) oss << ",";
                    oss << (raw[i] * inv);
                }
                oss << "]}\n";
                std::cout << oss.str() << std::flush;
            }
        }
    }
    return 0;
}
