import { requireOptionalNativeModule } from 'expo-modules-core';

// Power / thermal snapshot used to throttle background description.
export interface NativePower {
  charging: boolean;
  level: number; // battery 0..1, or -1 if unknown
  // Thermal status. Android: 0 none … 4 severe (PowerManager.THERMAL_STATUS_*).
  // iOS: 0 nominal … 3 critical (ProcessInfo.thermalState). -1 if unknown.
  thermal: number;
  // Android predicted thermal headroom 0..1 (1 = imminent throttling), via
  // PowerManager.getThermalHeadroom. -1 when unavailable (iOS, or pre-Android 11).
  headroom: number;
}

// Result of decoding a video's audio track to Whisper-ready PCM.
export interface ExtractedAudio {
  path: string; // file:// path to raw little-endian float32 PCM (16 kHz mono)
  sampleRate: number; // always 16000
  samples: number;
  durationSec: number;
}

export interface MediaProbeResult {
  kind: 'image' | 'video';
  width: number;
  height: number;
  rotationDegrees: 0 | 90 | 180 | 270;
  // Apply flips in encoded-pixel space before the clockwise rotation. Together
  // these fields preserve every EXIF orientation, including mirrored variants.
  flipX: boolean;
  flipY: boolean;
  durationUs: number | null;
  frameRate: number | null;
  videoMime: string | null;
  audioMime: string | null;
  hasAudio: boolean;
  seekable: boolean;
  byteSize: number | null;
  modifiedTimeMs: number | null;
  stableId: string;
  displayName: string | null;
}
export interface NativeMemeTextLayoutLine {
  text: string;
  start: number;
  end: number;
  widthDip: number;
  topDip: number;
  baselineDip: number;
}

export interface NativeMemeTextLayoutResult {
  widthDip: number;
  heightDip: number;
  includeFontPadding: false;
  toleranceDip: number;
  lines: NativeMemeTextLayoutLine[];
}

export interface NativeNormalizedPoint {
  x: number;
  y: number;
}

export interface NativeNormalizedRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface NativeDetectedTextElement {
  text: string;
  box: NativeNormalizedRect | null;
  cornerPoints: NativeNormalizedPoint[];
  languages: string[];
}

export interface NativeDetectedTextLine extends NativeDetectedTextElement {
  elements: NativeDetectedTextElement[];
}

export interface NativeDetectedTextBlock extends NativeDetectedTextElement {
  lines: NativeDetectedTextLine[];
}

export interface NativeDetectedTextResult {
  sourceWidth: number;
  sourceHeight: number;
  rotation: 0 | 90 | 180 | 270;
  languages: string[];
  blocks: NativeDetectedTextBlock[];
}

export interface NativeBorderColorSample {
  hex: string;
  sampleCount: number;
}

export interface NativeImagePixelGrid {
  rows: number;
  columns: number;
  colors: string[];
}

// What a composition wants to pull an asset in for. Mirrors
// RetainedRangeComposition.AssetRole; the names cross the bridge verbatim.
export type CompositionAssetRole = 'TITLE_CARD' | 'REPLACEMENT_VIDEO' | 'REPLACEMENT_AUDIO';

export interface CompositionAssetRequirement {
  uri: string;
  role: CompositionAssetRole;
  // Stated, never sniffed: media3 guesses a still image's type from the file
  // extension, so a mismatch between the claim and the bytes must be a
  // rejection rather than a silent substitution.
  mimeType?: string | null;
}

export interface CompositionAssetRejection {
  uri: string;
  role: CompositionAssetRole;
  reason: string;
}

// One materialized still-image cutout: subject pixels with alpha, already
// cropped to the subject's own bounds and written to the cache as a PNG.
//
// The bitmap deliberately does NOT cross the bridge. A 16 MP alpha channel in
// the JS heap is how this app OOM'd before, so JS holds this reference plus
// normalized geometry and the renderer reads the file natively.
export interface NativeSubjectCutout {
  id: string;
  // null for the combined "all subjects" cutout.
  subjectIndex: number | null;
  cutoutUri: string;
  // Where the cutout sits in the EXIF-oriented source frame.
  bounds: NativeNormalizedRect;
  widthPx: number;
  heightPx: number;
  // Fraction of the frame the subject's alpha covers, measured natively.
  coverage: number;
  bytes: number;
}

export interface NativeSubjectCutoutResult {
  requestId: string;
  sourceWidth: number;
  sourceHeight: number;
  // Size segmentation actually ran at, after the native memory ceiling.
  workingWidth: number;
  workingHeight: number;
  sampleSize: number;
  estimatedPeakBytes: number;
  ceilingBytes: number;
  directory: string;
  // null when the image genuinely has no subject. That is a RESULT, not an
  // error — the promise resolves.
  combined: NativeSubjectCutout | null;
  subjects: NativeSubjectCutout[];
  droppedSubjects: number;
}

export interface SubjectSegmentationProgressEvent {
  requestId: string;
  phase: 'downloading' | 'segmenting';
  bytesDownloaded?: number;
  totalBytes?: number;
}

// One progress report from a running video export. `stage` is an
// `ExportStage` from src/memeExportCore.ts; `progress` is null whenever media3
// genuinely cannot report a fraction on this device, which is not the same as
// zero and must not be drawn as an empty bar.
export interface NativeVideoExportProgressEvent {
  exportId: string;
  stage: string;
  progress: number | null;
  detail: string;
}

export interface NativeVideoExportResult {
  // file:// path of the finished MP4 in the app cache. The caller owns deleting it.
  path: string;
  // Truths the render had to bend: a codec or frame size the device would not
  // give us. Empty means the file is exactly what was asked for.
  warnings: string[];
}

interface MemegetBgNative {
  getPower(): NativePower;
  startForeground(title: string, text: string, progress: number, total: number): void;
  stopForeground(): void;
  getModifiedTime(uri: string): number | null;
  probeMedia(source: string): Promise<MediaProbeResult>;
  extractAudio(source: string, maxSeconds: number): Promise<ExtractedAudio | null>;
  extractVideoFrame(source: string, seconds: number): Promise<string | null>;
  extractVideoFramePlayer(source: string, seconds: number): Promise<string | null>;
  renderMemeVariation(
    source: string,
    kind: 'image' | 'video',
    topText: string,
    bottomText: string,
    coverTop: boolean,
    coverBottom: boolean
  ): Promise<string>;
  transcodeVideoToMp4(source: string): Promise<string>;
  renderImageProject(planJson: string): Promise<string>;
  inspectCompositionAssets(requirementsJson: string): Promise<CompositionAssetRejection[]>;
  copyFileToClipboard(uri: string, name: string, mimeType: string): Promise<void>;
  saveToDownloads(srcPath: string, name: string, mimeType: string): Promise<string>;
  measureMemeTextLayout(
    text: string,
    fontFamily: string,
    fontWeight: number,
    fontSizeDip: number,
    lineHeightDip: number,
    letterSpacingEm: number,
    widthDip: number,
    align: string
  ): Promise<NativeMemeTextLayoutResult>;
  detectTextRegions(source: string): Promise<NativeDetectedTextResult>;
  sampleImageBorderColor(
    source: string,
    x: number,
    y: number,
    width: number,
    height: number
  ): Promise<NativeBorderColorSample>;
  sampleImagePixelGrid(
    source: string,
    x: number,
    y: number,
    width: number,
    height: number,
    pixelSize: number
  ): Promise<NativeImagePixelGrid>;
  subjectSegmentationModuleInstalled(): Promise<boolean>;
  segmentImageSubjects(source: string, requestId: string): Promise<NativeSubjectCutoutResult>;
  cancelSubjectSegmentation(requestId: string): void;
  releaseSubjectCutouts(requestId: string): Promise<boolean>;
  sweepSubjectCutouts(): Promise<number>;
  exportVideoProject(planJson: string, exportId: string): Promise<NativeVideoExportResult>;
  cancelVideoExport(exportId: string): boolean;
  // Expo native modules are EventEmitters; the cutout download and the video
  // export report through this rather than being polled.
  addListener(
    event: 'onSubjectSegmentationProgress',
    listener: (payload: SubjectSegmentationProgressEvent) => void
  ): { remove(): void };
  addListener(
    event: 'onVideoExportProgress',
    listener: (payload: NativeVideoExportProgressEvent) => void
  ): { remove(): void };
}

// Optional on purpose: in Expo Go, in the JS-only dev flow, or before a native
// `expo prebuild`, this resolves to null and every call below no-ops — the app
// runs fine, the background throttles just aren't available yet.
const native = requireOptionalNativeModule<MemegetBgNative>('MemegetBg');

// True once the native module has been built into the app.
export const bgNativeAvailable = native != null;

// Returns null when the native module isn't present (callers treat null as
// "no signal, don't throttle").
export function getPower(): NativePower | null {
  try {
    return native ? native.getPower() : null;
  } catch {
    return null;
  }
}

// Start/stop a foreground service (Android) that keeps the process alive so the
// in-app description loop survives backgrounding. No-op without the native
// module; on iOS it only requests a short background-execution extension.
export function startKeepAlive(title: string, text: string, progress = -1, total = -1): void {
  try {
    native?.startForeground(title, text, progress, total);
  } catch {
    // ignore — keep-alive is best-effort
  }
}

export function stopKeepAlive(): void {
  try {
    native?.stopForeground();
  } catch {
    // ignore
  }
}

// Last-modified time (ms since epoch) of a SAF content:// document, read
// straight off its DocumentFile in native code. Returns null when the native
// module isn't built in, the uri is unreadable, or the provider reports no time
// — callers fall back to the index time. This is the reliable source for the
// library's "most recently added first" order: expo-file-system doesn't surface
// modificationTime for SAF documents.
export function getFileModifiedTime(uri: string): number | null {
  try {
    const t = native?.getModifiedTime(uri);
    return typeof t === 'number' && t > 0 ? t : null;
  } catch {
    return null;
  }
}

// Probe local media in native code without copying or uploading it. A missing
// native module is the only null case; readable source/decoder failures reject.
export async function probeMedia(source: string): Promise<MediaProbeResult | null> {
  if (!native) return null;
  return native.probeMedia(source);
}

export const textDetectionNativeAvailable =
  native != null && typeof native.detectTextRegions === 'function';
export const borderColorSamplerNativeAvailable =
  native != null && typeof native.sampleImageBorderColor === 'function';

export async function detectTextRegions(source: string): Promise<NativeDetectedTextResult | null> {
  if (!native || typeof native.detectTextRegions !== 'function') return null;
  return native.detectTextRegions(source);
}

export async function sampleImageBorderColor(
  source: string,
  rect: NativeNormalizedRect
): Promise<NativeBorderColorSample | null> {
  if (!native || typeof native.sampleImageBorderColor !== 'function') return null;
  return native.sampleImageBorderColor(
    source,
    rect.x,
    rect.y,
    rect.width,
    rect.height
  );
}

export async function sampleImagePixelGrid(
  source: string,
  rect: NativeNormalizedRect,
  pixelSize: number
): Promise<NativeImagePixelGrid | null> {
  if (!native || typeof native.sampleImagePixelGrid !== 'function') return null;
  return native.sampleImagePixelGrid(
    source,
    rect.x,
    rect.y,
    rect.width,
    rect.height,
    pixelSize
  );
}

// True once the native audio decoder is built into the app — the audio
// transcription feature is unavailable without it (there is no JS decoder for
// AAC/Opus tracks), so the UI gates on this.
export const audioNativeAvailable = native != null && typeof native.extractAudio === 'function';

// Decode a video's first audio track (Android MediaExtractor + MediaCodec in
// native code) to mono 16 kHz float32 PCM on disk. Resolves null when the file
// has no audio track OR when the native module isn't built in — callers treat
// both as "nothing to transcribe" (gate features on audioNativeAvailable to
// tell them apart). Rejects on decode errors.
export async function extractAudio(
  source: string,
  maxSeconds: number
): Promise<ExtractedAudio | null> {
  if (!native || typeof native.extractAudio !== 'function') return null;
  const res = await native.extractAudio(source, maxSeconds);
  return res && typeof res.path === 'string' ? res : null;
}

// Decode one frame of a video via MediaCodec (native) and return a file://
// jpeg path in the cache dir — the caller owns deleting it. MediaCodec is the
// player's own decode path, so it reads streams MediaMetadataRetriever (used
// by expo-image AND expo-video-thumbnails) refuses — including mp4 bytes
// wearing a .gif name. Pass seconds < 0 for AUTO: duration-proportional
// positions are tried and near-black frames rejected, so posters don't land
// on fade-from-black intros. Resolves null when the native module isn't
// built in; REJECTS with a specific reason ("no decoder for video/av01",
// "decode timeout", …) on failure so the caller can record why — those
// messages are the only debugging signal off a device without a debugger.
export async function extractVideoFrame(source: string, seconds: number): Promise<string | null> {
  if (!native || typeof native.extractVideoFrame !== 'function') return null;
  const p = await native.extractVideoFrame(source, seconds);
  return typeof p === 'string' && p ? p : null;
}

// Last-resort poster path: the same frame grab, but through ExoPlayer's (media3)
// decode pipeline, whose own container parsers read some streams the platform
// MediaExtractor/MediaMetadataRetriever reject ("no video track", "Could not
// generate thumbnail"). If a clip plays in the viewer but no platform decoder
// can poster it, this can. Resolves null when the native module (or the media3
// frame extractor) isn't present; REJECTS with a reason on a real decode
// failure so the caller can record why.
export async function extractVideoFramePlayer(
  source: string,
  seconds: number
): Promise<string | null> {
  if (!native || typeof native.extractVideoFramePlayer !== 'function') return null;
  const p = await native.extractVideoFramePlayer(source, seconds);
  return typeof p === 'string' && p ? p : null;
}

export const mediaEditorNativeAvailable =
  native != null &&
  typeof native.renderMemeVariation === 'function' &&
  typeof native.transcodeVideoToMp4 === 'function';

// True once the native still renderer is built into the app. The studio gates
// its export button on this rather than silently producing nothing.
export const imageRendererNativeAvailable =
  native != null && typeof native.renderImageProject === 'function';

// Render a full-resolution PNG from a serialized image render plan (see
// src/memeImageRenderCore.ts) and return its file:// path in the app cache —
// the caller owns moving or deleting it. Resolves null ONLY when the native
// module is absent; a genuine decode/draw/encode failure rejects with the
// native reason so the studio can show it instead of a silent no-op.
export async function renderImageProject(planJson: string): Promise<string | null> {
  if (!native || typeof native.renderImageProject !== 'function') return null;
  return native.renderImageProject(planJson);
}

// True once the native composition asset guard is built in. Without it the
// studio has no way to know whether a title card is really decodable, so it
// gates on this rather than letting the exporter discover it.
export const compositionAssetGuardNativeAvailable =
  native != null && typeof native.inspectCompositionAssets === 'function';

// Header-decode every asset a video composition wants to reference and return
// the ones it cannot honour, each with a sentence to show the user. An empty
// array means every asset checked out; null means the native module is absent,
// which is NOT the same as "all good" — gate on
// `compositionAssetGuardNativeAvailable` to tell them apart. A malformed
// requirement list rejects.
export async function inspectCompositionAssets(
  requirements: readonly CompositionAssetRequirement[]
): Promise<CompositionAssetRejection[] | null> {
  if (!native || typeof native.inspectCompositionAssets !== 'function') return null;
  if (requirements.length === 0) return [];
  return native.inspectCompositionAssets(JSON.stringify(requirements));
}

export async function measureMemeTextLayout(input: {
  text: string;
  fontFamily: string;
  fontWeight: number;
  fontSizeDip: number;
  lineHeightDip: number;
  letterSpacingEm: number;
  widthDip: number;
  align: string;
}): Promise<NativeMemeTextLayoutResult | null> {
  if (!native || typeof native.measureMemeTextLayout !== 'function') return null;
  return native.measureMemeTextLayout(
    input.text,
    input.fontFamily,
    input.fontWeight,
    input.fontSizeDip,
    input.lineHeightDip,
    input.letterSpacingEm,
    input.widthDip,
    input.align
  );
}

export async function renderMemeVariation(
  source: string,
  kind: 'image' | 'video',
  topText: string,
  bottomText: string,
  coverTop = false,
  coverBottom = false
): Promise<string | null> {
  if (!native || typeof native.renderMemeVariation !== 'function') return null;
  return native.renderMemeVariation(source, kind, topText, bottomText, coverTop, coverBottom);
}

export async function transcodeVideoToMp4(source: string): Promise<string | null> {
  if (!native || typeof native.transcodeVideoToMp4 !== 'function') return null;
  return native.transcodeVideoToMp4(source);
}

// Put an actual file (in practice a video, which expo-clipboard can't hold) on
// the system clipboard. Android stages the file behind a FileProvider and sets
// a content:// clip; whether a given paste target accepts a video is up to
// that app. Returns false when the native module isn't built in so the caller
// can fall back (e.g. to the still-frame copy); a genuine copy failure rejects.
export async function copyFileToClipboard(
  uri: string,
  name: string,
  mimeType: string
): Promise<boolean> {
  if (!native || typeof native.copyFileToClipboard !== 'function') return false;
  await native.copyFileToClipboard(uri, name, mimeType);
  return true;
}

// Copy a file (a finished export in the app cache) into the public Downloads
// folder via native MediaStore, returning the destination label (e.g.
// "Download/foo.zip"). The copy streams in native code, so a large export never
// passes through JS memory. Resolves null when the native module isn't built
// in, so callers can fall back to the share sheet.
export async function saveToDownloads(
  srcPath: string,
  name: string,
  mimeType: string
): Promise<string | null> {
  if (!native || typeof native.saveToDownloads !== 'function') return null;
  return native.saveToDownloads(srcPath, name, mimeType);
}

// Whether the two non-library export destinations can actually do anything.
//
// Both functions above degrade quietly — false / null — so a caller that does
// not check gets a button that appears to work and does nothing. The editor's
// destination picker gates on these instead of offering a dead option. They
// mirror the exact guards those functions use, deliberately: one place to be
// wrong is better than two that can disagree.
export const fileClipboardAvailable =
  native != null && typeof native.copyFileToClipboard === 'function';

export const downloadsAvailable = native != null && typeof native.saveToDownloads === 'function';

// Whether still-image subject cutouts exist in this build at all.
//
// Same contract as the two flags above, for the same reason: `segmentImageSubjects`
// resolves null without the native module, and a Cutout button that silently
// does nothing is worse than one that is absent. Note what this does NOT say —
// whether the ML Kit model is on the device. That is a separate, changeable
// fact; ask `subjectSegmentationModuleInstalled()`.
export const subjectSegmentationAvailable =
  native != null && typeof native.segmentImageSubjects === 'function';

// Whether Play services already holds the segmentation model. False means the
// first cutout will download it (~a few MB), which is a user-visible wait the
// studio announces instead of hiding behind a spinner. Resolves false when the
// native module is missing, and also when the probe itself cannot run — both
// mean "assume a download", which is the safe thing to tell the user.
export async function subjectSegmentationModuleInstalled(): Promise<boolean> {
  if (!native || typeof native.subjectSegmentationModuleInstalled !== 'function') return false;
  return native.subjectSegmentationModuleInstalled();
}

// Segment the subjects of a local still image, materializing one cutout PNG per
// subject plus a combined one under a per-request cache directory the caller
// releases with `releaseSubjectCutouts`.
//
// Resolves null ONLY when the native module is absent. A real failure REJECTS
// with one of the E_CUTOUT_* codes so the caller can offer the right remedy
// (see classifyCutoutFailure in src/memeCutoutCore.ts) — and an image with no
// subject RESOLVES with `combined: null`, because "nothing to cut out" is an
// answer, not an error.
export async function segmentImageSubjects(
  source: string,
  requestId: string
): Promise<NativeSubjectCutoutResult | null> {
  if (!native || typeof native.segmentImageSubjects !== 'function') return null;
  return native.segmentImageSubjects(source, requestId);
}

// Ask an in-flight segmentation to stop. Fire-and-forget: the run rejects with
// E_CUTOUT_CANCELLED at its next checkpoint, which is what the caller waits on.
export function cancelSubjectSegmentation(requestId: string): void {
  if (!native || typeof native.cancelSubjectSegmentation !== 'function') return;
  native.cancelSubjectSegmentation(requestId);
}

// Delete one request's cutout files. Returns false when there was nothing to
// delete (or no native module), true when a directory went away.
export async function releaseSubjectCutouts(requestId: string): Promise<boolean> {
  if (!native || typeof native.releaseSubjectCutouts !== 'function') return false;
  return native.releaseSubjectCutouts(requestId);
}

// Drop cutout directories old enough that no session can still be using them,
// returning how many went away. Cheap enough to call when the studio opens.
export async function sweepSubjectCutouts(): Promise<number> {
  if (!native || typeof native.sweepSubjectCutouts !== 'function') return 0;
  return native.sweepSubjectCutouts();
}

// Subscribe to model-download / segmentation progress. Returns a no-op
// unsubscribe when events are unavailable, so callers never branch on it.
export function addSubjectSegmentationProgressListener(
  listener: (payload: SubjectSegmentationProgressEvent) => void
): { remove(): void } {
  if (!native || typeof native.addListener !== 'function') return { remove() {} };
  return native.addListener('onSubjectSegmentationProgress', listener);
}

// True once the native video exporter is built in. Without it the studio has
// no way to render a video project, so it gates its export button on this
// rather than offering one that silently does nothing.
export const videoExporterNativeAvailable =
  native != null && typeof native.exportVideoProject === 'function';

// The rejection code the native exporter uses for a cancel. Cancelling is a
// normal outcome, so the caller has to be able to tell it from a failure after
// the reason has been flattened into an Error.
export const VIDEO_EXPORT_CANCELLED_CODE = 'E_VIDEO_EXPORT_CANCELLED';

export function isVideoExportCancellation(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) return false;
  if ('code' in error && error.code === VIDEO_EXPORT_CANCELLED_CODE) return true;
  // Expo flattens the code into the message on some paths, and a cancel that
  // reads as a failure shows the user a red error for something they asked for.
  return 'message' in error && String(error.message).includes(VIDEO_EXPORT_CANCELLED_CODE);
}

// Render a video composition plan (src/memeVideoCompositionCore.ts) to an MP4
// in the app cache. Resolves ONLY when the native side has verified a complete
// file; rejects once on failure or cancellation. Null means the native module
// isn't built in — gate on `videoExporterNativeAvailable` to tell that apart
// from a render that failed.
//
// `exportId` is the handle `cancelVideoExport` needs, and the progress
// subscription is scoped to this call: it is removed on every settle, so a
// finished export can never move a later run's progress bar.
export async function exportVideoProject(
  planJson: string,
  exportId: string,
  onProgress?: (event: NativeVideoExportProgressEvent) => void
): Promise<NativeVideoExportResult | null> {
  if (!native || typeof native.exportVideoProject !== 'function') return null;
  const subscription =
    onProgress && typeof native.addListener === 'function'
      ? native.addListener('onVideoExportProgress', (event) => {
          if (event?.exportId === exportId) onProgress(event);
        })
      : null;
  try {
    return await native.exportVideoProject(planJson, exportId);
  } finally {
    subscription?.remove();
  }
}

// Ask a running export to stop. False means there was nothing to cancel; the
// export's own promise is what reports that it finished unwinding.
export function cancelVideoExport(exportId: string): boolean {
  if (!native || typeof native.cancelVideoExport !== 'function') return false;
  try {
    return native.cancelVideoExport(exportId) === true;
  } catch {
    return false;
  }
}
