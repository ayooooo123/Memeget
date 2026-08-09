# Image → video: audio and image artifacts

**Date:** 2026-08-08
**Status:** Approved for implementation planning

## Summary

Memeget will let a user turn a static image meme into a video by (1) adding an
audio track and/or (2) placing image "artifacts" (e.g. a character or sticker)
on top of it. Artifact images are pickable from the in-app meme library **and**
from the device filesystem.

The feature ships in two phases on one shared export path:

- **Phase A — static composite + audio.** The image, with any placed artifacts
  baked in, is held as a still frame for a chosen duration, with an optional
  looping music track. Output is an `.mp4`.
- **Phase B — animated artifacts.** An artifact can move/scale/fade over time
  via the `TransformKeyframe` machinery the editor model already has. Phase B
  adds a small set of **motion presets** (not a manual keyframe editor) and the
  native per-frame overlay compositing to render them.

The approach extends the existing media3 composition/export pipeline rather than
building a new renderer. Everything stays on-device; nothing is uploaded.

## Existing system

The editor and export stack already provides most of the primitives (verified by
source scout):

- **Artifact layer already modeled.** `src/memeEditProjectCore.ts` defines
  `MediaOverlayLayer` (`kind: 'media'`):
  `{ id, kind:'media', assetUri:string, assetKind:'image'|'video', fit:'contain'|'cover', targetMaskTrackId:string|null, active:TimeRangeUs|null, keyframes:TransformKeyframe[] }`.
  It is part of the layer union
  (`TextLayer | CoverLayer | SubjectLayer | MediaOverlayLayer | DrawLayer`,
  `memeEditProjectCore.ts:129`) and is `KeyframedLayer`. `TransformKeyframe`
  (`:34-41`) is `{ timeUs, center:NormalizedPoint, scale:number, rotationDegrees:number, opacity:number, easing:'linear'|'hold' }`.
- **Artifact rendering + list already exist.** `MemeEditCanvas.tsx`
  (`MediaLayerContent`, ~`:1177`) renders an image or video overlay from
  `assetUri`/`fit`; `MemeLayerList.tsx` (`:9-16`) labels it "Image overlay" /
  "Video overlay". The `add-layer` / `add-layers` reducer actions
  (`memeEditProjectCore.ts:2288`) validate and `normalizeLayer` new layers.
- **Audio is built** (`src/memeVideoAudioCore.ts`, `src/components/MemeVideoAudioTool.tsx`):
  pick a track via `DocumentPicker` (`audio/*`), `probeMedia` for its duration,
  choose start offset + volume; the exporter loops the music. Currently wired
  video-only.
- **Native exporter can draw a still as video.** `RetainedRangeComposition.kt`
  builds a media3 `Composition`; still "title cards" use `ImageAssetLoader` +
  `MediaItem.Builder#setImageDurationMs`. Music-only export forces an audio
  track onto silent content. Overlays burn in via
  `OverlayEffect(BitmapOverlay.createStaticBitmapOverlay(...))`
  (`RetainedRangeComposition.kt:454-466`) — **static PNG over every frame, no
  timing/keyframes.**
- **Export routing** (`src/components/MemeGrid.tsx`): `onStudioExport` (`:939`)
  picks the destination, then `runStudioExport` (`:896`) branches on
  `project.source.kind` — video ⇒ `runVideoExport` (`:791`, builds overlay PNG via
  `buildVideoOverlayRenderPlan` → optional `renderImageProject`, then
  `buildVideoCompositionPlan` → `exportVideoProject`); otherwise the image branch
  is **inlined** in `runStudioExport` (`:896-923`): `buildImageRenderPlan` →
  `renderImageProject`. There is no standalone `runImageExport` function.
- **Pickers.** `expo-document-picker` is installed and used for `image/*`
  (`MemeSubjectTool.tsx:198`) and `audio/*` (`MemeVideoAudioTool.tsx:93`). There
  is **no** `expo-image-picker` and **no** reusable "pick a library item as
  input" modal.

### The gaps

1. `buildVideoCompositionPlan` **hard-rejects image sources**
   (`memeVideoCompositionCore.ts:241-244`, throws unless
   `source.kind === 'video'`) and requires ≥1 retained video range (`:302-309`).
   There is no image→video path.
2. No tool/button adds a `MediaOverlayLayer`; the tool rail
   (`memeEditCanvasCore.ts:97-100`) has no add-media/artifact tool, and the audio
   tool is video-only.
3. No library picker to select an existing meme image as an artifact.
4. Native overlays are static only; animated artifacts (Phase B) need
   time-varying overlay rendering.

## Goals

1. Add an image artifact to an image meme, sourced from the library or the
   filesystem, and place/scale/rotate it on the canvas (reusing existing
   `MediaOverlayLayer` gestures).
2. Add an audio track to an image meme.
3. Export an image meme (with baked static artifacts) as an `.mp4`: still frame
   for a duration + optional looping music (Phase A).
4. Animate an artifact with a motion preset and render that motion into the
   exported video (Phase B).
5. Reuse the existing composition/export pipeline, progress, cancellation, and
   on-device privacy model.

## Non-goals

- A full manual keyframe timeline editor for artifacts (Phase B ships presets
  only; manual keyframing is a later extension). The underlying model already
  stores arbitrary keyframes, so this is a UI limit, not a data limit.
- `expo-image-picker` / camera capture. Filesystem picking uses the existing
  `expo-document-picker`.
- Video-artifact overlays as animated layers over an image (artifacts in this
  feature are images; `assetKind:'video'` overlays are out of scope here).
- Changing the existing video-source export behavior.
- Transparent-alpha intermediate video encoding (rejected approach; see below).

## Chosen approach

**Extend the media3 composition pipeline** (Approach 1 of the three considered).

Rejected alternatives:
- *Custom Canvas frame renderer + MediaCodec encoder:* full control but a large
  new native subsystem (frame pump, A/V sync, perf) duplicating media3. Highest
  risk.
- *Pre-render overlays to a transparent video then reuse static burn-in:*
  Android H.264 has no alpha channel, forcing a PNG frame sequence — heavy and
  awkward.

## Data model changes

`MemeEditProject` (`memeEditProjectCore.ts:173-185`) gains an **image-video
intent** so an image project can carry export-as-video settings:

- Add an optional `imageVideo` spec on the project, present only for
  `source.kind === 'image'`:
  `imageVideo: { durationUs: number; music: { uri, volume, startUs } | null } | null`.
  **`imageVideo != null` is the single source of truth for video output:**
  `null`/absent ⇒ still-image export; non-null ⇒ export as video. Adding music,
  applying a motion preset to an artifact, or toggling "Export as video" all set
  `imageVideo` (see Trigger); clearing all of them reverts it to `null`. `music`
  reuses the exact shape `VideoCompositionMusic` already uses
  (`memeVideoCompositionCore.ts:159-166`).
- The existing video path keeps its `video: VideoEditSpec | null`; the two specs
  are mutually exclusive by `source.kind`. Validation rejects `imageVideo` on a
  video source and `video` on an image source.
- `durationUs` is clamped to `[MIN_IMAGE_VIDEO_DURATION_US, MAX_IMAGE_VIDEO_DURATION_US]`
  (proposed 1 s … 60 s) in the project validator, so a hand-written project can
  never outrun the exporter.

No change to `MediaOverlayLayer` — its `keyframes` and `active` fields already
express Phase B animation; Phase B only adds a **preset → keyframes** generator
(pure function) and honors those keyframes in the native renderer.

## New UI

### Add-artifact tool

- Add an `'artifact'` tool to `memeEditToolsForSource` (`memeEditCanvasCore.ts:97-100`)
  for **image** projects. Intentionally also add it to **video** projects, which
  support media overlays in the model but likewise have no add-media tool today.
  Add its spec (glyph, label) to the `TOOLS` array (`MemeEditToolRail.tsx:24-35`).
- The tool opens a **source chooser**: "From library" or "From files".
  - *From files:* `DocumentPicker.getDocumentAsync({ type:['image/*'], copyToCacheDirectory:true, multiple:false })` (identical to `MemeSubjectTool.tsx:198`). The
    returned cache uri is the `assetUri`.
  - *From library:* opens the new **library artifact picker** (below); the
    selected meme's uri is the `assetUri`. Only image-kind library items are
    selectable as artifacts (video artifacts are a non-goal here).
- On selection, dispatch `add-layer` with a new `MediaOverlayLayer` built by a
  factory `createMediaOverlayLayer(id, { assetUri, assetKind:'image' })` (mirrors
  `createDrawLayer`, `memeDrawToolCore.ts:81`): `fit:'contain'`,
  `targetMaskTrackId:null`, `active:null`, `keyframes:[]`, centered at default
  transform. The layer is then movable/scalable/rotatable with the existing
  canvas gestures and appears in `MemeLayerList`.

### Library artifact picker

- A new modal component `MemeLibraryPicker` that reuses `MemeGrid` in a
  **select-return** mode: browse/search the library, tap an image tile → resolve
  its `uri`, close, return it to the caller. It does not open the studio.
- Implemented by threading an optional `selectMode`/`onPick(uri)` through the
  existing grid selection path (`MemeGrid` already tracks `selected`); when in
  select mode a tap calls `onPick` instead of opening the viewer/studio, and
  non-image kinds are visually disabled.
- This is a self-contained unit: given "pick an image from the library," it
  returns a uri and nothing else.

### Audio on image projects

- Add `'audio'` to the image tool list. Reuse `MemeVideoAudioTool`'s music
  controls (`onSetMusic`): for an image project, setting music writes
  `project.imageVideo.music` and defaults `imageVideo.durationUs` to the track's
  measured duration (via `probeMedia`). **This is a real refactor, not just
  hiding UI:** the tool currently assumes a non-null `project.video` (`const video
  = project.video`, `:52`) and unconditionally reads `video.retainedRanges` /
  `outputDurationUs` (`:144-145`). Split the video-only body (source mute/volume,
  retained-range duration) from the shared music controls so the music section
  works with `imageVideo`, and show the source-audio section only for video.

## Image→video export path

### Duration and video-output trigger

- **Duration source** (resolved once at export, pure): if music present ⇒
  `durationUs = musicDurationUs`; else the user-set slider value
  (default 3 s). When any artifact is animated ⇒
  `durationUs = max(resolved, lastKeyframeTimeUs)`.
- **Trigger:** `imageVideo != null` is authoritative — an image project exports
  as **video** iff `imageVideo` is set. Setting music, applying a motion preset
  to any artifact, or the explicit **"Export as video"** toggle all set
  `imageVideo` (defaulting `durationUs` to the resolved duration above), so
  animation and audio can never disagree with the export decision or leave the
  duration without an owner. With `imageVideo == null` the project exports as a
  still image (unchanged).

### Plan builder

- Add `buildImageVideoCompositionPlan(project)` in `memeVideoCompositionCore.ts`
  (sibling to `buildVideoCompositionPlan`) that accepts `source.kind === 'image'`
  and emits a `VideoCompositionPlan` variant:
  - `source`: the **baked base still** — the composed image (base + all *static*
    layers) produced by the existing `renderImageProject` plan — described as an
    image source with `durationUs = output.durationUs`.
  - `output`: `{ widthPx, heightPx }` from `visibleImageDimensions` (same math
    the still exporter uses), `durationUs` = resolved duration, `speed = 1`.
  - `music`: unchanged `VideoCompositionMusic` shape when present.
  - `overlays` (Phase B): a list of animated-overlay descriptors (below); empty
    in Phase A.
  - `rejections[]`: reuse the existing readable-rejection mechanism (asset can't
    decode, duration out of range, too many overlays, etc.).
- `buildVideoCompositionPlan` is left unchanged for video sources. A thin
  `buildCompositionPlan(project)` dispatcher routes by `source.kind` so callers
  have one entry point.
- Export routing (`runStudioExport`, `MemeGrid.tsx:896`): for an image project
  with `imageVideo != null`, route to a new `runImageVideoExport` (mirrors
  `runVideoExport`); otherwise keep the existing inlined image-render branch.

### Phase A native rendering (still + music)

- Extend the native plan parser (`VideoExportPlan.kt`) and
  `RetainedRangeComposition.kt` to accept an **image-base composition**: a single
  media3 sequence item that is the baked base still via `ImageAssetLoader` +
  `setImageDurationMs(durationMs)`, concatenated with the existing looping-music
  sequence (`.setIsLooping(true)`, `.setRemoveVideo()`), encoded through the same
  `Composition`/exporter used today. This reuses the existing card/still and
  music machinery; the only new logic is "image source is legal and needs no
  retained range."

### Phase B native rendering (animated artifacts)

- Split the export render:
  - **Base still** = base image + all *static* layers (text, draw, cover,
    subject, and any non-animated media overlays), baked by `renderImageProject`
    exactly as Phase A.
  - **Animated media overlays** = each `MediaOverlayLayer` with non-empty
    `keyframes` becomes a separate overlay descriptor in the plan:
    `{ uri, naturalWidthPx, naturalHeightPx, fit, keyframes:[{ timeUs, centerX, centerY, scale, rotationDegrees, opacity, easing }] }`.
- In `RetainedRangeComposition.kt`, replace `createStaticBitmapOverlay` for these
  with a **time-varying `BitmapOverlay`** subclass whose
  `getOverlaySettings(presentationTimeUs)` interpolates the keyframes
  (translation from `center`, `scale`, `alpha` from `opacity`; `easing`
  linear/hold) into media3 `OverlaySettings`. **Rotation** keyframes use an
  `OverlaySettings` transformation matrix (or a matrix-capable overlay); if a
  device/media3 version cannot honor per-frame rotation, the exporter falls back
  to the nearest static rotation and records a warning (never a silent drop).
- Overlay pixel bounds keep the existing `MAX_OVERLAY_PIXELS` guard
  (`VideoExportPlan.kt:129,282`), which today bounds the single burn-in overlay
  PNG against the frame size; applying it **per animated overlay** is a
  deliberate semantic extension for Phase B. The number of animated overlays is
  capped by `PROJECT_LIMITS.maxLayers`.

## Error handling and edge cases

- **No linked folder / undecodable artifact:** the picker/plan surfaces a
  readable error; the artifact is not added / the export rejects with a reason
  (reuse the `rejections[]` channel and existing export error toasts).
- **Artifact from library that is a video:** disabled in the picker (non-goal).
- **Music shorter than duration:** loops (existing behavior). **Longer than
  duration:** trimmed to `durationUs` from `startUs`.
- **Duration with animation but no music and slider untouched:** duration =
  `max(defaultSlider, lastKeyframeTimeUs)` so the whole animation is shown.
- **Native exporter missing** (`videoExporterNativeAvailable === false`): the
  "export as video" affordance is hidden/disabled, exactly as the existing video
  export gate.
- **Cancellation / progress:** reuse `exportVideoProject`'s `exportId`,
  `onVideoExportProgress`, and `cancelVideoExport`.
- **Re-open a saved image-video project:** `imageVideo` round-trips through the
  project (de)serializer; add coverage so it is not dropped.

## Testing

Pure-core (jest, deterministic — matches the repo's core-test convention):
- `buildImageVideoCompositionPlan`: image source accepted; duration derivation
  (music vs slider vs animation `max`); output dimensions from
  `visibleImageDimensions`; rejections (bad duration, undecodable asset, overlay
  cap); music passthrough.
- Project validator: `imageVideo` only on image sources, `video` only on video
  sources; duration clamp.
- Preset→keyframes generator (Phase B): each preset yields keyframes with correct
  start/end `center`/`scale`/`opacity` and monotonic `timeUs` within duration.
- Serializer round-trip for `imageVideo` and animated `MediaOverlayLayer`.

Native (instrumented device gate — matches existing `docs/editing` gate culture):
- Phase A: image-base + music export produces a playable `.mp4` of expected
  duration with an audio track.
- Phase B: animated overlay renders motion (frame samples at t0/mid/end differ as
  the preset predicts); rotation-fallback path records a warning rather than
  crashing.

## Rollout / phasing

- **Phase A (foundation):** data-model `imageVideo`; add-artifact tool +
  library/filesystem picker (adds *static* artifacts); audio-on-image; image→video
  plan builder + routing; Phase A native still+music path. Ships a complete
  "image → video with a character and audio" experience.
- **Phase B (animation):** motion presets + preset→keyframes generator; animated
  overlay plan descriptors; native time-varying `BitmapOverlay` (with rotation
  fallback). Builds on the same plan and export path.

## Risks

- **media3 per-frame overlay animation** is the main native unknown (especially
  rotation). Mitigation: express motion as translate/scale/alpha (well
  supported), isolate rotation behind a matrix overlay with a static fallback +
  warning, and gate Phase B behind a device probe like the existing segmentation
  gate.
- **Duration/size for large artifacts** could inflate memory; reuse the existing
  overlay/card pixel guards.
