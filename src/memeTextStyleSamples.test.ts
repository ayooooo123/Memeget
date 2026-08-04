
import sample from './memeTextStyleSamples.json';
import { inferOriginalTextStyle, relativeLuminance, splitGlyphAndBackground } from './memeImageEditCore';

// Synthetic arrays prove the algorithm; this proves it survives real pixels —
// anti-aliased glyph edges, a photographic background showing through the
// counters, and heavy letterforms that cover about half the box. Ground truth
// was established by inspecting the crop, not by asking the code.
describe('style inference on real exported meme pixels', () => {
  const colors = sample.colors as string[];

  test('reads the white caption off a dark photographic background', () => {
    const split = splitGlyphAndBackground(colors);
    expect(split).not.toBeNull();
    expect(relativeLuminance(split!.glyph)).toBeGreaterThan(relativeLuminance(split!.background));
    expect(split!.contrast).toBeGreaterThan(4);
  });

  test('treats it as a shouted caption and keeps it legible', () => {
    const style = inferOriginalTextStyle({
      sampledColors: colors,
      originalText: 'MEME TEXT',
      regionHeight: 0.095,
      lineCount: 1,
    });
    expect(style.uppercase).toBe(true);
    expect(style.preset).toBe('impact');
    // The original has a dark outline around white glyphs; the inferred style
    // must reproduce that relationship rather than inverting it.
    expect(relativeLuminance(style.color)).toBeGreaterThan(relativeLuminance(style.outlineColor));
    expect(style.fontSize).toBeGreaterThan(0);
  });

  test('does not hand back text that would vanish into its own patch', () => {
    const style = inferOriginalTextStyle({
      sampledColors: colors,
      originalText: 'MEME TEXT',
      regionHeight: 0.095,
      lineCount: 1,
    });
    expect(Math.abs(relativeLuminance(style.color) - relativeLuminance(style.backgroundColor))).toBeGreaterThan(0.05);
  });
});
