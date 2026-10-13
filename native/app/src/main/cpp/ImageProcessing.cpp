#include <rnexecutorch/data_processing/ImageProcessing.h>
#include <rnexecutorch/Error.h>
#include <rnexecutorch/ErrorCodes.h>
#include <executorch/extension/tensor/tensor_ptr_maker.h>
#include <opencv2/opencv.hpp>
#include <algorithm>
#include <array>
#include <cmath>
#include <string>
#include <vector>

namespace rnexecutorch::image_processing {

using executorch::aten::Tensor;
using executorch::extension::TensorPtr;

std::vector<float> colorMatToVector(const cv::Mat &mat) {
  return colorMatToVector(mat, cv::Scalar(0.0, 0.0, 0.0),
                          cv::Scalar(1.0, 1.0, 1.0));
}

std::vector<float> colorMatToVector(const cv::Mat &mat, cv::Scalar mean,
                                    cv::Scalar variance) {
  int pixelCount = mat.cols * mat.rows;
  std::vector<float> v(pixelCount * 3);

  for (int i = 0; i < pixelCount; i++) {
    int row = i / mat.cols;
    int col = i % mat.cols;
    cv::Vec3b pixel = mat.at<cv::Vec3b>(row, col);
    v[0 * pixelCount + i] =
        static_cast<float>((pixel[0] - mean[0] * 255.0) / (variance[0] * 255.0));
    v[1 * pixelCount + i] =
        static_cast<float>((pixel[1] - mean[1] * 255.0) / (variance[1] * 255.0));
    v[2 * pixelCount + i] =
        static_cast<float>((pixel[2] - mean[2] * 255.0) / (variance[2] * 255.0));
  }

  return v;
}

cv::Mat bufferToColorMat(const std::span<const float> &buffer,
                         cv::Size matSize) {
  cv::Mat mat(matSize, CV_8UC3);

  int pixelCount = matSize.width * matSize.height;
  for (int i = 0; i < pixelCount; i++) {
    int row = i / matSize.width;
    int col = i % matSize.width;

    float r = buffer[0 * pixelCount + i];
    float g = buffer[1 * pixelCount + i];
    float b = buffer[2 * pixelCount + i];

    cv::Vec3b color(static_cast<uchar>(b * 255), static_cast<uchar>(g * 255),
                    static_cast<uchar>(r * 255));
    mat.at<cv::Vec3b>(row, col) = color;
  }

  return mat;
}


cv::Mat readImage(const std::string &imageURI) {
  std::string filePath = imageURI;
  constexpr std::string_view kFilePrefix = "file://";
  if (filePath.starts_with(kFilePrefix)) {
    filePath = filePath.substr(kFilePrefix.length());
  }

  cv::Mat image = cv::imread(filePath, cv::IMREAD_COLOR);
  if (image.empty()) {
    throw RnExecutorchError(RnExecutorchErrorCode::FileReadFailed,
                            "Failed to load image from path: " + filePath);
  }
  return image;
}

TensorPtr getTensorFromMatrix(const std::vector<int32_t> &tensorDims,
                              const cv::Mat &matrix) {
  return executorch::extension::make_tensor_ptr(tensorDims,
                                                colorMatToVector(matrix));
}

TensorPtr getTensorFromMatrix(const std::vector<int32_t> &tensorDims,
                              const cv::Mat &matrix, cv::Scalar mean,
                              cv::Scalar variance) {
  return executorch::extension::make_tensor_ptr(
      tensorDims, colorMatToVector(matrix, mean, variance));
}

TensorPtr getTensorFromMatrixGray(const std::vector<int32_t> &tensorDims,
                                  const cv::Mat &matrix) {
  return executorch::extension::make_tensor_ptr(tensorDims,
                                                grayMatToVector(matrix));
}

std::vector<float> grayMatToVector(const cv::Mat &mat) {
  cv::Mat floatMat;
  if (mat.type() != CV_32F) {
    mat.convertTo(floatMat, CV_32F);
  } else {
    floatMat = mat;
  }

  if (floatMat.isContinuous()) {
    return {floatMat.ptr<float>(), floatMat.ptr<float>() + floatMat.total()};
  }

  std::vector<float> v;
  v.reserve(floatMat.total());
  for (int i = 0; i < floatMat.rows; ++i) {
    v.insert(v.end(), floatMat.ptr<float>(i), floatMat.ptr<float>(i) + floatMat.cols);
  }
  return v;
}

cv::Mat getMatrixFromTensor(cv::Size size, const Tensor &tensor) {
  auto resultData = static_cast<const float *>(tensor.const_data_ptr());
  return bufferToColorMat(std::span<const float>(resultData, tensor.numel()),
                          size);
}

cv::Mat resizePadded(const cv::Mat inputImage, cv::Size targetSize) {
  cv::Size inputSize = inputImage.size();
  if (inputSize.width == 0 || inputSize.height == 0) {
    return cv::Mat::zeros(targetSize, inputImage.type());
  }
  const float heightRatio =
      static_cast<float>(targetSize.height) / inputSize.height;
  const float widthRatio =
      static_cast<float>(targetSize.width) / inputSize.width;
  const float resizeRatio = std::min(heightRatio, widthRatio);
  const int newWidth = std::max(1, static_cast<int>(inputSize.width * resizeRatio));
  const int newHeight = std::max(1, static_cast<int>(inputSize.height * resizeRatio));
  cv::Mat resizedImg;
  cv::resize(inputImage, resizedImg, cv::Size(newWidth, newHeight), 0, 0,
             cv::INTER_AREA);

  constexpr int minCornerPatchSize = 1;
  constexpr int cornerPatchFractionSize = 30;
  int cornerPatchSize =
      std::min(inputSize.height, inputSize.width) / cornerPatchFractionSize;
  cornerPatchSize = std::max(minCornerPatchSize, cornerPatchSize);

  const std::array<cv::Mat, 4> corners = {
      inputImage(cv::Rect(0, 0, cornerPatchSize, cornerPatchSize)),
      inputImage(cv::Rect(inputSize.width - cornerPatchSize, 0, cornerPatchSize,
                          cornerPatchSize)),
      inputImage(cv::Rect(0, inputSize.height - cornerPatchSize,
                          cornerPatchSize, cornerPatchSize)),
      inputImage(cv::Rect(inputSize.width - cornerPatchSize,
                          inputSize.height - cornerPatchSize, cornerPatchSize,
                          cornerPatchSize))};

  cv::Scalar backgroundScalar = cv::mean(corners[0]);
  for (size_t i = 1; i < corners.size(); i++) {
    backgroundScalar += cv::mean(corners[i]);
  }
  backgroundScalar /= static_cast<double>(corners.size());

  constexpr size_t numChannels = 3;
  for (size_t i = 0; i < numChannels; ++i) {
    backgroundScalar[i] = cvFloor(backgroundScalar[i]);
  }

  const int deltaW = targetSize.width - newWidth;
  const int deltaH = targetSize.height - newHeight;
  const int top = deltaH / 2;
  const int bottom = deltaH - top;
  const int left = deltaW / 2;
  const int right = deltaW - left;

  cv::Mat centeredImg;
  cv::copyMakeBorder(resizedImg, centeredImg, top, bottom, left, right,
                     cv::BORDER_CONSTANT, backgroundScalar);

  return centeredImg;
}

std::pair<TensorPtr, cv::Size>
readImageToTensor(const std::string &path,
                  const std::vector<int32_t> &tensorDims,
                  bool maintainAspectRatio, std::optional<cv::Scalar> normMean,
                  std::optional<cv::Scalar> normStd) {
  cv::Mat input = readImage(path);
  cv::Size imageSize = input.size();

  if (tensorDims.size() < 2) {
    throw RnExecutorchError(RnExecutorchErrorCode::UnexpectedNumInputs,
                            "Unexpected tensor size, expected at least 2 dimensions");
  }
  cv::Size tensorSize = cv::Size(tensorDims[tensorDims.size() - 1],
                                 tensorDims[tensorDims.size() - 2]);

  if (maintainAspectRatio) {
    input = resizePadded(input, tensorSize);
  } else {
    cv::resize(input, input, tensorSize);
  }

  cv::cvtColor(input, input, cv::COLOR_BGR2RGB);

  if (normMean.has_value() && normStd.has_value()) {
    return {getTensorFromMatrix(tensorDims, input, normMean.value(), normStd.value()),
            imageSize};
  }
  return {getTensorFromMatrix(tensorDims, input), imageSize};
}

cv::Mat applySigmoid(const cv::Mat &logits) {
  cv::Mat probMat;
  cv::exp(-logits, probMat);
  probMat = 255.0f / (1.0f + probMat);
  probMat.convertTo(probMat, CV_8UC1);
  return probMat;
}

} // namespace rnexecutorch::image_processing
