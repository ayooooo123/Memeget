import { colors, radius, space } from './theme';
import type { TextLayer, TextStyle, TimeRangeUs, TransformKeyframe } from './memeEditProjectCore';

export const MEME_TEXT_PRESET_IDS = ['impact', 'subtitle', 'label', 'news', 'bubble', 'plain'] as const;
export type MemeTextPresetId = typeof MEME_TEXT_PRESET_IDS[number];

export const MEME_TEXT_LAYOUT_TOLERANCE_PX = 2;
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
  widthPx: number;
  baselinePx: number;
  topPx: number;
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
    widthPx: number;
    heightPx: number;
    centerPx: { x: number; y: number };
    wrapWidthPx: number;
    fontSizePx: number;
  };
  transform: {
    scale: number;
    rotationDegrees: number;
    opacity: number;
  };
  font: MemeTextFontSpec;
  align: TextStyle['align'];
  fill: { color: string; opacity: number };
  outline: { color: string; widthPx: number };
  backing: { color: string | null; radiusPx: number; paddingXPx: number; paddingYPx: number; tail: MemeTextBackingTail };
  layout: {
    lines: MemeTextLayoutLine[];
    widthPx: number;
    heightPx: number;
    lineHeightPx: number;
  };
  diagnostics: {
    androidStaticLayoutTolerancePx: number;
  };
}

export interface NativeMemeTextLayoutInput {
  text: string;
  fontFamily: MemeTextFontSpec['family'];
  fontWeight: number;
  fontSizePx: number;
  letterSpacingEm: number;
  widthPx: number;
  align: TextStyle['align'];
  lineHeightPx: number;
}

export interface NativeMemeTextLayoutResult {
  widthPx: number;
  heightPx: number;
  includeFontPadding: boolean;
  tolerancePx: number;
  lines: Array<{ text: string; start: number; end: number; widthPx: number; topPx: number; baselinePx: number }>;
}

export interface NativeMemeTextLayoutComparison {
  ok: boolean;
  lineCountDrift: number;
  maxWidthDriftPx: number;
  maxTopDriftPx: number;
  maxBaselineDriftPx: number;
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

function roundPx(value: number): number {
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

function charWidthEm(char: string, font: MemeTextFontSpec): number {
  if (char === ' ') return 0.32;
  if (char === '\t') return 0.64;
  if ('.,:;!|'.includes(char)) return 0.24;
  if ('ilI[]()'.includes(char)) return 0.3;
  if ('tfjr'.includes(char)) return 0.4;
  if ('mwMW@#%&'.includes(char)) return 0.86;
  if ('ESesubcl'.includes(char)) return 0.64;
  if (/[A-Z]/.test(char)) return font.weight === '900' ? 0.68 : 0.64;
  return 0.6;
}

function charWidthPx(char: string, fontSizePx: number, font: MemeTextFontSpec): number {
  const weightFactor = font.weight === '900' ? 1.04 : font.weight === '700' ? 1 : 0.96;
  return (charWidthEm(char, font) + font.letterSpacingEm) * fontSizePx * weightFactor;
}

function measureTextPx(text: string, fontSizePx: number, font: MemeTextFontSpec): number {
  if (text.length === 0) return 0;
  let widthPx = 0;
  for (let index = 0; index < text.length; index += 1) {
    widthPx += charWidthPx(text[index], fontSizePx, font);
  }
  widthPx -= font.letterSpacingEm * fontSizePx * (font.weight === '900' ? 1.04 : font.weight === '700' ? 1 : 0.96);
  return Math.round(widthPx);
}

function splitLongToken(token: string, maxWidthPx: number, fontSizePx: number, font: MemeTextFontSpec): string[] {
  const parts: string[] = [];
  let current = '';
  let currentWidthPx = 0;
  for (const char of token) {
    const nextWidthPx = currentWidthPx + charWidthPx(char, fontSizePx, font);
    if (current && Math.round(nextWidthPx) > maxWidthPx) {
      parts.push(current);
      current = char;
      currentWidthPx = charWidthPx(char, fontSizePx, font);
    } else {
      current += char;
      currentWidthPx = nextWidthPx;
    }
  }
  if (current) parts.push(current);
  return parts;
}

function wrapParagraph(paragraph: string, maxWidthPx: number, fontSizePx: number, font: MemeTextFontSpec): string[] {
  const tokens = paragraph.trim().split(/\s+/).filter(Boolean);
  if (tokens.length === 0) return [''];
  const lines: string[] = [];
  let current = '';
  for (const token of tokens) {
    const tokenParts = measureTextPx(token, fontSizePx, font) > maxWidthPx
      ? splitLongToken(token, maxWidthPx, fontSizePx, font)
      : [token];
    for (const part of tokenParts) {
      const candidate = current ? `${current} ${part}` : part;
      if (current && measureTextPx(candidate, fontSizePx, font) > maxWidthPx) {
        lines.push(current);
        current = part;
      } else {
        current = candidate;
      }
    }
  }
  if (current) lines.push(current);
  return lines;
}

function layoutLines(text: string, wrapWidthPx: number, fontSizePx: number, font: MemeTextFontSpec): MemeTextLayoutLine[] {
  const lineHeightPx = roundPx(fontSizePx * font.lineHeightRatio);
  const baselineOffsetPx = roundPx(fontSizePx * 0.92);
  const normalizedText = text.replace(/\r\n/g, '\n');
  const rawLines = normalizedText
    .split('\n')
    .flatMap((paragraph) => wrapParagraph(paragraph, wrapWidthPx, fontSizePx, font));
  let searchStart = 0;
  return rawLines.map((line, index) => {
    const found = line.length === 0 ? searchStart : normalizedText.indexOf(line, searchStart);
    const start = found < 0 ? searchStart : found;
    const end = start + line.length;
    searchStart = end + 1;
    return {
      text: line,
      start,
      end,
      widthPx: measureTextPx(line, fontSizePx, font),
      baselinePx: roundPx(index * lineHeightPx + baselineOffsetPx),
      topPx: roundPx(index * lineHeightPx),
    };
  });
}

export function buildMemeTextLayoutSpec(
  layer: TextLayer,
  keyframe: TransformKeyframe,
  canvas: { canvasWidthPx: number; canvasHeightPx: number }
): MemeTextLayoutSpec {
  const preset = layer.style.preset;
  const defaults = PRESET_DEFAULTS[preset];
  const width = normalizeMemeTextWrapWidth(layer.width);
  const fontSize = normalizeMemeTextFontSize(layer.fontSize);
  const canvasWidthPx = Math.max(1, Math.round(canvas.canvasWidthPx));
  const canvasHeightPx = Math.max(1, Math.round(canvas.canvasHeightPx));
  const fontSizePx = Math.max(1, Math.round(fontSize * canvasHeightPx));
  const wrapWidthPx = Math.max(1, Math.round(width * canvasWidthPx));
  const font = defaults.font;
  const lines: MemeTextLayoutLine[] = [];
  const lineHeightPx = roundPx(fontSizePx * font.lineHeightRatio);
  const layoutHeightPx = 0;
  const outlineWidthPx = Math.round(layer.style.outlineScale * fontSizePx);
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
      widthPx: canvasWidthPx,
      heightPx: canvasHeightPx,
      centerPx: {
        x: Math.round(clampNumber(keyframe.center.x, 0, 1, 0.5) * canvasWidthPx),
        y: Math.round(clampNumber(keyframe.center.y, 0, 1, 0.5) * canvasHeightPx),
      },
      wrapWidthPx,
      fontSizePx,
    },
    transform: {
      scale: round(clampNumber(keyframe.scale, 0.01, 16, 1)),
      rotationDegrees: round(clampNumber(keyframe.rotationDegrees, -36_000, 36_000, 0)),
      opacity: round(clampNumber(keyframe.opacity, 0, 1, 1)),
    },
    font: { ...font },
    align: layer.style.align,
    fill: { color: layer.style.color, opacity: layer.style.opacity },
    outline: { color: layer.style.outlineColor, widthPx: outlineWidthPx },
    backing: {
      color: layer.style.backgroundColor,
      radiusPx: Math.round(backing.radiusScale * fontSizePx),
      paddingXPx: Math.round(backing.paddingXScale * fontSizePx) || (layer.style.backgroundColor ? space.sm : 0),
      paddingYPx: Math.round(backing.paddingYScale * fontSizePx) || (layer.style.backgroundColor ? space.xs : 0),
      tail: layer.style.backgroundColor ? backing.tail : 'none',
    },
    layout: {
      lines,
      widthPx: wrapWidthPx,
      heightPx: layoutHeightPx,
      lineHeightPx,
    },
    diagnostics: {
      androidStaticLayoutTolerancePx: MEME_TEXT_LAYOUT_TOLERANCE_PX,
    },
  };
}

export function nativeMemeTextLayoutInputFromSpec(spec: MemeTextLayoutSpec): NativeMemeTextLayoutInput {
  return {
    text: spec.displayText,
    fontFamily: spec.font.family,
    fontWeight: Number(spec.font.weight),
    lineHeightPx: spec.layout.lineHeightPx,
    fontSizePx: spec.canvas.fontSizePx,
    letterSpacingEm: spec.font.letterSpacingEm,
    widthPx: spec.canvas.wrapWidthPx,
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
    const spec = buildMemeTextLayoutSpec(layer, defaultKeyframe(0, preset), { canvasWidthPx: 720, canvasHeightPx: 1_280 });
    return { preset, scale: scales[preset], input: nativeMemeTextLayoutInputFromSpec(spec) };
  });
}

export function compareNativeMemeTextLayoutResults(
  expected: NativeMemeTextLayoutResult,
  actual: NativeMemeTextLayoutResult,
  scale: number,
  tolerancePx: number
): NativeMemeTextLayoutComparison {
  const count = Math.max(expected.lines.length, actual.lines.length);
  let maxWidthDriftPx = Math.abs(actual.widthPx - expected.widthPx) * scale;
  let maxTopDriftPx = Math.abs(actual.heightPx - expected.heightPx) * scale;
  let maxBaselineDriftPx = 0;
  let contentMatches = actual.includeFontPadding === expected.includeFontPadding;
  for (let index = 0; index < count; index += 1) {
    const left = expected.lines[index];
    const right = actual.lines[index];
    if (!left || !right) {
      contentMatches = false;
      maxWidthDriftPx = Number.POSITIVE_INFINITY;
      maxTopDriftPx = Number.POSITIVE_INFINITY;
      maxBaselineDriftPx = Number.POSITIVE_INFINITY;
      break;
    }
    if (left.text !== right.text || left.start !== right.start || left.end !== right.end) contentMatches = false;
    maxWidthDriftPx = Math.max(maxWidthDriftPx, Math.abs(right.widthPx - left.widthPx) * scale);
    maxTopDriftPx = Math.max(maxTopDriftPx, Math.abs(right.topPx - left.topPx) * scale);
    maxBaselineDriftPx = Math.max(maxBaselineDriftPx, Math.abs(right.baselinePx - left.baselinePx) * scale);
  }
  const lineCountDrift = actual.lines.length - expected.lines.length;
  return {
    ok: contentMatches && lineCountDrift === 0 && maxWidthDriftPx <= tolerancePx && maxTopDriftPx <= tolerancePx && maxBaselineDriftPx <= tolerancePx,
    lineCountDrift,
    maxWidthDriftPx,
    maxTopDriftPx,
    maxBaselineDriftPx,
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
      maxWidthDriftPx: 0,
      maxTopDriftPx: 0,
      maxBaselineDriftPx: 0,
    };
  }
  const scale = spec.transform.scale;
  const limit = spec.diagnostics.androidStaticLayoutTolerancePx;
  const count = Math.max(spec.layout.lines.length, native.lines.length);
  let maxWidthDriftPx = Math.abs(native.widthPx - spec.layout.widthPx) * scale;
  let maxTopDriftPx = Math.abs(native.heightPx - spec.layout.heightPx) * scale;
  let maxBaselineDriftPx = 0;
  let contentMatches = native.includeFontPadding === false;
  for (let index = 0; index < count; index += 1) {
    const expected = spec.layout.lines[index];
    const actual = native.lines[index];
    if (!expected || !actual) {
      contentMatches = false;
      maxWidthDriftPx = Number.POSITIVE_INFINITY;
      maxTopDriftPx = Number.POSITIVE_INFINITY;
      maxBaselineDriftPx = Number.POSITIVE_INFINITY;
      break;
    }
    if (actual.text !== expected.text || actual.start !== expected.start || actual.end !== expected.end) {
      contentMatches = false;
    }
    maxWidthDriftPx = Math.max(maxWidthDriftPx, Math.abs(actual.widthPx - expected.widthPx) * scale);
    maxTopDriftPx = Math.max(maxTopDriftPx, Math.abs(actual.topPx - expected.topPx) * scale);
    maxBaselineDriftPx = Math.max(maxBaselineDriftPx, Math.abs(actual.baselinePx - expected.baselinePx) * scale);
  }
  const lineCountDrift = native.lines.length - spec.layout.lines.length;
  return {
    ok: contentMatches &&
      lineCountDrift === 0 &&
      maxWidthDriftPx <= limit &&
      maxTopDriftPx <= limit &&
      maxBaselineDriftPx <= limit,
    lineCountDrift,
    maxWidthDriftPx,
    maxTopDriftPx,
    maxBaselineDriftPx,
  };
}

export function memeTextBackingRadiusForPreview(spec: MemeTextLayoutSpec): number {
  if (spec.backing.color === null) return 0;
  return spec.backing.radiusPx || radius.sm;
}
