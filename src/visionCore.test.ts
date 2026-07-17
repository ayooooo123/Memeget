// Tests for the pure VLM-caption helpers. The important one here is
// captionLikelyComplete: it decides when to interrupt() a running generation,
// so getting it wrong either clips a real field (too eager) or wastes decode
// time (too lax). visionCore only pulls react-native-executorch for the model
// descriptors (MODEL); stub it so the pure helpers can be tested under Node.
jest.mock('react-native-executorch', () => ({
  GEMMA4_E2B_MM: { modelSource: 'stub-gemma' },
  LFM2_5_VL_450M_QUANTIZED: { modelSource: 'stub-lfm' },
}));

import {
  captionLikelyComplete,
  parseVision,
  CAPTION_TOKEN_BUDGET,
} from './visionCore';

// A well-formed reply in the exact four-line order the prompt requests.
const FULL =
  'CAPTION: a man turns to admire another woman while his girlfriend glares\n' +
  'TEXT: me, new framework, the project i should be working on\n' +
  'SUBJECTS: man, girlfriend, other woman\n' +
  'TAGS: distracted boyfriend, jealousy, disgust, relatable\n';

describe('captionLikelyComplete', () => {
  it('is false before any caption has streamed', () => {
    expect(captionLikelyComplete('')).toBe(false);
    expect(captionLikelyComplete('CAP')).toBe(false);
  });

  it('is false while only the caption line has arrived', () => {
    expect(captionLikelyComplete('CAPTION: a man turns to look\n')).toBe(false);
  });

  it('is false when TAGS has started but not finished (no trailing newline)', () => {
    const partial = FULL.slice(0, FULL.lastIndexOf('\n')); // drop the final newline
    expect(captionLikelyComplete(partial)).toBe(false);
  });

  it('is true once the TAGS line is finished', () => {
    expect(captionLikelyComplete(FULL)).toBe(true);
  });

  it('stays true (and lets us stop) when the model rambles past TAGS', () => {
    const rambly = FULL + 'Let me know if you want me to explain why this is funny...';
    expect(captionLikelyComplete(rambly)).toBe(true);
  });

  it('does not fire on an early out-of-order TAGS with no caption yet', () => {
    // A malformed reply that emits TAGS first must not stop us before the
    // headline CAPTION field has had a chance to stream.
    expect(captionLikelyComplete('TAGS: a, b, c\nSUBJECTS: x\n')).toBe(false);
  });

  it('is case- and label-delimiter-insensitive', () => {
    const lower =
      'caption - a cat\n' + 'text -\n' + 'subjects - cat\n' + 'tags - cat, cute\n';
    expect(captionLikelyComplete(lower)).toBe(true);
  });

  it('leaves a well-formed, interrupted reply fully parseable', () => {
    // The whole point: stopping at TAGS-complete still yields every field.
    const res = parseVision(FULL);
    expect(res.caption).toContain('man turns to admire');
    expect(res.tags).toEqual(
      expect.arrayContaining(['distracted boyfriend', 'jealousy'])
    );
  });
});

describe('CAPTION_TOKEN_BUDGET', () => {
  it('is a sane runaway backstop, not a tight clip on normal output', () => {
    // Four short lines are well under this; the budget only catches a model that
    // never terminates its TAGS line.
    expect(CAPTION_TOKEN_BUDGET).toBeGreaterThan(100);
    expect(CAPTION_TOKEN_BUDGET).toBeLessThan(400);
  });
});
