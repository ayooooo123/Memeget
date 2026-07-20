// Manual mock so pure logic in src/visionCore.ts (prompt assembly, grounding,
// reply parsing) is unit-testable under Node. The real package pulls in native
// bindings that can't load off-device; the app only imports the opaque MODEL
// descriptor from it, so an empty stand-in is enough for the tests.
module.exports = {
  GEMMA4_E2B_MM: { modelName: 'gemma4-e2b-multimodal', modelSource: 'mock-gemma', capabilities: ['vision', 'audio'] },
};
