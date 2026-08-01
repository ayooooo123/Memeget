# On-Device Image + Video Remix Studio Implementation Plan

> **For agentic workers:** REQUIRED: execute with `@subagent-driven-development` when available, otherwise `@executing-plans`. Use `@test-driven-development`, `@react-native-best-practices`, `@verification-before-completion`, and `@systematic-debugging`. Every permanent behavior starts with a failing contract test or a reproducible device scenario.

**Goal:** Make Memeget’s per-meme “Create a variation” action a shockingly capable, fast, private remix studio for both images and videos: direct text and graphic manipulation, text replacement, crop/trim, timed animation, subject isolation, background replacement, audio controls, and reliable export into the collection—without changing or uploading the original.

**Product standard:** This must feel like a purpose-built meme remixer, not a miniature generic video editor. The common jobs—replace caption, pin a label to a person, isolate a reaction, trim a clip, mute it, add timed text, export/copy—must take seconds. Advanced capabilities remain discoverable behind focused tools rather than crowding the first screen.

**Architecture:** React Native owns one versioned, non-destructive edit project, direct manipulation, undo/redo, and preview overlays. Android native code owns source probing, full-resolution image rendering, frame extraction, segmentation/tracking prepasses, Media3 composition/effects, audio preservation, and cancellable exports. Normalized coordinates and microsecond timestamps are the shared contract. Finished output flows through the existing `saveSharedFiles` → pending row → `indexSavedFiles` path and becomes a new meme.

**Current baseline:** Commit `c0ee240` provides direct Downloads export, PNG image caption variations, Media3 WebM→H.264/AAC MP4 conversion, static video captions, collection persistence, pending rows, indexing, and temp cleanup. Extend these paths. Remove superseded fixed-caption image/video branches after every caller migrates; do not leave parallel editors.

**Tech stack:** React Native 0.85, Expo 56, TypeScript 5.9, `PanResponder`, Expo Video, Android Bitmap/Canvas, MediaCodec/MediaExtractor, Media3 1.9.0 pinned to Expo Video, Media3 Transformer/effect, existing ML Kit OCR 19.0.1, ML Kit Subject Segmentation 16.0.0-beta1 for still images, MediaPipe Tasks Vision with a pinned bundled segmentation model for video, Jest 30.

**Primary references:**

- Media3 Transformer: https://developer.android.com/media/media3/transformer
- Media3 multi-asset composition: https://developer.android.com/media/media3/transformer/multi-asset
- ML Kit still-image subject segmentation: https://developers.google.com/ml-kit/vision/subject-segmentation/android
- MediaPipe image/video segmentation: https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter/android

**Execution prerequisite:** create an isolated feature branch/worktree from current `main`. Preserve the unrelated local voice-diarization spec edit. Do not change Media3 versions independently of Expo Video.

---

## Experience definition

### One editor, two media modes

Opening `Create a variation…` enters a full-screen studio with:

- media preview at the top;
- Undo/Redo and Before/After in the header;
- a context-sensitive tool rail;
- a selected-layer inspector;
- `Save new`, `Copy result`, and `Export` destinations;
- inline render/model errors that remain visible above Android Modal windows.

**Image tools:** Text, Replace text, Crop, Subject, Background, Layers.

**Video tools:** Trim, Text, Motion, Subject, Background, Audio, Crop, Speed, Layers.

The first tap defaults to the most likely useful action:

- image with OCR text → `Replace text` suggestions;
- image without OCR → `Text`;
- video → timeline with trim handles and `Text`.

### Included image capabilities

1. Multiple movable text layers with content, font preset, size, width, alignment, fill, outline, backing box, opacity, rotation, and layer order.
2. Meme-native presets: Impact, subtitle, label, breaking-news bar, chat bubble, and plain system text.
3. Crop presets and free crop; rotate 90°; horizontal/vertical flip.
4. OCR boxes that can be selected, covered, pixelated, or replaced with a prefilled editable text layer.
5. Manual rectangular cover regions for OCR misses.
6. Combined or individual subject cutouts.
7. Transparent, solid-color, blurred-original, or replacement-image backgrounds.
8. Cutout styling: outline, shadow, duplicate/offset “sticker” effect.
9. 30-step undo/redo and press-and-hold Before/After.
10. Full-resolution PNG output.

### Included video capabilities

1. Frame-accurate-enough trim handles with thumbnail strip and current-time readout.
2. Crop/rotate/flip plus output aspect presets.
3. Original audio preserved by default; explicit mute and 0–200% volume.
4. Speed presets from 0.5× to 2× with audio pitch handled by Media3; no silent speed drift.
5. Multiple timed text/label layers with start/end handles.
6. Keyframes for position, scale, rotation, opacity, and background color; linear and hold interpolation.
7. “Pin to subject” motion mode: manual start/end keyframes always available; automatic tracking is gated by an on-device probe and falls back honestly to manual keyframes.
8. Video person/foreground isolation with a temporally smoothed mask track.
9. Transparent-look composites over solid color, blurred source, replacement image, or replacement video. MP4 itself remains opaque; alpha is composited before export.
10. Timeline split points and removal of unwanted middle ranges, implemented as a Media3 composition of retained segments.
11. Static image/title-card insertion with a bounded duration.
12. Export progress, estimated stage, cancellation, process-safe cleanup, and codec fallback reporting.
13. H.264/AAC MP4 output. Silent GIF export is a separate encoder gate and must never masquerade as delivered until real GIF bytes pass device tests.

### Smart remix moments

The milestone should create at least these “how did it do that on my phone?” flows:

- Tap existing caption text → type replacement → save.
- Tap a person → remove the background → add a colored outline → place them over another image.
- Trim a video, mute it, and add a timed caption in under ten seconds.
- Pin a label to a moving person with two keyframes; auto-track if the runtime gate passes.
- Isolate a person throughout a short reaction clip and place them over a blurred original background.
- Turn a WebM into an edited, paste-compatible MP4 without exposing codec details.

### Explicit non-goals for the first complete milestone

- Cloud editing or uploads.
- Generative image/video synthesis.
- Claiming sampled fill/pixelation is “AI erase” or inpainting.
- Arbitrary freehand painting, clone stamp, LUT marketplace, chroma-key studio, or professional audio mixing.
- Unbounded multi-track projects. One source meme plus bounded overlays/background assets is the contract.
- Reverse/boomerang until a real decode/encode implementation passes device performance gates.

---

## Shared project model

Create `src/memeEditProjectCore.ts`. It is React-free and serializable except transient native cache URIs.

```ts
export type MediaEditKind = 'image' | 'video';
export type NormalizedPoint = { x: number; y: number };
export type NormalizedRect = { x: number; y: number; width: number; height: number };
export type TimeRangeUs = { startUs: number; endUs: number };

export interface BaseTransform {
  rotation: 0 | 90 | 180 | 270;
  flipX: boolean;
  flipY: boolean;
  crop: NormalizedRect;
  outputAspect: 'source' | '1:1' | '4:5' | '9:16' | '16:9';
}

export interface TextStyle {
  preset: 'impact' | 'subtitle' | 'label' | 'news' | 'bubble' | 'plain';
  color: string;
  outlineColor: string;
  outlineScale: number;
  backgroundColor: string | null;
  opacity: number;
  align: 'left' | 'center' | 'right';
  uppercase: boolean;
}

export interface TransformKeyframe {
  timeUs: number;
  center: NormalizedPoint;
  scale: number;
  rotationDegrees: number;
  opacity: number;
  easing: 'linear' | 'hold';
}

export interface TextLayer {
  id: string;
  kind: 'text';
  text: string;
  width: number;
  style: TextStyle;
  active: TimeRangeUs | null; // null for images
  keyframes: TransformKeyframe[]; // images use one timeUs=0 keyframe
}

export interface CoverLayer {
  id: string;
  kind: 'cover';
  rect: NormalizedRect;
  mode: 'solid' | 'pixelate';
  color: string;
  pixelSize: number;
}

export interface SubjectLayer {
  id: string;
  kind: 'subject';
  subjectIndex: number | null;
  maskTrackId: string;
  active: TimeRangeUs | null;
  keyframes: TransformKeyframe[];
  outlineColor: string | null;
  outlineScale: number;
  shadowScale: number;
}

export interface BackgroundSpec {
  mode: 'source' | 'solid' | 'blurred-source' | 'image' | 'video';
  color: string;
  assetUri: string | null;
  blurScale: number;
}

export interface VideoEditSpec {
  retainedRanges: TimeRangeUs[];
  speed: number;
  audio: { muted: boolean; volume: number };
  insertedCards: Array<{ uri: string; atUs: number; durationUs: number }>;
}

export interface MemeEditProject {
  version: 1;
  source: { uri: string; name: string; kind: MediaEditKind; width: number; height: number; durationUs: number | null };
  base: BaseTransform;
  video: VideoEditSpec | null;
  layers: Array<TextLayer | CoverLayer | SubjectLayer>;
  background: BackgroundSpec;
  transient: { maskTracks: Record<string, string>; materializedSourceUri: string | null };
}
```

### Invariants

- All coordinates are finite and clamped to `[0,1]` against the post-rotation/post-crop working canvas.
- All video times are integer microseconds. Retained ranges are sorted, non-overlapping, and inside source duration.
- Keyframes are sorted and unique per timestamp; interpolation is deterministic.
- Layer array order is render order.
- Native cache URIs never enter SQLite sidecars or collection metadata.
- Reducers never call `Date.now()` or generate random IDs; callers supply IDs.
- One pointer gesture creates one undo state, not hundreds.
- Export reads an immutable project snapshot. Editing while exporting is disabled or explicitly cancels/restarts.

---

## File structure

### New TypeScript files

- `src/memeEditProjectCore.ts` — types, validation, reducer, geometry/time mapping, keyframe interpolation, undo/redo.
- `src/memeEditProjectCore.test.ts` — deterministic project contracts.
- `src/memeEditDraftStore.ts` — crash-safe cache JSON, TTL cleanup, source identity checks.
- `src/memeEditDraftStore.test.ts` — atomic save/restore/expiry contracts with injected IO.
- `src/components/MemeRemixStudio.tsx` — image/video dispatcher and shared shell.
- `src/components/MemeEditCanvas.tsx` — contained-media coordinate mapping and direct manipulation.
- `src/components/MemeEditToolRail.tsx` — mode-specific tool navigation.
- `src/components/MemeTimeline.tsx` — thumbnails, playhead, trim/split ranges, timed layer bars.
- `src/components/MemeLayerList.tsx` — selection, visibility, order, duplicate/delete.
- `src/components/MemeTextInspector.tsx` — text presets and style controls.
- `src/components/MemeTransformInspector.tsx` — crop/rotate/flip/aspect controls.
- `src/components/MemeTextReplaceTool.tsx` — OCR region selection and replacement.
- `src/components/MemeSubjectTool.tsx` — still/video mask state and background controls.
- `src/components/MemeVideoAudioTool.tsx` — mute/volume controls.
- `src/components/MemeVideoMotionTool.tsx` — timed layers and keyframes.
- `src/components/MemeExportSheet.tsx` — Save/Copy/Downloads, progress, cancel, codec report.

### New Android files

- `MemeMediaProbe.kt` — width/height/duration/rotation/audio/video MIME, frame rate, seek capability.
- `MemeImageRenderer.kt` — structured full-resolution image renderer.
- `MemeTextDetector.kt` — ML Kit OCR line/element text with normalized boxes.
- `MemeStillSubjectSegmenter.kt` — ML Kit combined and individual subject masks.
- `MemeVideoMaskTrack.kt` — sampled MediaPipe video segmentation, temporal smoothing, bounded cache format.
- `MemeMotionTracker.kt` — gated automatic subject/object tracking; manual-keyframe fallback remains product-complete.
- `MemeVideoCompositionBuilder.kt` — retained ranges, title cards, speed, audio, crop, and background asset sequences.
- `MemeDynamicOverlay.kt` — timestamp-aware text/subject transform interpolation.
- `MemeVideoEffects.kt` — mask/background/blur/pixelation compositing.
- `MemeVideoExport.kt` — Transformer lifecycle, progress, cancel, fallback, cleanup, structured result.

### Existing files to modify

- `src/components/MemeVariationEditor.tsx` — replaced by `MemeRemixStudio`; remove after migration.
- `src/components/MemeGrid.tsx` — open studio and persist finished output only.
- `src/screens/LibraryScreen.tsx` — immediate indexing callback remains the sole finished-output path.
- `src/memeActionsCore.ts` — naming/output-profile helpers only.
- `modules/memeget-bg/index.ts` — typed native probe/render/segment/track/export API.
- `MemegetBgModule.kt` — promise-safe bridge, progress events, cancellation.
- `MemeMediaEditor.kt` — split into focused image/video units, then remove.
- `modules/memeget-bg/android/build.gradle` — pinned ML Kit/MediaPipe additions; Media3 remains 1.9.0.
- `README.md` and Settings privacy copy — only after verified delivery.

---

## Chunk 0: Runtime and performance gates

### Task 0.1: Prove pinned Media3 1.9 composition/export contracts

- [ ] Build a native probe using the exact Media3 1.9.0 artifacts already resolved with Expo Video.
- [ ] Verify on the Pixel 9 Pro: trim, two retained ranges, H.264 output, AAC preservation, volume, speed, static overlay, timestamp-aware overlay, crop, and cancellation.
- [ ] Record original/output duration, audio presence, A/V end-time delta, bytes, median export rate, and peak memory for 5 s/15 s fixtures at 720p and 1080p.
- [ ] Fail the gate if audio disappears without mute, A/V drift exceeds 50 ms, cancellation leaves codecs/files alive, or 1080p export crashes.
- [ ] Confirm which multi-asset APIs exist in 1.9.0. If official examples require 1.10+, implement only APIs present in 1.9; do not upgrade Media3 independently.

**Gate output:** `docs/editing/media3-1.9-device-gate.json` created only from observed device results.

### Task 0.2: Prove video segmentation and optional tracking

- [ ] Pin a bundled MediaPipe segmentation model and its license; no `latest.release` dependencies.
- [ ] Run segmentation in `VIDEO` mode against three 10-second fixtures: one person, two people crossing, and fast motion.
- [ ] Measure 256/384/512 working sizes and 8/12/15 mask FPS. Choose the smallest configuration whose temporally smoothed masks have no obvious edge pumping at normal playback.
- [ ] Require the selected 10-second 720p pipeline to finish within 3× realtime on the Pixel 9 Pro and stay under a measured memory ceiling. Otherwise ship still-image isolation first and mark video isolation gated—not stubbed.
- [ ] Probe automatic tracking separately. If it loses the selected subject across occlusion/cuts, omit Auto-track from the milestone; manual keyframes remain complete.

**Gate output:** `docs/editing/video-segmentation-device-gate.json` with model digest, settings, runtime, memory, and accepted/rejected capabilities.

---

## Chunk 1: Shared non-destructive project foundation

### Task 1.1: Project reducer, time mapping, and history

- [ ] Write failing Jest tests for default image/video projects, clamping, crop transforms, retained-range mapping, speed-adjusted output times, layer ordering, active ranges, keyframe interpolation, validation, and 30-step undo/redo.
- [ ] Verify RED: `npm test -- memeEditProjectCore --runInBand`.
- [ ] Implement pure types/reducer with caller-supplied IDs.
- [ ] Coalesce gestures and trim drags into one undo transaction.
- [ ] Reject malformed versions, NaN/Infinity, zero-area crops, overlapping retained ranges, invalid keyframes, and assets outside the bounded project contract.
- [ ] Verify GREEN and `npm run typecheck`.

### Task 1.2: Crash-safe drafts and source probing

- [ ] Add injected-IO tests for atomic draft write/replace, restore, source mismatch, seven-day expiry, and transient URI stripping.
- [ ] Add `MemeMediaProbe.kt`; materialize SAF once per session and return real dimensions, orientation, duration, frame rate when known, video/audio MIME, and audio presence.
- [ ] Autosave after a 500 ms debounce and on background. Restore only after user confirmation if the source still matches.
- [ ] Draft cancellation removes JSON and all session-owned `meme_work_*` assets.

---

## Chunk 2: Direct-manipulation studio shell

### Task 2.1: Shared shell, preview, and layers

- [ ] Replace the split fixed-caption modal with `MemeRemixStudio` for both images and videos.
- [ ] Add measured contained-media bounds; map touches only inside the actual image/video rectangle, never the letterbox.
- [ ] Add tool rail, selected-layer inspector, layer list, Undo/Redo, Before/After, cancel, and export entry.
- [ ] Use direct drag for selected layers and explicit handles/controls for resize/rotation. Preserve 60 FPS by keeping transient gesture values out of the full project reducer until release.
- [ ] Make every icon accessible and every selected/disabled state visible without color alone.

### Task 2.2: High-quality text and layout presets

- [ ] Add multiple text layers and the six meme-native presets.
- [ ] Support content, wrap width, position, scale, rotation, opacity, fill, outline, backing box, alignment, uppercase, duplicate, delete, and reorder.
- [ ] Implement preview/native font metric parity fixtures. Output placement, line breaks, and baseline must match preview within two preview pixels.
- [ ] Keep long text responsive by using uncontrolled input while typing and committing on blur/debounce.

---

## Chunk 3: Image editing intelligence

### Task 3.1: Crop, replace text, and honest removal

- [ ] Add crop presets/free crop, rotate, and flip with deterministic layer remapping.
- [ ] Expose ML Kit OCR line/element boxes from the native module, reusing the resolved 19.0.1 stack.
- [ ] Tap a region to `Cover`, `Pixelate`, or `Replace`; manual rectangle covers OCR misses.
- [ ] Sample a median border color for solid cover, user-overridable.
- [ ] `Replace` creates a cover plus a prefilled text layer. Never label this inpainting.

### Task 3.2: Still-image cutouts and backgrounds

- [ ] Pin ML Kit Subject Segmentation 16.0.0-beta1 and explicitly request its Play Services module for sideloaded installs.
- [ ] Show a cancellable one-time model download state; distinguish offline, unavailable, and real segmentation failure.
- [ ] Return combined and individual masks. Let the user choose all/one subject.
- [ ] Add transparent, solid, blurred-source, and replacement-image backgrounds.
- [ ] Add outline, shadow, and duplicate-offset sticker effects.
- [ ] Verify first download, airplane-mode reuse, alpha edges, multiple subjects, and temp cleanup on physical device.

### Task 3.3: Full-resolution image renderer

- [ ] Parse the immutable project snapshot natively and apply base transform → background/mask → layers in order.
- [ ] Respect EXIF. Keep source resolution unless memory safety requires a visible, user-approved output-size choice.
- [ ] Render PNG transparency correctly; add fixture checks for PNG signature and alpha.
- [ ] Save through existing collection/index path and leave original checksum unchanged.

---

## Chunk 4: Video timeline, trim, speed, and audio

### Task 4.1: Timeline and playback synchronization

- [ ] Add thumbnail extraction/cache at bounded intervals, playhead, current/duration labels, trim handles, split points, retained-range bars, and layer bars.
- [ ] Scrubbing seeks Expo Video; throttle seeks so a drag cannot flood ExoPlayer.
- [ ] Map source↔output times through removed ranges and speed in pure tested helpers.
- [ ] Selecting a layer seeks to its start if the playhead is outside its active range.
- [ ] Changing trim clamps or removes invalid timed layers with an explicit undoable reducer action.

### Task 4.2: Audio and speed controls

- [ ] Preserve audio by default. Add explicit mute and 0–200% volume.
- [ ] Add 0.5×, 0.75×, 1×, 1.25×, 1.5×, and 2× speed.
- [ ] Preview volume/mute immediately. Preview speed only where Expo Video can do so without drift; otherwise label as export-only.
- [ ] Native export fails rather than silently dropping audio unless muted was selected.
- [ ] Device-test spoken fixtures for pitch, sync, trim boundaries, and output duration.

### Task 4.3: Multi-range video composition and title cards

- [ ] Build retained clips as Media3 sequences and concatenate them without gaps.
- [ ] Support a bounded number of split/removal operations and static title cards.
- [ ] Preserve source orientation/aspect transforms across every segment.
- [ ] Reject incompatible replacement video/audio assets with a readable reason; no silent format substitution.

---

## Chunk 5: Timed overlays and motion

### Task 5.1: Timed text layers and keyframes

- [ ] Give every video text/subject layer start/end handles.
- [ ] Add keyframes for center, scale, rotation, opacity, with linear/hold interpolation.
- [ ] Native `MemeDynamicOverlay` evaluates the same pure interpolation contract at each presentation timestamp.
- [ ] Add `Set keyframe here`, next/previous keyframe, delete keyframe, and copy-forward controls.
- [ ] Press-and-hold Before/After hides overlays without resetting playback.

### Task 5.2: Pin labels to motion

- [ ] Manual mode: user sets at least two keyframes; interpolation visibly follows motion.
- [ ] Auto mode appears only if Task 0.2 tracking gate passed. User selects a subject at the playhead; native prepass emits normalized track keyframes with confidence.
- [ ] Low-confidence spans are visible and editable; never jump to a different subject silently.
- [ ] Cuts terminate tracks. User explicitly restarts tracking after a cut.

---

## Chunk 6: Video subject isolation and compositing

### Task 6.1: Build bounded mask tracks

- [ ] Decode segmentation frames at the gated resolution/FPS with MediaCodec/MediaPipe VIDEO mode.
- [ ] Temporally smooth masks without bleeding across scene cuts.
- [ ] Store a bounded compressed mask track under a session ID; include model digest, source hash, dimensions, FPS, and timestamps.
- [ ] Report prepass progress and support cancellation. Delete partial tracks on cancel/failure.
- [ ] Invalidate mask tracks when source ranges, rotation, or crop changes.

### Task 6.2: Composite video foreground/background

- [ ] Create a timestamp-aware Media3 effect that maps export timestamps to mask samples and interpolates/holds according to the gate.
- [ ] Support solid, blurred-source, replacement-image, and replacement-video backgrounds.
- [ ] Add outline/shadow around the foreground with bounded kernel sizes.
- [ ] Keep original audio and duration unchanged unless user edits them.
- [ ] Compare preview and export at start/middle/end plus fast-motion boundaries.

---

## Chunk 7: Production export pipeline

### Task 7.1: Progress, cancellation, and codec fallbacks

Expose structured events:

```ts
export type ExportStage = 'preparing' | 'segmenting' | 'encoding' | 'saving' | 'indexing';
export interface MemeExportProgress { stage: ExportStage; progress: number | null; detail: string }
export interface MemeExportResult { path: string; name: string; mimeType: 'image/png' | 'video/mp4'; warnings: string[] }
```

- [ ] Bridge Transformer callbacks with Expo Promise/Event APIs; resolve only on completion, reject once on error/cancel.
- [ ] Poll Media3 progress on the main looper while active; stop polling on every terminal path.
- [ ] Cancel releases Transformer/codecs, segmentation workers, temp assets, foreground notifications, and keep-alive lease.
- [ ] Default video output H.264/AAC MP4. Use Media3 fallback reporting; surface changed resolution/bitrate/codec to the user.
- [ ] Never silently remove audio, truncate layers, or return a partial file.

### Task 7.2: Save, Copy, and Downloads destinations

- [ ] `Save new` writes through `saveSharedFiles`, emits pending row, then calls `onCreated` for immediate indexing.
- [ ] `Copy result` exports first, then uses the existing file clipboard. WebM-derived results remain real MP4 bytes.
- [ ] `Export` writes through `saveToDownloads` with collision-safe names.
- [ ] Destination choice does not rerender twice; cache one completed export snapshot until the project changes.
- [ ] Keep failed result in editor with inline error and retry.

### Task 7.3: Optional GIF output gate

- [ ] Research and prototype a maintained Android animated GIF encoder with explicit license, 16 KB alignment, memory, duration, frame-rate, and size measurements.
- [ ] Require real `GIF89a` bytes, animation across frames, X/mobile upload test, and bounded file size.
- [ ] If no implementation passes, omit GIF. MP4 remains complete; no MIME/extension trick.

---

## Chunk 8: Integration, polish, and delivery

### Task 8.1: Collection, search, and cleanup invariants

- [ ] Successful render creates exactly one new meme. Cancel/failure creates none.
- [ ] Original file, DB row, tags, sidecar, and checksum remain unchanged.
- [ ] Finished output is indexed/searchable through existing paths.
- [ ] Session draft, materialized source, thumbnails, masks, and partial exports are owned and cleaned deterministically.
- [ ] App restart recovers valid draft or sweeps expired assets without touching clipboard/export files.

### Task 8.2: Device UX matrix

Exercise on Pixel 9 Pro with real SAF `content://` images, MP4, and WebM:

- [ ] portrait/landscape/rotated image;
- [ ] large PNG and HEIC/WebP source;
- [ ] silent and audio video;
- [ ] WebM→edited MP4;
- [ ] short/long clip and 1080p clip;
- [ ] trim/split/speed/mute/volume;
- [ ] timed text and keyframes;
- [ ] still and video subject isolation;
- [ ] background image/video;
- [ ] cancel during segmentation and encoding;
- [ ] app background/foreground and process restart;
- [ ] first model download and airplane-mode reuse;
- [ ] low-storage and codec failure.

Capture screenshots/video for the six smart remix flows. Verify touch targets, keyboard clearance, timeline readability, 60 FPS direct manipulation, progress, cancellation, and errors.

### Task 8.3: Final verification and documentation

- [ ] `npm run typecheck`
- [ ] `npm test -- --runInBand`
- [ ] `./gradlew :memeget-bg:compileDebugKotlin :app:assembleRelease`
- [ ] Record APK size delta, native model/dependency size, export performance, and memory gates.
- [ ] Install with `adb -s <physical-serial> install -r android/app/build/outputs/apk/release/app-release.apk`.
- [ ] Update README and Settings only with observed behavior. State one-time model downloads and no-upload guarantees precisely.
- [ ] Commit by completed chunk; merge only after the full physical-device matrix passes.

---

## Verification matrix

| Contract | Automated proof | Physical-device proof |
|---|---|---|
| Project geometry/time | Jest reducer, mapping, interpolation tests | Preview/output start-middle-end comparison |
| Undo/drafts | Reducer and injected-IO tests | Background/restart recovery |
| Image rendering | Spec validation and layer-order tests | Full-resolution PNG + alpha inspection |
| OCR replacement | Box normalization/order tests | Tap/replace real captions |
| Still cutout | Native state/error mapping | First download, offline reuse, edge inspection |
| Timeline/trim | Source↔output time tests | Real seek, trim, split, duration |
| Audio | Export result invariants | Spoken A/V sync and volume/mute |
| Motion | Keyframe interpolation tests | Manual/auto subject following |
| Video isolation | Mask-track format/invalidation tests | Flicker, occlusion, memory, speed |
| Export/cancel | State-machine tests | Cancel each stage; no leaked file/codec |
| Collection | Persistence orchestration tests | Count +1, searchable, original unchanged |
| Build | Typecheck/Jest/Kotlin/release | Upgrade install over existing data |

## Success criteria

- Replacing existing image text, isolating a subject, trimming/muting a video, and adding timed text are each reachable in a few obvious taps.
- Image preview and output agree within two preview pixels after scaling.
- Video overlay timing agrees within one rendered frame at the output frame rate.
- Original audio is preserved and A/V end-time delta stays below 50 ms unless mute is explicit.
- Video segmentation/tracking features appear only after their performance/quality gates pass; manual keyframes remain a complete fallback.
- No source image/video bytes leave the device. One-time model downloads are explicit and user-triggered.
- Every successful result is a real PNG or H.264/AAC MP4, creates exactly one indexed collection item, and leaves the original untouched.
- Cancellation and every failure path leave no new meme and no partial/session temp leaks.
- Full automated verification, release build, and physical Pixel 9 Pro matrix pass before merge.
