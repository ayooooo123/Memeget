# On-Device Image Editing Implementation Plan

> **For agentic workers:** REQUIRED: Use `@subagent-driven-development` when subagents are available, otherwise `@executing-plans`. Follow `@test-driven-development`, `@react-native-best-practices`, and `@verification-before-completion`. Every permanent behavior starts with a failing test or a reproducible device scenario.

**Goal:** Turn the current top/bottom-caption variation screen into an honest, on-device image editor that can position and restyle text, replace text regions, crop/rotate/flip images, remove or replace backgrounds, isolate individual subjects, and save every result as a newly indexed meme without altering the source.

**Architecture:** Keep React Native responsible for interaction and normalized edit state; keep final-resolution pixel work in the existing `memeget-bg` Android module. A pure TypeScript reducer defines deterministic edit history and preview geometry. Native Bitmap/Canvas rendering receives one structured edit specification, while ML Kit supplies OCR boxes and subject masks. All coordinates are normalized against the post-rotation, post-crop working canvas so preview and output share one coordinate system.

**Tech Stack:** React Native 0.85, Expo 56, TypeScript 5.9, React Native `PanResponder`, Android Bitmap/Canvas, existing ML Kit text recognition 19.0.1, ML Kit Subject Segmentation 16.0.0-beta1, Expo Modules Kotlin bridge, Jest 30.

**Current baseline:** Commit `c0ee240` already provides `MemeVariationEditor`, native PNG text rendering, Media3 video captions, `saveSharedFiles`, pending-row insertion, background indexing, and safe temporary-file cleanup. Extend these paths; do not create a second persistence or export convention.

**Research references:**

- ML Kit subject segmentation is Android 24+, beta, and delivered as an unbundled Play Services module: https://developers.google.com/ml-kit/vision/subject-segmentation/android
- Existing OCR dependency already resolves `play-services-mlkit-text-recognition:19.0.1`; expose its bounding boxes rather than adding another OCR stack.
- Expo ImageManipulator remains useful for reference only; it cannot flatten editable text layers: https://docs.expo.dev/versions/latest/sdk/imagemanipulator/

**Required execution prerequisite:** Work on a dedicated feature branch/worktree from current `main`. Preserve the unrelated local edit in `docs/superpowers/specs/2026-07-24-voice-diarization-identification-design.md`.

---

## Product boundary

### Included in the first image-editor milestone

1. Add multiple text layers with editable content, position, size, alignment, fill, outline, and optional backing box.
2. Drag text layers directly on the preview; use explicit controls for size/style so gestures stay predictable on small screens.
3. Crop, rotate by 90-degree increments, and flip horizontally/vertically.
4. Detect OCR text regions with bounding boxes. Selecting a region can:
   - cover it with a sampled solid color or pixelation; or
   - cover it and create a prefilled replacement text layer.
5. Manual rectangular cover regions for text OCR misses.
6. Remove the background, isolate all subjects, or choose one detected subject.
7. Composite the isolated subject over transparency or a solid color.
8. Undo/redo up to 30 edit states.
9. Save a full-resolution PNG as a new meme, immediately show it in the collection, and index it through the existing path.
10. Clear, actionable states for model download, unsupported images, render failure, and missing linked folders.

### Explicitly excluded from this milestone

- Generative/content-aware inpainting. “Remove text” must say exactly what it does: sampled-color cover or pixelation. Never present rectangle fill as AI inpainting.
- Clone stamp, freehand painting, stickers, filters, or arbitrary perspective warps.
- Video subject segmentation or video text removal.
- Uploading media to a server.
- A new project-file format. Edits are transient until flattened into the new PNG.

### Network/privacy contract

Subject segmentation runs on-device after Google Play Services downloads its unbundled model. Because Memeget is sideloaded, do not rely only on Play Store install-time delivery. The first background-removal action must explicitly check/request the module, show “Downloading background removal model — one time,” and allow cancellation. Update the privacy/network copy: this is a user-triggered one-time model download, never an image upload. Once installed, the feature must work in airplane mode.

---

## Canonical edit model

Create `src/memeImageEditCore.ts` with serializable types. Do not put React state, native paths, or database records in this module.

```ts
export type NormalizedPoint = { x: number; y: number }; // each value 0..1
export type NormalizedRect = { x: number; y: number; width: number; height: number };

export interface ImageBaseTransform {
  rotation: 0 | 90 | 180 | 270;
  flipX: boolean;
  flipY: boolean;
  crop: NormalizedRect; // source after rotation/flip; full image = 0,0,1,1
}

export interface TextLayer {
  id: string;
  kind: 'text';
  text: string;
  center: NormalizedPoint;
  width: number;
  fontScale: number; // relative to working-canvas width
  align: 'left' | 'center' | 'right';
  color: string; // validated #RRGGBB or #AARRGGBB
  outlineColor: string;
  outlineScale: number;
  backgroundColor: string | null;
  uppercase: boolean;
}

export interface CoverLayer {
  id: string;
  kind: 'cover';
  rect: NormalizedRect;
  mode: 'solid' | 'pixelate';
  color: string; // used by solid; initially sampled, user-overridable
  pixelSize: number; // used by pixelate
}

export interface SubjectBackground {
  mode: 'keep' | 'transparent' | 'solid';
  color: string;
  maskUri: string | null; // transient cache PNG; never stored in DB
  subjectIndex: number | null; // null = combined foreground
}

export interface MemeImageEditSpec {
  version: 1;
  base: ImageBaseTransform;
  layers: Array<TextLayer | CoverLayer>; // render in array order
  background: SubjectBackground;
}
```

**Coordinate invariant:** Native rendering first applies rotation/flip and crop to create the working bitmap. Every layer coordinate is then interpreted against that working bitmap. UI preview uses the same sequence. Geometry helpers clamp rectangles/points to `[0,1]`; reducers reject NaN/Infinity and zero-area crops.

---

## File structure

### New files

- `src/memeImageEditCore.ts` — types, validation, geometry transforms, reducer, undo/redo history, deterministic IDs supplied by caller.
- `src/memeImageEditCore.test.ts` — geometry, clamping, reducer, history, layer ordering, and serialization contracts.
- `src/components/MemeImageEditor.tsx` — image-only editor shell, preview, tool rail, selected-layer controls, save/error states.
- `src/components/MemeImageCanvas.tsx` — scaled preview, coordinate conversion, text/cover overlays, selection chrome, drag handling.
- `src/components/MemeTextTool.tsx` — text content/style controls.
- `src/components/MemeCropTool.tsx` — crop/rotate/flip controls.
- `src/components/MemeRemoveTextTool.tsx` — OCR/manual-region selection and honest cover/pixelate controls.
- `src/components/MemeSubjectTool.tsx` — model state, combined/individual subject selection, transparent/solid background controls.
- `modules/memeget-bg/android/src/main/java/expo/modules/memegetbg/MemeImageRenderer.kt` — structured full-resolution Bitmap/Canvas renderer split out of `MemeMediaEditor`.
- `modules/memeget-bg/android/src/main/java/expo/modules/memegetbg/MemeTextDetector.kt` — ML Kit line/element text and normalized boxes.
- `modules/memeget-bg/android/src/main/java/expo/modules/memegetbg/MemeSubjectSegmenter.kt` — model availability/download, combined and individual mask PNGs.

### Existing files to modify

- `src/components/MemeVariationEditor.tsx` — dispatch images to `MemeImageEditor`; keep the existing focused video-caption flow.
- `src/components/MemeGrid.tsx` — keep persistence/index orchestration; accept the final edit spec and temporary mask paths.
- `src/memeActionsCore.ts` and tests — keep output naming; add no image-editor geometry here.
- `modules/memeget-bg/index.ts` — typed `renderImageEdit`, `detectImageTextRegions`, segmentation availability/download, and `segmentImageSubjects` APIs.
- `modules/memeget-bg/android/src/main/java/expo/modules/memegetbg/MemegetBgModule.kt` — promise-safe bridge functions and coded failures.
- `modules/memeget-bg/android/src/main/java/expo/modules/memegetbg/MemeMediaEditor.kt` — retain video export; delegate image work to `MemeImageRenderer` and remove the old fixed-caption image path after migration.
- `modules/memeget-bg/android/build.gradle` — add `play-services-mlkit-subject-segmentation:16.0.0-beta1`; explicitly keep Media3 versions aligned with Expo Video.
- `android/app/src/main/AndroidManifest.xml` or the Expo config plugin responsible for generated manifest changes — declare `subject_segment` only if explicit module installation still needs manifest metadata; do not hand-edit generated output if prebuild overwrites it.
- `README.md` — document image editing, the one-time subject model download, and no-upload behavior after device verification.

---

## Chunk 1: Pure edit state and renderer contract

### Task 1: Build the normalized edit reducer and history

**Files:**
- Create: `src/memeImageEditCore.ts`
- Create: `src/memeImageEditCore.test.ts`

- [ ] Write failing tests for full-image defaults, clamping, crop validation, rotated preview/source dimensions, layer insertion/reordering/removal, text updates, and a 30-state undo/redo cap.
- [ ] Verify RED with `npm test -- memeImageEditCore --runInBand`.
- [ ] Implement pure helpers and a reducer. Caller supplies IDs; the reducer must never call `Date.now()` or allocate random IDs.
- [ ] Make history coalesce drag updates: one pointer gesture produces one undo step, not one step per move event.
- [ ] Round-trip a valid spec through `JSON.stringify/parse`; reject unknown versions and non-finite coordinates.
- [ ] Verify GREEN with the focused test and `npm run typecheck`.

**Done when:** Pure tests prove deterministic preview geometry and history without importing React Native or native modules.

### Task 2: Replace the fixed image-caption bridge with structured rendering

**Files:**
- Create: `MemeImageRenderer.kt`
- Modify: `MemeMediaEditor.kt`
- Modify: `MemegetBgModule.kt`
- Modify: `modules/memeget-bg/index.ts`
- Modify: `MemeGrid.tsx`

- [ ] Add a typed JS bridge contract: `renderImageEdit(sourceUri, specJson): Promise<string | null>`.
- [ ] Parse and validate version 1 natively. Reject malformed colors, non-finite values, empty crops, unknown layer modes, and oversized layer counts with coded errors.
- [ ] Decode with EXIF orientation respected. Bound working memory, but do not silently downscale successful output below the source dimensions. If the source cannot fit the safe bitmap budget, return a readable size error and leave the original untouched.
- [ ] Apply base transform, crop, optional subject mask/background, then layers in array order.
- [ ] Render text with matching alignment, wrap width, outline, backing box, and uppercase behavior. Render cover layers as solid fill or pixelation.
- [ ] Keep the current materialize-before-native and `finally` cleanup rules.
- [ ] Compile with `./gradlew :memeget-bg:compileDebugKotlin`.

**Device scenario:** Render one deterministic fixture containing crop + rotation + two text layers + one cover. Pull/open the output and verify source dimensions, PNG signature, visible layer order, and unchanged original.

---

## Chunk 2: Interactive editor shell

### Task 3: Split image and video editor flows

**Files:**
- Create: `MemeImageEditor.tsx`
- Modify: `MemeVariationEditor.tsx`
- Modify: `MemeGrid.tsx`

- [ ] Keep video captions in the current lightweight editor. Route only `item.kind === 'image'` to `MemeImageEditor`.
- [ ] Seed the image editor with a default spec and bounded history. Reset state by meme ID, not by incidental parent renders.
- [ ] Add a bottom tool rail: `Text`, `Remove text`, `Crop`, `Subject`; selected tool owns one focused control panel.
- [ ] Keep Save visible above the keyboard. Save is disabled when spec equals the default or while rendering.
- [ ] Show native/model errors inside the modal; root-level toasts are behind Android `Modal` windows and are not sufficient feedback.
- [ ] Cancel discards edit state and temp masks. Android back closes the keyboard first, then the editor.

**Done when:** Image and video edit screens open from the existing More action, and all existing video variation behavior remains unchanged.

### Task 4: Implement preview parity, selection, and drag

**Files:**
- Create: `MemeImageCanvas.tsx`
- Create: `MemeTextTool.tsx`
- Create: `MemeCropTool.tsx`
- Modify: `memeImageEditCore.ts` tests

- [ ] Add failing coordinate-conversion tests for portrait, landscape, 90° rotation, crop offsets, and letterboxed preview bounds.
- [ ] Render the transformed source in a measured preview frame. Convert pointer coordinates only inside the actual contained image rect, not the surrounding letterbox.
- [ ] Use one `PanResponder` per selected layer; update ephemeral position during drag and commit one reducer action on release.
- [ ] Add text layer creation/editing, direct selection, delete, ordering, size, alignment, fill, outline, backing box, and uppercase controls.
- [ ] Add crop presets (`Free`, `Original`, `1:1`, `4:5`, `16:9`), rotate, and flip. Changing the base transform remaps or clamps existing layers deterministically.
- [ ] Add Undo/Redo buttons with accessibility labels and disabled states.

**Device scenarios:** On one portrait and one landscape meme, drag text to all four corners, rotate, crop, undo/redo, save, and compare preview placement with the full-resolution output.

---

## Chunk 3: OCR-assisted text replacement

### Task 5: Expose OCR text boxes from the existing ML Kit stack

**Files:**
- Create: `MemeTextDetector.kt`
- Modify: `MemegetBgModule.kt`
- Modify: `modules/memeget-bg/index.ts`
- Create/modify: `memeImageEditCore` tests for box normalization

Define:

```ts
export interface DetectedTextRegion {
  id: string;
  text: string;
  rect: NormalizedRect;
  angleDegrees: number;
  confidence: number | null;
}
```

- [ ] Reuse ML Kit text recognition already pulled by `expo-text-extractor`; add an explicit direct module dependency only if Gradle requires it for compilation.
- [ ] Return line-level regions with stable reading-order IDs and normalized boxes. Clamp boxes and ignore empty/out-of-bounds detections.
- [ ] Materialize the SAF source once, use it for OCR and rendering, and clean it after the editor closes.
- [ ] Promise resolution/rejection must happen exactly once; close recognizer resources on success, failure, and editor cancellation.
- [ ] Test the pure normalization/order logic and compile the native module.

**Done when:** A known-caption fixture returns tappable boxes matching its visible lines, including rotated images.

### Task 6: Add honest region removal and replacement

**Files:**
- Create: `MemeRemoveTextTool.tsx`
- Modify: `MemeImageCanvas.tsx`
- Modify: `MemeImageRenderer.kt`
- Modify: `memeImageEditCore.ts` tests

- [ ] Show OCR boxes as selectable overlays; add a manual rectangle mode for missed text.
- [ ] Selecting `Cover` creates a `CoverLayer`. Default its color to the median of pixels sampled just outside the region; let the user override it.
- [ ] Selecting `Pixelate` creates a pixelation layer with a bounded block-size control.
- [ ] Selecting `Replace` creates the cover plus a new text layer prefilled with detected text and centered over the region.
- [ ] Do not mutate or delete the OCR region list when edits change; rerunning detection is an explicit action.
- [ ] Label these actions `Cover`, `Pixelate`, and `Replace`. Do not use “Erase with AI” or “Inpaint.”

**Device scenarios:** Replace top/bottom Impact text, cover text over a solid speech bubble, manually cover one OCR miss, and verify output contains no stale source text in the selected regions.

---

## Chunk 4: Subject isolation and background replacement

### Task 7: Gate and implement subject segmentation

**Files:**
- Create: `MemeSubjectSegmenter.kt`
- Modify: `MemegetBgModule.kt`
- Modify: `modules/memeget-bg/index.ts`
- Modify: `modules/memeget-bg/android/build.gradle`
- Modify: manifest/config plugin as required

Expose:

```ts
export type SubjectModelState = 'available' | 'downloadable' | 'downloading' | 'unavailable';
export interface SegmentedSubject {
  index: number;
  maskUri: string;
  bounds: NormalizedRect;
}
export interface SubjectSegmentationResult {
  combinedMaskUri: string;
  subjects: SegmentedSubject[];
}
```

- [ ] Pin `com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1` and record that it is a beta API.
- [ ] Add explicit `ModuleInstallClient` availability and download operations suitable for a sideloaded APK. Never call segmentation before availability is confirmed.
- [ ] Configure foreground confidence masks and individual subject masks. Write masks to `meme_work_*` cache PNGs and return normalized subject bounds.
- [ ] Reject cancellation/download/offline cases with distinct codes; no empty-success response.
- [ ] Release segmenter/native resources and remove partial masks on failure.
- [ ] Compile and run a release build before UI integration.

**Hard gate:** On the physical test device, run first use with network available, then force-stop, enable airplane mode, reopen, and segment again. The second run must complete without network.

### Task 8: Add subject/background controls and composition

**Files:**
- Create: `MemeSubjectTool.tsx`
- Modify: `MemeImageEditor.tsx`
- Modify: `MemeImageCanvas.tsx`
- Modify: `MemeImageRenderer.kt`
- Modify: `MemeImageEditCore` tests

- [ ] First action shows model availability/download state and explicit one-time-download copy.
- [ ] After segmentation, default to combined foreground. If multiple subjects exist, show tappable numbered subject thumbnails and an `All subjects` option.
- [ ] Background choices: `Keep`, `Transparent`, and `Solid`; solid has a small curated palette plus hex input validation.
- [ ] Preview uses the returned mask; final render composites at source resolution with feathered alpha edges.
- [ ] Changing crop/rotation after segmentation invalidates the mask and prompts a rerun rather than silently misaligning it.
- [ ] Undoing past subject removal cleans masks no longer referenced by any history state.

**Device scenarios:** Isolate one person, isolate all people, isolate a non-person subject, export transparency, export a solid background, and inspect edge halos at 200% zoom.

---

## Chunk 5: Persistence, cleanup, and release verification

### Task 9: Preserve collection and temp-file invariants

**Files:**
- Modify: `MemeGrid.tsx`
- Modify: `LibraryScreen.tsx`
- Modify: `saf.ts` only if current helpers cannot express cleanup safely
- Add focused orchestration tests if a new service is extracted

- [ ] Save only flattened PNG output. The source URI, database row, sidecar knowledge, and source file remain untouched.
- [ ] Keep `saveSharedFiles` as the sole destination writer and dedupe gate.
- [ ] Keep pending-row insertion immediate; call `onCreated` so ready embeddings index the new item immediately, with pending recovery as the durable fallback.
- [ ] Close the editor only after the new file is written. Show duplicate/write/render errors inline.
- [ ] Delete materialized source, render output, OCR intermediates, and masks in `finally` blocks. Clipboard/export-owned files retain their existing lifecycle.
- [ ] Emit one library change after persistence and another only if indexing materially updates the record.

**Done when:** Successful save increments the collection by one; cancellation and every injected failure leave count/files unchanged and no `meme_work_*` leak after app restart.

### Task 10: Verify, document, and deliver

**Files:**
- Modify after behavior passes: `README.md`
- Modify after behavior passes: relevant privacy/network text in Settings if present

- [ ] Run `npm run typecheck`.
- [ ] Run `npm test -- --runInBand`; require zero failures.
- [ ] Run `./gradlew :memeget-bg:compileDebugKotlin :app:assembleRelease`.
- [ ] Record APK byte-size delta versus `c0ee240`; explain the native dependency/model-delivery contribution.
- [ ] Install the release APK with `adb -s <physical-device> install -r android/app/build/outputs/apk/release/app-release.apk`.
- [ ] Exercise every device scenario above against real linked-folder `content://` files, not only app-cache fixtures.
- [ ] Verify one-time model download messaging, airplane-mode reuse, image editor keyboard behavior, back/cancel, process restart, and low-memory failure handling.
- [ ] Capture screenshots for portrait/landscape editor, OCR replacement, transparent subject isolation, and compact error states.
- [ ] Update README only with observed behavior. State the one-time Play Services subject-model download explicitly; retain “no uploads.”

---

## Overall verification matrix

| Contract | Automated proof | Device proof |
|---|---|---|
| Geometry/undo | Jest reducer and coordinate tests | Preview/output corner placement |
| Source preservation | Orchestration failure tests | Source file checksum unchanged |
| Text rendering | Spec validation + layer-order tests | Multi-layer PNG inspection |
| Text replacement | OCR box normalization tests | Cover/replace known fixtures |
| Subject isolation | Native compile + state mapping tests | First download, offline reuse, alpha-edge inspection |
| Collection result | Save/index orchestration tests | Count increments and item searchable |
| Cleanup | Injected failures + cleanup assertions | Cache sweep after cancel/restart |
| Build health | Typecheck, 325+ Jest tests, Kotlin compile, release build | APK update installs over existing data |

## Success criteria

- A user can open any image meme, make multiple edits, undo/redo, and save a new PNG without changing the original.
- Preview and output placement agree within 2 pixels after scaling to the preview size for all tested orientations/crops.
- OCR regions are selectable and text replacement visibly covers the selected source region.
- Background removal can return all subjects or one selected subject and export real PNG transparency.
- No image bytes leave the device. The only new network path is an explicit, user-triggered one-time segmentation-model download.
- Every success creates exactly one collection item; every cancellation/failure creates none.
- Full tests, typecheck, native compile, release build, and physical-device scenarios pass before merge.
