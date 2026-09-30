# OCR without Google Play Services

Status: **decided and switched (2026-09-29): Option 1.** Google Play services
ML Kit is out of the app entirely — JD: if it depends on Play services, we
can't use it. What shipped:

- **Index OCR** — `src/ocrEngine.ts` runs react-native-executorch `OCRModule`
  (`OCR_ENGLISH`: CRAFT int8 detector 20.9 MB + CRNN English recognizer
  18.4 MB, downloaded on first use) behind `extractImageText`, called from
  `analyzeFrames` in `src/indexer.ts`. Detections are put back in reading order
  by `src/ocrCore.ts`. `expo-text-extractor` is uninstalled.
- **Editor text boxes** — native `prepareTextDetectionImage` (in
  `MemeTextDetector.kt`) still does the EXIF-upright, 2048-capped decode, now
  writing a JPEG; JS runs the same OCR engine on it and `ocrCore` builds the
  block/line/element result the text tool already read.
- **Subject cutouts** — FastSAM-s (47.3 MB) via
  `InstanceSegmentationModule`, orchestrated in `src/subjectSegmentation.ts`.
  Native `prepareSubjectSegmentation` decodes under the memory ceiling;
  `selectSubjectMasks` (`src/memeCutoutCore.ts`) picks subjects out of FastSAM's
  "segment everything" masks (drops speckle, the whole frame, edge-hugging
  backdrop, and parts of an already-chosen subject); native
  `writeSubjectCutouts` writes the PNGs with a one-pixel feathered alpha.
  ML Kit picked salient subjects itself; this heuristic is the new, unmeasured
  part — check it on real memes.
- The `play-services-mlkit-*` Gradle deps and the
  `com.google.mlkit.vision.DEPENDENCIES` manifest entry are gone.

Not yet measured: CRAFT+CRNN accuracy against the old ML Kit strings (the A/B
below still applies), and cutout subject choice on real memes.

The research that led here follows, unchanged.

## Why this is open

OCR on Android goes through `expo-text-extractor`, whose
`android/build.gradle` hardcodes:

```gradle
implementation 'com.google.android.gms:play-services-mlkit-text-recognition:19.0.1'
```

`modules/memeget-bg/android/build.gradle` pulls the same artifact for
`MemeTextDetector.kt` (the editor's text boxes), plus
`play-services-mlkit-subject-segmentation:16.0.0-beta1`.

That artifact is the **unbundled** ML Kit: the model is not in the APK, it is
fetched by Play Services through the "dynamite" loader. On a phone without GMS
it can never load. From the device log:

```
DynamiteModule: Local module descriptor class for com.google.mlkit.dynamite.text.latin not found.
DynamiteModule: Invalid GmsCore APK, remote loading disabled.
MobileVisionBase: Failed to load deprecated vision dynamite module.
```

`ocr()` in `indexer.ts` swallows that and returns `''`, so it degrades silently.
As of 1169 it latches off after the first failure instead of paying two failed
dynamite lookups plus an exception per image and per sampled video frame.

## What OCR is actually worth here

Measured on the live sidecar export (341 memes, `/sdcard/Meme/.memeget/`):

| | |
|---|---|
| memes carrying OCR text | 166 / 341 (48.7%) |
| median OCR length | 27 chars (max 1972 — a tweet screenshot) |
| **memes whose OCR words appear nowhere else** in caption, tags or extra terms | **75 (22%)** |

So a fifth of the library has search terms that only OCR provides. The newest
imports — including `tweet_*.jpg` screenshots that are mostly text — have none.

Quality of what ML Kit did return is mixed on stylized text: `'utlas'`,
`'HEURSLO'`, `"ENOW THAT'S WHAT I CALL GOYSLOP"` (leading artifact), `"'m evil
monkey no one alive"` (dropped leading I). Restoring parity is not the ceiling.

Four consumers, so text alone is not enough — the editor needs boxes:

1. OCR hint injected into the VLM prompt (what makes the 512px downscale safe
   for text-heavy memes — `docs/on-device-vlm.md`).
2. Duplicate-skip: OCR equality stops one template with different top-text from
   collapsing into a twin.
3. `OCR_RULES` → durable `ocr` tags, and the OCR→entity exact-match path.
4. `MemeTextDetector.kt` → per-line/element boxes for cover / pixelate /
   replace-text in the editor.

## Option 1 — ExecuTorch CRAFT + CRNN (already installed)

`react-native-executorch@0.9.2` — the dependency the app already runs its CLIP
towers, Gemma and Whisper through — **ships an OCR pipeline**. Verified in
`node_modules`:

| fact | source |
|---|---|
| `useOCR`, `useVerticalOCR`, `OCRModule`, `VerticalOCRModule` exported | `src/index.ts:172-195` |
| CRAFT detector + per-alphabet CRNN recognizers | `src/constants/modelRegistry.ts:469` |
| `craft_xnnpack_int8.pte`, `crnn_<alphabet>_xnnpack_fp32.pte` on HF | `src/constants/ocr/models.ts:5-11` |
| returns `{ bbox: {x1,y1,x2,y2}, text, score }[]` | `src/types/ocr.ts:14` |
| `preventLoad` flag, same demand-load pattern as the other models | `src/hooks/computer_vision/useOCR.ts:13-46` |
| library license | MIT (`package.json`) |

- No Google anything. No Play Services, no ML Kit, no new native module, no new
  inference runtime in a process that already peaks near 5 GB.
- Boxes included, so the editor keeps working.
- Same runtime fetch path as Gemma/Whisper already use.
- `models.ocr({ language: 'en' })`; language-parameterized.
- CRAFT+CRNN is the pipeline EasyOCR uses — a known-good classic, not exotic.

Unknown: accuracy on Impact-with-heavy-outline meme text versus ML Kit's Latin
model. That is measurable rather than arguable — see "How to decide" below.

## Option 2 — bundled ML Kit (one-line swap, still Google code)

ML Kit ships a **bundled** variant that statically links the model into the APK
and needs no Play Services: `com.google.mlkit:text-recognition:16.0.1` (plus
`-chinese`, `-devanagari`, `-japanese`, `-korean`). Google's docs are explicit
that the bundled form "makes the feature available immediately upon
installation, without requiring a separate download from Google Play Services."

- Identical API: `TextRecognition.getClient(...)`, `TextBlock`/`Line`/`Element`
  with `boundingBox` and `cornerPoints`. `MemeTextDetector.kt` needs no code
  change, only the dependency line.
- ~4 MB per architecture per language.
- `expo-text-extractor` would still need patching or dropping: it hardcodes the
  GMS artifact and its JS API returns `Promise<string[]>` — text only, no
  geometry (`node_modules/expo-text-extractor/src/index.ts:10`).
- Model and SDK stay proprietary Google, just no longer a Google *service*.
  Model updates then require an app update.

The cheapest way to un-break OCR today. Does not satisfy "no Google".

## Option 3 — PP-OCR via ONNX Runtime (strongest non-Google accuracy)

PaddleOCR PP-OCRv5 mobile, Apache-2.0 including weights, run through ONNX
Runtime (Microsoft, `onnxruntime-android` / `onnxruntime-react-native`).
Detection ~4.6 MB, recognition ~10.4 MB (v4) to ~15.8 MB (v5), angle classifier
under 1 MB. Emits 4-point quads per text line.

Cost: no maintained React Native OCR wrapper exists. RapidOCR is the reference
integration but is Python/CLI-first, so this means a C++/JNI bridge plus NMS
post-processing in our own native module, and a second inference runtime
(~150-200 MB RSS) beside ExecuTorch. Reported, not verified: possible native
library conflicts with the MediaPipe AAR already linked.

Worth it only if Option 1 measures badly.

## Option 4 — let the VLM read the text

Gemma 4 E2B is already loaded and already reads text. Reported benchmarks put
E2B around 41.9% on the IDP leaderboard (UNVERIFIED — treat as directional). It
can emit `box_2d` JSON if prompted, but that is text parsing, not structural
output, and the boxes are loose.

Fails on three counts: no dependable boxes for the editor, quality below what
`OCR_RULES` and duplicate-skip need, and it only runs in the slow describe pass —
OCR has to be available in the fast import pass, which is exactly where the VLM
hint is consumed.

Keep as a fallback hint source. Not a replacement.

## Ruled out

- **Tesseract4Android** — Apache-2.0 and Google-free, but roughly 50-70%
  character accuracy on decorative fonts over busy backgrounds without heavy
  pre-processing. Wrong tool for meme text.
- **docTR / EasyOCR / TrOCR / MMOCR / Surya** — server-side Python. No realistic
  on-device Android path without an export project of its own.
- **Custom OCR export to `.pte`** — no public PP-OCR/DBNet/CRNN/TrOCR ExecuTorch
  export exists, and XNNPACK's dynamic-shape support is limited, which a
  variable-width recognizer and a variable box count both need. Moot anyway,
  since Option 1 is this, already exported and packaged.
- **MediaPipe Tasks Vision** for OCR — it has no text task. (It does have
  Interactive Segmentation, relevant below.)

## Separate, same root cause: subject segmentation

`play-services-mlkit-subject-segmentation` is **unbundled only** — there is no
bundled variant, so the editor's still-image cutout is also dead on a de-Googled
phone. MediaPipe Interactive Segmentation is offline and already a dependency,
but it needs a tap to indicate the subject, so it is not a drop-in for automatic
cutouts. Tracked here so it is not rediscovered later; it does not block OCR.

## Recommendation

**Option 1.** It removes a Google dependency instead of relabelling one, adds no
runtime, returns the boxes the editor needs, and rides loading machinery the app
already has. Option 2 is the fallback if measurement says CRAFT+CRNN is worse on
meme text and quality outweighs the dependency.

## How to decide, not argue

There is a free A/B set sitting in the sidecar: **166 memes already carry ML Kit
OCR text**. Run CRAFT+CRNN over the same images and compare per meme — character
overlap against the stored ML Kit string, plus how often each engine finds text
the other missed. Then push both through the existing harness
(`npm run tagtest`, and the aspect eval's lexical-vs-dense gap in
`docs/`-referenced `evalCore`) to see the search effect rather than the string
diff. Wiring `useOCR` behind the existing `ocr()` seam is small; the measurement
is the real work, and it is the same shape as `npm run recognition`.
