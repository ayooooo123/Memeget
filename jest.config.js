// Lightweight unit-test setup. The app itself needs native modules (CLIP, OCR,
// SAF) that can't run under Node, so tests are scoped to the pure/IO-mockable
// logic — chiefly the link resolver, whose platform paths we want locked in so
// a future change can't silently break "save a meme from a shared link".
//
// Type-checking the whole app is `npm run typecheck`; here we disable ts-jest
// diagnostics so the expo tsconfig's app-wide settings don't fail the run.
module.exports = {
  testEnvironment: 'node',
  testMatch: ['**/*.test.ts'],
  // `.worktrees/` holds other branches' checkouts; their suites belong to them.
  testPathIgnorePatterns: ['/node_modules/', '/\\.worktrees/'],
  modulePathIgnorePatterns: ['<rootDir>/\\.worktrees/'],
  transform: {
    '^.+\\.ts$': ['ts-jest', { diagnostics: false }],
  },
};
