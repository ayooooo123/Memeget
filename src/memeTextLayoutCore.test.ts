import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  MEME_TEXT_COLOR_SWATCHES,
  MEME_TEXT_LAYOUT_TOLERANCE_PX,
  MEME_TEXT_PRESET_IDS,
  applyMemeTextPreset,
  buildMemeTextLayoutSpec,
  buildMemeTextPreviewFixtures,
  MEME_TEXT_MAX_LENGTH,
  clampMemeTextContent,
  createMemeTextLayer,
  memeTextMeasureKey,
  nativeMemeTextLayoutInputFromSpec,
  compareNativeMemeTextLayoutToSpec,
  compareNativeMemeTextLayoutResults,
  getMemeTextPresetDefaults,
  textDisplayText,
} from './memeTextLayoutCore';
import type { TextLayer, TransformKeyframe } from './memeEditProjectCore';

function kf(overrides: Partial<TransformKeyframe> = {}): TransformKeyframe {
  return {
    timeUs: 0,
    center: { x: 0.5, y: 0.5 },
    scale: 1,
    rotationDegrees: 0,
    opacity: 1,
    easing: 'linear',
    ...overrides,
  };
}

describe('meme text presets', () => {
  test('defines six selectable preset defaults with bounded normalized layout', () => {
    expect(MEME_TEXT_PRESET_IDS).toEqual(['impact', 'subtitle', 'label', 'news', 'bubble', 'plain']);

    const defaults = MEME_TEXT_PRESET_IDS.map((preset) => getMemeTextPresetDefaults(preset));
    expect(defaults).toHaveLength(6);
    expect(new Set(defaults.map((preset) => `${preset.width}:${preset.fontSize}:${preset.style.align}:${preset.style.backgroundColor}:${preset.style.outlineScale}`)).size).toBe(6);

    for (const defaultsForPreset of defaults) {
      expect(defaultsForPreset.width).toBeGreaterThanOrEqual(0.12);
      expect(defaultsForPreset.width).toBeLessThanOrEqual(1);
      expect(defaultsForPreset.fontSize).toBeGreaterThanOrEqual(0.035);
      expect(defaultsForPreset.fontSize).toBeLessThanOrEqual(0.2);
      expect(defaultsForPreset.style.opacity).toBeGreaterThanOrEqual(0);
      expect(defaultsForPreset.style.opacity).toBeLessThanOrEqual(1);
      expect(MEME_TEXT_COLOR_SWATCHES).toContain(defaultsForPreset.style.color);
      expect(MEME_TEXT_COLOR_SWATCHES).toContain(defaultsForPreset.style.outlineColor);
      if (defaultsForPreset.style.backgroundColor !== null) {
        expect(MEME_TEXT_COLOR_SWATCHES).toContain(defaultsForPreset.style.backgroundColor);
      }
    }

    expect(getMemeTextPresetDefaults('impact')).toMatchObject({
      style: { uppercase: true, outlineScale: 0.22, backgroundColor: null, align: 'center' },
    });
    expect(getMemeTextPresetDefaults('subtitle')).toMatchObject({
      style: { uppercase: false, backgroundColor: '#0a0b0e', align: 'center' },
    });
    expect(getMemeTextPresetDefaults('news')).toMatchObject({
      width: 1,
      style: { uppercase: true, backgroundColor: '#d8ff4a', align: 'left' },
    });
    expect(getMemeTextPresetDefaults('bubble')).toMatchObject({
      style: { uppercase: false, backgroundColor: '#f2f4f8', align: 'left' },
      backing: { tail: 'bottom-left' },
    });
  });

  test('creates text layers and applies preset changes without replacing user content or transform range', () => {
    const layer = createMemeTextLayer('caption-1', 'plain', {
      text: 'typed once, kept forever',
      active: { startUs: 1_000, endUs: 5_000 },
      keyframes: [kf({ center: { x: 0.25, y: 0.75 }, scale: 1.5, rotationDegrees: 12 })],
    });

    const changed = applyMemeTextPreset(layer, 'impact');

    expect(changed.id).toBe(layer.id);
    expect(changed.text).toBe('typed once, kept forever');
    expect(changed.active).toEqual(layer.active);
    expect(changed.keyframes).toEqual(layer.keyframes);
    expect(changed.style.preset).toBe('impact');
    expect(changed.style.uppercase).toBe(true);
    expect(changed.width).toBe(getMemeTextPresetDefaults('impact').width);
    expect(changed.fontSize).toBe(getMemeTextPresetDefaults('impact').fontSize);
  });

  test('uppercases display text without mutating stored content', () => {
    const layer = createMemeTextLayer('impact-caption', 'impact', { text: 'Do not overwrite iPhone slang' });

    expect(textDisplayText(layer)).toBe('DO NOT OVERWRITE IPHONE SLANG');
    expect(layer.text).toBe('Do not overwrite iPhone slang');
  });

  test('exports and applies the shared maximum text length', () => {
    expect(MEME_TEXT_MAX_LENGTH).toBe(20_000);
    expect(clampMemeTextContent('ok')).toBe('ok');
    expect(clampMemeTextContent('x'.repeat(MEME_TEXT_MAX_LENGTH + 10))).toHaveLength(MEME_TEXT_MAX_LENGTH);
  });
});

describe('serializable meme text layout contract', () => {
  test('converts normalized layer data into serializable raw text and native style inputs without TS prewrapping', () => {
    const layer = createMemeTextLayer('subtitle', 'subtitle', {
      text: 'we need a deterministic subtitle wrap for android parity',
      width: 0.5,
      fontSize: 0.05,
    });

    const spec = buildMemeTextLayoutSpec(layer, kf({ center: { x: 0.4, y: 0.8 }, scale: 1.2, rotationDegrees: -8, opacity: 0.8 }), {
      canvasWidthPx: 1_000,
      canvasHeightPx: 500,
    });

    expect(spec).toEqual({
      id: 'subtitle',
      preset: 'subtitle',
      text: 'we need a deterministic subtitle wrap for android parity',
      displayText: 'we need a deterministic subtitle wrap for android parity',
      normalized: {
        center: { x: 0.4, y: 0.8 },
        width: 0.5,
        fontSize: 0.05,
      },
      canvas: {
        widthPx: 1_000,
        heightPx: 500,
        centerPx: { x: 400, y: 400 },
        wrapWidthPx: 500,
        fontSizePx: 25,
      },
      transform: { scale: 1.2, rotationDegrees: -8, opacity: 0.8 },
      font: {
        family: 'NotoSans',
        weight: '700',
        lineHeightRatio: 1.18,
        letterSpacingEm: 0,
        includeFontPadding: false,
      },
      align: 'center',
      fill: { color: '#f2f4f8', opacity: 1 },
      outline: { color: '#0a0b0e', widthPx: 2 },
      backing: { color: '#0a0b0e', radiusPx: 6, paddingXPx: 12, paddingYPx: 6, tail: 'none' },
      layout: {
        lines: [],
        widthPx: 500,
        heightPx: 0,
        lineHeightPx: 29.5,
      },
      diagnostics: {
        androidStaticLayoutTolerancePx: MEME_TEXT_LAYOUT_TOLERANCE_PX,
      },
    });
  });

  test('preserves raw explicit line breaks for native preview wrapping', () => {
    const layer = createMemeTextLayer('plain', 'plain', {
      text: 'first line\n\nSupercalifragilisticexpialidocious token',
      width: 0.22,
      fontSize: 0.08,
    });

    const spec = buildMemeTextLayoutSpec(layer, kf(), { canvasWidthPx: 600, canvasHeightPx: 400 });

    expect(spec.displayText).toBe('first line\n\nSupercalifragilisticexpialidocious token');
    expect(spec.layout.lines).toEqual([]);
  });

  test('serializes representative Android StaticLayout parity fixtures with a two preview-pixel tolerance target', () => {
    const fixtures = MEME_TEXT_PRESET_IDS.map((preset) => buildMemeTextLayoutSpec(
      createMemeTextLayer(`fixture-${preset}`, preset, { text: `${preset} layout fixture words` }),
      kf(),
      { canvasWidthPx: 720, canvasHeightPx: 1_280 }
    ));

    expect(MEME_TEXT_LAYOUT_TOLERANCE_PX).toBe(2);
    expect(fixtures.map((fixture) => [fixture.preset, fixture.font.includeFontPadding, fixture.diagnostics.androidStaticLayoutTolerancePx])).toEqual([
      ['impact', false, 2],
      ['subtitle', false, 2],
      ['label', false, 2],
      ['news', false, 2],
      ['bubble', false, 2],
      ['plain', false, 2],
    ]);
  });

  test('native parity diagnostics consume serialized spec placement instead of a tolerance constant only', () => {
    const spec = buildMemeTextLayoutSpec(
      createMemeTextLayer('fixture-impact', 'impact', { text: 'native outline words' }),
      kf({ scale: 1.4, rotationDegrees: 3 }),
      { canvasWidthPx: 720, canvasHeightPx: 1_280 }
    );

    expect(nativeMemeTextLayoutInputFromSpec(spec)).toEqual({
      text: spec.displayText,
      fontFamily: spec.font.family,
      fontWeight: Number(spec.font.weight),
      fontSizePx: spec.canvas.fontSizePx,
      lineHeightPx: spec.layout.lineHeightPx,
      letterSpacingEm: spec.font.letterSpacingEm,
      widthPx: spec.canvas.wrapWidthPx,
      align: spec.align,
    });

    const exactNative = {
      widthPx: spec.canvas.wrapWidthPx,
      heightPx: spec.layout.heightPx,
      includeFontPadding: false,
      tolerancePx: MEME_TEXT_LAYOUT_TOLERANCE_PX,
      lines: spec.layout.lines.map((line) => ({ ...line })),
    };
    expect(compareNativeMemeTextLayoutToSpec(spec, exactNative)).toEqual({
      ok: true,
      lineCountDrift: 0,
      maxWidthDriftPx: 0,
      maxTopDriftPx: 0,
      maxBaselineDriftPx: 0,
    });

    const measuredNative = {
      ...exactNative,
      heightPx: 40,
      lines: [{ text: 'native line', start: 0, end: 11, widthPx: 10, topPx: 0, baselinePx: 30 }],
    };
    expect(compareNativeMemeTextLayoutToSpec(spec, measuredNative)).toMatchObject({
      ok: true,
      lineCountDrift: 0,
    });

    const driftedPreview = {
      ...measuredNative,
      lines: measuredNative.lines.map((line) => ({ ...line, baselinePx: line.baselinePx + 3 })),
    };
    expect(compareNativeMemeTextLayoutResults(measuredNative, driftedPreview, spec.transform.scale, MEME_TEXT_LAYOUT_TOLERANCE_PX)).toMatchObject({
      ok: false,
      maxBaselineDriftPx: 4.199999999999999,
    });
  });

  test('validates committed preview fixture JSON against the TypeScript-produced native inputs', () => {
    const fixturePath = join(__dirname, 'memeTextLayoutPreviewFixtures.json');
    expect(JSON.parse(readFileSync(fixturePath, 'utf8'))).toEqual(buildMemeTextPreviewFixtures());
  });

  test('keeps native measurement key stable across time-varying transform changes', () => {
    const layer = createMemeTextLayer('stable', 'subtitle', { text: 'same words', width: 0.5, fontSize: 0.05 });
    const base = buildMemeTextLayoutSpec(layer, kf({ center: { x: 0.2, y: 0.3 }, scale: 1, rotationDegrees: 0 }), { canvasWidthPx: 720, canvasHeightPx: 1_280 });
    const moved = buildMemeTextLayoutSpec(layer, kf({ center: { x: 0.8, y: 0.7 }, scale: 2, rotationDegrees: 45 }), { canvasWidthPx: 720, canvasHeightPx: 1_280 });
    const rewrapped = buildMemeTextLayoutSpec({ ...layer, width: 0.7 }, kf(), { canvasWidthPx: 720, canvasHeightPx: 1_280 });

    expect(memeTextMeasureKey(moved)).toBe(memeTextMeasureKey(base));
    expect(memeTextMeasureKey(rewrapped)).not.toBe(memeTextMeasureKey(base));
  });
});
