# On-device meme understanding (LFM2.5-VL 1.6B)

Memeget describes memes with **LFM2.5-VL 1.6B (multimodal)**, Liquid AI's
on-device vision-language model (SigLIP2 vision encoder + LFM2 backbone), running
fully on-device through the **same `react-native-executorch` runtime** that
already powers CLIP (LFM runs on the XNNPACK backend). CLIP stays the fast
embedding / similarity / teach-by-example backbone; the VLM is an **enrichment
pass** that adds a human caption, the literal text, and open-vocabulary tags that
CLIP's fixed 97-label vocabulary can't produce. Nothing leaves the device.

**One model, no tiers.** There is a single VLM and no user-facing model picker —
the app's job is to feel like magic, not to make people choose. LFM2.5-VL 1.6B
was chosen as the fastest turnkey option in the RNE catalog that still keeps
strong caption/OCR quality (helped by the CLIP grounding + ML Kit OCR hints fed
into the prompt). The rationale, the alternatives weighed, and the ruled-out
paths are recorded in [`vlm-model-decision.md`](./vlm-model-decision.md).

## Architecture

```
visionCore.ts      React-free core: model constants, prompt, reply parse
                   (flat "LABEL: value" format + JSON/bare fallback),
                   throttle logic, rate mapping, setting keys.
        │
        ├── vision.tsx ............ FOREGROUND path: useLLM hook + in-app paced
        │                          loop + provider/UI state (a React context).
        │
        └── headlessVision.ts ..... HEADLESS path: LLMModule CLASS (no React),
                                    loadable in a background JS context.
                   │
indexer.ts         enrichLibrary (burst) · enrichNextMeme (one) ·
                   runBackgroundSession (bounded) — all drive a VisionEnricher,
                   with duplicate-skip + telemetry.
                   │
backgroundTask.ts  expo-background-task / TaskManager task that runs
                   runBackgroundSession headlessly, throttled by power state.
modules/memeget-bg native module: battery/thermal reads + keep-alive FGS.
```

The key enabler for true background work: `react-native-executorch` exports
`LLMModule` (a **class**, not just the `useLLM` hook), so the model can be
instantiated and run **outside the React tree** — which is what an OS-scheduled
background task needs.

## Efficiency choices

- **512px frames** — both models resample to their vision encoder's working
  resolution anyway (Gemma to a fixed square, LFM by tiling over 512); capping
  keeps decode/transcode — and, for LFM, prefill — bounded.
- **OCR hint** — ML Kit's already-extracted text is fed into the prompt so the
  small model doesn't re-read small text; this is what makes the 512 downscale
  safe for text-heavy memes.
- **Duplicate-skip** — a pending meme whose CLIP vector matches an already-
  described one (cosine ≥ 0.99) **and** whose OCR text matches copies that
  result instead of re-running the model. The OCR check prevents merging the
  same template with different top-text.
- **Terse prompt**, **resident model**, **concurrency = 1** (a mutex shared by
  the burst and trickle paths).

## Background processing

- **Foreground / app-open**: `vision.tsx` runs a paced loop; intensity slider →
  memes/hour. Throttled by battery/thermal via `modules/memeget-bg`.
- **App backgrounded but alive**: the Android keep-alive foreground service
  holds the process so the loop keeps going.
- **App closed**: `backgroundTask.ts` is scheduled (WorkManager / BGTaskScheduler
  via `expo-background-task`) to run bounded, resumable headless sessions.

Throttles (persisted, in Settings): only-while-charging, pause-when-warm,
pause-on-low-battery.

## Status

| Phase | What | State |
|------|------|-------|
| 0 | 512 downscale · OCR hint · concurrency mutex | shipped |
| 1 | In-app paced loop + intensity slider | shipped |
| 1.5 | Duplicate-skip · telemetry · terser output · retag fix | shipped |
| 2 | Keep-alive FGS + battery/thermal throttles | shipped (native unverified) |
| 3 | Headless `LLMModule` + OS-scheduled background sessions | shipped (native + on-device behavior unverified) |

### Not done / not applicable

- **NPU fast-lane (Qualcomm QNN/Hexagon)** — only helps on Snapdragon; the
  primary test device (Pixel 9 Pro) is **Google Tensor G4**, whose TPU has no
  ExecuTorch backend. Not pursued.
- **iOS BGProcessingTask** is reached via `expo-background-task`; iOS background
  scheduling is opportunistic (system-decided, charger/overnight) — treat as a
  top-up, not the primary indexer.

## Validating the unverified native pieces

The TypeScript is typechecked. The native module (`modules/memeget-bg`), the
background-task scheduling, and **whether a ~hundreds-of-MB model loads/runs
inside the OS background window** all need an on-device build:

```bash
npx expo prebuild --clean      # generates android/, autolinks modules/memeget-bg + plugins
npx expo run:android           # build + install the dev client
```

Then: enable AI descriptions, turn on Background processing, background the app
(and/or lock the phone on a charger), and confirm `vision_state` advances. Watch
for: local-module autolinking, the `FOREGROUND_SERVICE_DATA_SYNC` manifest
merge, and memory pressure when the model loads in the background context.
Sessions are bounded + resumable, so a killed session just resumes next time.
