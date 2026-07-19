// Manual mock so pure logic in src/visionCore.ts (prompt assembly, grounding,
// reply parsing) is unit-testable under Node. The real package pulls in native
// bindings that can't load off-device; the app only imports opaque MODEL
// descriptors from it, so empty stand-ins are enough for the tests.
module.exports = {
  LFM2_5_VL_1_6B_QUANTIZED: { modelSource: 'mock-lfm' },
};
