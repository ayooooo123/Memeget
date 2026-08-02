import {
  MEME_TEXT_COLOR_SWATCHES,
  MEME_TEXT_LAYOUT_TOLERANCE_PX,
  MEME_TEXT_PRESET_IDS,
  applyMemeTextPreset,
  buildMemeTextLayoutSpec,
  MEME_TEXT_MAX_LENGTH,
  clampMemeTextContent,
  createMemeTextLayer,
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
  test('converts normalized layer data into deterministic canvas-pixel layout, baselines, and wrapping', () => {
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
        lines: [
          { text: 'we need a deterministic subtitle wrap', widthPx: 483, baselinePx: 23, topPx: 0 },
          { text: 'for android parity', widthPx: 216, baselinePx: 52.5, topPx: 29.5 },
        ],
        widthPx: 500,
        heightPx: 59,
        lineHeightPx: 29.5,
      },
      diagnostics: {
        androidStaticLayoutTolerancePx: MEME_TEXT_LAYOUT_TOLERANCE_PX,
      },
    });
  });

  test('preserves explicit line breaks and wraps long tokens without empty lines', () => {
    const layer = createMemeTextLayer('plain', 'plain', {
      text: 'first line\nSupercalifragilisticexpialidocious token',
      width: 0.22,
      fontSize: 0.08,
    });

    const spec = buildMemeTextLayoutSpec(layer, kf(), { canvasWidthPx: 600, canvasHeightPx: 400 });

    expect(spec.layout.lines.map((line) => line.text)).toEqual([
      'first line',
      'Superca',
      'lifragilis',
      'ticexpia',
      'lidociou',
      's token',
    ]);
    expect(spec.layout.lines.every((line) => line.text.length > 0)).toBe(true);
    expect(spec.layout.lines.every((line) => line.widthPx <= spec.canvas.wrapWidthPx)).toBe(true);
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
});
