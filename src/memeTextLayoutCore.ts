import { colors, radius, space } from './theme';
import type { TextLayer, TextStyle, TimeRangeUs, TransformKeyframe } from './memeEditProjectCore';

export const MEME_TEXT_PRESET_IDS = ['impact', 'subtitle', 'label', 'news', 'bubble', 'plain'] as const;
export type MemeTextPresetId = typeof MEME_TEXT_PRESET_IDS[number];

export const MEME_TEXT_LAYOUT_TOLERANCE_DIP = 2;
export const MEME_TEXT_MAX_LENGTH = 20_000;


export const MEME_TEXT_BOUNDS = Object.freeze({
  minWrapWidth: 0.12,
  maxWrapWidth: 1,
  defaultWrapWidth: 0.72,
  minFontSize: 0.035,
  maxFontSize: 0.2,
  defaultFontSize: 0.09,
});

export const MEME_TEXT_COLOR_SWATCHES = [
  colors.text,
  colors.bg,
  colors.volt,
  colors.onVolt,
  colors.textDim,
  colors.danger,
  colors.good,
  colors.accent,
] as const;

export type MemeTextBackingTail = 'none' | 'bottom-left';

export interface MemeTextBackingDefaults {
  color: string | null;
  radiusScale: number;
  paddingXScale: number;
  paddingYScale: number;
  tail: MemeTextBackingTail;
}

export interface MemeTextPresetDefaults {
  preset: MemeTextPresetId;
  width: number;
  fontSize: number;
  style: TextStyle;
  font: MemeTextFontSpec;
  backing: MemeTextBackingDefaults;
}
export interface MemeTextFontSpec {
  family: 'Anton' | 'NotoSans';
  weight: '400' | '700' | '900';
  lineHeightRatio: number;
  letterSpacingEm: number;
  includeFontPadding: false;
}

export interface MemeTextLayoutLine {
  text: string;
  start: number;
  end: number;
  widthDip: number;
  baselineDip: number;
  topDip: number;
}

export interface MemeTextLayoutSpec {
  id: string;
  preset: MemeTextPresetId;
  text: string;
  displayText: string;
  normalized: {
    center: { x: number; y: number };
    width: number;
    fontSize: number;
  };
  canvas: {
    widthDip: number;
    heightDip: number;
    centerDip: { x: number; y: number };
    wrapWidthDip: number;
    fontSizeDip: number;
  };
  transform: {
    scale: number;
    rotationDegrees: number;
    opacity: number;
  };
  font: MemeTextFontSpec;
  align: TextStyle['align'];
  fill: { color: string; opacity: number };
  outline: { color: string; widthDip: number };
  backing: { color: string | null; radiusDip: number; paddingXDip: number; paddingYDip: number; tail: MemeTextBackingTail };
  layout: {
    lines: MemeTextLayoutLine[];
    widthDip: number;
    heightDip: number;
    lineHeightDip: number;
  };
  diagnostics: {
    androidStaticLayoutToleranceDip: number;
  };
}

export interface NativeMemeTextLayoutInput {
  text: string;
  fontFamily: MemeTextFontSpec['family'];
  fontWeight: number;
  fontSizeDip: number;
  letterSpacingEm: number;
  widthDip: number;
  align: TextStyle['align'];
  lineHeightDip: number;
}

export interface NativeMemeTextLayoutResult {
  widthDip: number;
  heightDip: number;
  includeFontPadding: boolean;
  toleranceDip: number;
  lines: Array<{ text: string; start: number; end: number; widthDip: number; topDip: number; baselineDip: number }>;
}

export interface NativeMemeTextLayoutComparison {
  ok: boolean;
  lineCountDrift: number;
  maxWidthDriftDip: number;
  maxTopDriftDip: number;
  maxBaselineDriftDip: number;
}

export interface MemeTextPreviewFixture {
  preset: MemeTextPresetId;
  scale: number;
  input: NativeMemeTextLayoutInput;
}


const PRESET_DEFAULTS: Record<MemeTextPresetId, MemeTextPresetDefaults> = {
  impact: {
    preset: 'impact',
    width: 0.88,
    fontSize: 0.118,
    style: {
      preset: 'impact',
      color: colors.text,
      outlineColor: colors.bg,
      outlineScale: 0.22,
      backgroundColor: null,
      opacity: 1,
      align: 'center',
      uppercase: true,
    },
    font: { family: 'Anton', weight: '900', lineHeightRatio: 0.95, letterSpacingEm: 0.018, includeFontPadding: false },
    backing: { color: null, radiusScale: 0, paddingXScale: 0, paddingYScale: 0, tail: 'none' },
  },
  subtitle: {
    preset: 'subtitle',
    width: 0.78,
    fontSize: 0.05,
    style: {
      preset: 'subtitle',
      color: colors.text,
      outlineColor: colors.bg,
      outlineScale: 0.08,
      backgroundColor: colors.bg,
      opacity: 1,
      align: 'center',
      uppercase: false,
    },
    font: { family: 'NotoSans', weight: '700', lineHeightRatio: 1.18, letterSpacingEm: 0, includeFontPadding: false },
    backing: { color: colors.bg, radiusScale: 0.24, paddingXScale: 0.48, paddingYScale: 0.24, tail: 'none' },
  },
  label: {
    preset: 'label',
    width: 0.42,
    fontSize: 0.064,
    style: {
      preset: 'label',
      color: colors.onVolt,
      outlineColor: colors.onVolt,
      outlineScale: 0,
      backgroundColor: colors.volt,
      opacity: 1,
      align: 'center',
      uppercase: true,
    },
    font: { family: 'NotoSans', weight: '900', lineHeightRatio: 1.05, letterSpacingEm: 0.035, includeFontPadding: false },
    backing: { color: colors.volt, radiusScale: 0.18, paddingXScale: 0.42, paddingYScale: 0.2, tail: 'none' },
  },
  news: {
    preset: 'news',
    width: 1,
    fontSize: 0.058,
    style: {
      preset: 'news',
      color: colors.onVolt,
      outlineColor: colors.onVolt,
      outlineScale: 0,
      backgroundColor: colors.volt,
      opacity: 1,
      align: 'left',
      uppercase: true,
    },
    font: { family: 'NotoSans', weight: '900', lineHeightRatio: 1.02, letterSpacingEm: 0.02, includeFontPadding: false },
    backing: { color: colors.volt, radiusScale: 0, paddingXScale: 0.38, paddingYScale: 0.22, tail: 'none' },
  },
  bubble: {
    preset: 'bubble',
    width: 0.62,
    fontSize: 0.064,
    style: {
      preset: 'bubble',
      color: colors.bg,
      outlineColor: colors.bg,
      outlineScale: 0,
      backgroundColor: colors.text,
      opacity: 1,
      align: 'left',
      uppercase: false,
    },
    font: { family: 'NotoSans', weight: '700', lineHeightRatio: 1.12, letterSpacingEm: 0, includeFontPadding: false },
    backing: { color: colors.text, radiusScale: 0.55, paddingXScale: 0.5, paddingYScale: 0.34, tail: 'bottom-left' },
  },
  plain: {
    preset: 'plain',
    width: 0.58,
    fontSize: 0.06,
    style: {
      preset: 'plain',
      color: colors.text,
      outlineColor: colors.bg,
      outlineScale: 0,
      backgroundColor: null,
      opacity: 1,
      align: 'center',
      uppercase: false,
    },
    font: { family: 'NotoSans', weight: '400', lineHeightRatio: 1.16, letterSpacingEm: 0, includeFontPadding: false },
    backing: { color: null, radiusScale: 0, paddingXScale: 0, paddingYScale: 0, tail: 'none' },
  },
};

function round(value: number): number {
  return Math.round(value * 1_000_000) / 1_000_000;
}

function roundDip(value: number): number {
  return Math.round(value * 10) / 10;
}

function clampNumber(value: number, min: number, max: number, fallback: number): number {
  if (!Number.isFinite(value)) return fallback;
  return Math.min(max, Math.max(min, value));
}

export function normalizeMemeTextWrapWidth(value: number): number {
  return round(clampNumber(value, MEME_TEXT_BOUNDS.minWrapWidth, MEME_TEXT_BOUNDS.maxWrapWidth, MEME_TEXT_BOUNDS.defaultWrapWidth));
}

export function normalizeMemeTextFontSize(value: number): number {
  return round(clampNumber(value, MEME_TEXT_BOUNDS.minFontSize, MEME_TEXT_BOUNDS.maxFontSize, MEME_TEXT_BOUNDS.defaultFontSize));
}

export function isMemeTextPreset(value: string): value is MemeTextPresetId {
  return Object.prototype.hasOwnProperty.call(PRESET_DEFAULTS, value);
}

export function getMemeTextPresetDefaults(preset: MemeTextPresetId): MemeTextPresetDefaults {
  const defaults = PRESET_DEFAULTS[preset];
  return {
    preset: defaults.preset,
    width: defaults.width,
    fontSize: defaults.fontSize,
    style: { ...defaults.style },
    font: { ...defaults.font },
    backing: { ...defaults.backing },
  };
}

export function defaultMemeTextFontSizeForPreset(preset: TextStyle['preset']): number {
  return PRESET_DEFAULTS[preset].fontSize;
}

export function createMemeTextLayer(
  id: string,
  preset: MemeTextPresetId = 'impact',
  overrides: Partial<Omit<TextLayer, 'id' | 'kind' | 'style'>> & { style?: Partial<TextStyle> } = {}
): TextLayer {
  const defaults = getMemeTextPresetDefaults(preset);
  const active = overrides.active === undefined ? null : cloneActive(overrides.active);
  return {
    id,
    kind: 'text',
    text: overrides.text ?? '',
    width: normalizeMemeTextWrapWidth(overrides.width ?? defaults.width),
    fontSize: normalizeMemeTextFontSize(overrides.fontSize ?? defaults.fontSize),
    style: { ...defaults.style, ...overrides.style, preset },
    active,
    keyframes: overrides.keyframes
      ? overrides.keyframes.map((frame) => ({ ...frame, center: { ...frame.center } }))
      : [defaultKeyframe(active?.startUs ?? 0, preset)],
  };
}

export function applyMemeTextPreset(layer: TextLayer, preset: MemeTextPresetId): TextLayer {
  const defaults = getMemeTextPresetDefaults(preset);
  return {
    ...layer,
    width: defaults.width,
    fontSize: defaults.fontSize,
    style: { ...defaults.style },
    active: cloneActive(layer.active),
    keyframes: layer.keyframes.map((frame) => ({ ...frame, center: { ...frame.center } })),
  };
}

export function clampMemeTextContent(text: string): string {
  return text.length > MEME_TEXT_MAX_LENGTH ? text.slice(0, MEME_TEXT_MAX_LENGTH) : text;
}

export function textDisplayText(layer: Pick<TextLayer, 'text' | 'style'>): string {
  return layer.style.uppercase ? layer.text.toLocaleUpperCase() : layer.text;
}

function cloneActive(active: TimeRangeUs | null): TimeRangeUs | null {
  return active ? { ...active } : null;
}

function defaultKeyframe(timeUs: number, preset: MemeTextPresetId): TransformKeyframe {
  const centerYByPreset: Record<MemeTextPresetId, number> = {
    impact: 0.18,
    subtitle: 0.82,
    label: 0.24,
    news: 0.88,
    bubble: 0.36,
    plain: 0.5,
  };
  return {
    timeUs,
    center: { x: 0.5, y: centerYByPreset[preset] },
    scale: 1,
    rotationDegrees: 0,
    opacity: 1,
    easing: 'linear',
  };
}


export function buildMemeTextLayoutSpec(
  layer: TextLayer,
  keyframe: TransformKeyframe,
  canvas: { canvasWidthDip: number; canvasHeightDip: number }
): MemeTextLayoutSpec {
  const preset = layer.style.preset;
  const defaults = PRESET_DEFAULTS[preset];
  const width = normalizeMemeTextWrapWidth(layer.width);
  const fontSize = normalizeMemeTextFontSize(layer.fontSize);
  const canvasWidthDip = Math.max(1, Math.round(canvas.canvasWidthDip));
  const canvasHeightDip = Math.max(1, Math.round(canvas.canvasHeightDip));
  const fontSizeDip = Math.max(1, Math.round(fontSize * canvasHeightDip));
  const wrapWidthDip = Math.max(1, Math.round(width * canvasWidthDip));
  const font = defaults.font;
  const lines: MemeTextLayoutLine[] = [];
  const lineHeightDip = roundDip(fontSizeDip * font.lineHeightRatio);
  const layoutHeightDip = 0;
  const outlineWidthDip = Math.round(layer.style.outlineScale * fontSizeDip);
  const backing = defaults.backing;
  return {
    id: layer.id,
    preset,
    text: layer.text,
    displayText: textDisplayText(layer),
    normalized: {
      center: { x: round(clampNumber(keyframe.center.x, 0, 1, 0.5)), y: round(clampNumber(keyframe.center.y, 0, 1, 0.5)) },
      width,
      fontSize,
    },
    canvas: {
      widthDip: canvasWidthDip,
      heightDip: canvasHeightDip,
      centerDip: {
        x: Math.round(clampNumber(keyframe.center.x, 0, 1, 0.5) * canvasWidthDip),
        y: Math.round(clampNumber(keyframe.center.y, 0, 1, 0.5) * canvasHeightDip),
      },
      wrapWidthDip,
      fontSizeDip,
    },
    transform: {
      scale: round(clampNumber(keyframe.scale, 0.01, 16, 1)),
      rotationDegrees: round(clampNumber(keyframe.rotationDegrees, -36_000, 36_000, 0)),
      opacity: round(clampNumber(keyframe.opacity, 0, 1, 1)),
    },
    font: { ...font },
    align: layer.style.align,
    fill: { color: layer.style.color, opacity: layer.style.opacity },
    outline: { color: layer.style.outlineColor, widthDip: outlineWidthDip },
    backing: {
      color: layer.style.backgroundColor,
      radiusDip: Math.round(backing.radiusScale * fontSizeDip),
      paddingXDip: Math.round(backing.paddingXScale * fontSizeDip) || (layer.style.backgroundColor ? space.sm : 0),
      paddingYDip: Math.round(backing.paddingYScale * fontSizeDip) || (layer.style.backgroundColor ? space.xs : 0),
      tail: layer.style.backgroundColor ? backing.tail : 'none',
    },
    layout: {
      lines,
      widthDip: wrapWidthDip,
      heightDip: layoutHeightDip,
      lineHeightDip,
    },
    diagnostics: {
      androidStaticLayoutToleranceDip: MEME_TEXT_LAYOUT_TOLERANCE_DIP,
    },
  };
}

export function nativeMemeTextLayoutInputFromSpec(spec: MemeTextLayoutSpec): NativeMemeTextLayoutInput {
  return {
    text: spec.displayText,
    fontFamily: spec.font.family,
    fontWeight: Number(spec.font.weight),
    lineHeightDip: spec.layout.lineHeightDip,
    fontSizeDip: spec.canvas.fontSizeDip,
    letterSpacingEm: spec.font.letterSpacingEm,
    widthDip: spec.canvas.wrapWidthDip,
    align: spec.align,
  };
}

export function memeTextMeasureKey(spec: MemeTextLayoutSpec): string {
  return JSON.stringify(nativeMemeTextLayoutInputFromSpec(spec));
}

export function buildMemeTextPreviewFixtures(): MemeTextPreviewFixture[] {
  const texts: Record<MemeTextPresetId, string> = {
    impact: 'impact layout fixture words',
    subtitle: 'subtitle layout fixture words',
    label: 'label layout fixture words',
    news: 'news layout fixture words',
    bubble: 'bubble layout fixture words',
    plain: 'plain layout\n\nfixture words',
  };
  const scales: Record<MemeTextPresetId, number> = {
    impact: 1,
    subtitle: 1.25,
    label: 1,
    news: 1.5,
    bubble: 1,
    plain: 1,
  };
  return MEME_TEXT_PRESET_IDS.map((preset) => {
    const layer = createMemeTextLayer(`fixture-${preset}`, preset, { text: texts[preset] });
    const spec = buildMemeTextLayoutSpec(layer, defaultKeyframe(0, preset), { canvasWidthDip: 720, canvasHeightDip: 1_280 });
    return { preset, scale: scales[preset], input: nativeMemeTextLayoutInputFromSpec(spec) };
  });
}

export function compareNativeMemeTextLayoutResults(
  expected: NativeMemeTextLayoutResult,
  actual: NativeMemeTextLayoutResult,
  scale: number,
  toleranceDip: number
): NativeMemeTextLayoutComparison {
  const count = Math.max(expected.lines.length, actual.lines.length);
  let maxWidthDriftDip = Math.abs(actual.widthDip - expected.widthDip) * scale;
  let maxTopDriftDip = Math.abs(actual.heightDip - expected.heightDip) * scale;
  let maxBaselineDriftDip = 0;
  let contentMatches = actual.includeFontPadding === expected.includeFontPadding;
  for (let index = 0; index < count; index += 1) {
    const left = expected.lines[index];
    const right = actual.lines[index];
    if (!left || !right) {
      contentMatches = false;
      maxWidthDriftDip = Number.POSITIVE_INFINITY;
      maxTopDriftDip = Number.POSITIVE_INFINITY;
      maxBaselineDriftDip = Number.POSITIVE_INFINITY;
      break;
    }
    if (left.text !== right.text || left.start !== right.start || left.end !== right.end) contentMatches = false;
    maxWidthDriftDip = Math.max(maxWidthDriftDip, Math.abs(right.widthDip - left.widthDip) * scale);
    maxTopDriftDip = Math.max(maxTopDriftDip, Math.abs(right.topDip - left.topDip) * scale);
    maxBaselineDriftDip = Math.max(maxBaselineDriftDip, Math.abs(right.baselineDip - left.baselineDip) * scale);
  }
  const lineCountDrift = actual.lines.length - expected.lines.length;
  return {
    ok: contentMatches && lineCountDrift === 0 && maxWidthDriftDip <= toleranceDip && maxTopDriftDip <= toleranceDip && maxBaselineDriftDip <= toleranceDip,
    lineCountDrift,
    maxWidthDriftDip,
    maxTopDriftDip,
    maxBaselineDriftDip,
  };
}

export function compareNativeMemeTextLayoutToSpec(
  spec: MemeTextLayoutSpec,
  native: NativeMemeTextLayoutResult
): NativeMemeTextLayoutComparison {
  if (spec.layout.lines.length === 0) {
    return {
      ok: native.includeFontPadding === false,
      lineCountDrift: 0,
      maxWidthDriftDip: 0,
      maxTopDriftDip: 0,
      maxBaselineDriftDip: 0,
    };
  }
  const scale = spec.transform.scale;
  const limit = spec.diagnostics.androidStaticLayoutToleranceDip;
  const count = Math.max(spec.layout.lines.length, native.lines.length);
  let maxWidthDriftDip = Math.abs(native.widthDip - spec.layout.widthDip) * scale;
  let maxTopDriftDip = Math.abs(native.heightDip - spec.layout.heightDip) * scale;
  let maxBaselineDriftDip = 0;
  let contentMatches = native.includeFontPadding === false;
  for (let index = 0; index < count; index += 1) {
    const expected = spec.layout.lines[index];
    const actual = native.lines[index];
    if (!expected || !actual) {
      contentMatches = false;
      maxWidthDriftDip = Number.POSITIVE_INFINITY;
      maxTopDriftDip = Number.POSITIVE_INFINITY;
      maxBaselineDriftDip = Number.POSITIVE_INFINITY;
      break;
    }
    if (actual.text !== expected.text || actual.start !== expected.start || actual.end !== expected.end) {
      contentMatches = false;
    }
    maxWidthDriftDip = Math.max(maxWidthDriftDip, Math.abs(actual.widthDip - expected.widthDip) * scale);
    maxTopDriftDip = Math.max(maxTopDriftDip, Math.abs(actual.topDip - expected.topDip) * scale);
    maxBaselineDriftDip = Math.max(maxBaselineDriftDip, Math.abs(actual.baselineDip - expected.baselineDip) * scale);
  }
  const lineCountDrift = native.lines.length - spec.layout.lines.length;
  return {
    ok: contentMatches &&
      lineCountDrift === 0 &&
      maxWidthDriftDip <= limit &&
      maxTopDriftDip <= limit &&
      maxBaselineDriftDip <= limit,
    lineCountDrift,
    maxWidthDriftDip,
    maxTopDriftDip,
    maxBaselineDriftDip,
  };
}

export function memeTextBackingRadiusForPreview(spec: MemeTextLayoutSpec): number {
  if (spec.backing.color === null) return 0;
  return spec.backing.radiusDip || radius.sm;
}
