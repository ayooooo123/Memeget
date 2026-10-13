#pragma once

#include <atomic>
#include <string>

namespace memeget {

// Rewrites a GGUF file with every BF16 tensor stored as F16; tensor order, shapes, all other tensors and
// metadata are copied unchanged, except that a BF16 `general.file_type` label becomes F16. CPUs without
// the ARMv8.6 BF16 extension (for example Cortex-A76) then use FP16 vector kernels instead of a scalar
// BF16 fallback. Streams in bounded chunks, throws if a value is outside the F16 range or `cancelled`
// becomes true, and returns the number of converted tensors. The output is deterministic for a given input.
int convertBf16TensorsToF16(const std::string& sourcePath,
                            const std::string& destinationPath,
                            const std::atomic<bool>* cancelled);

} // namespace memeget
