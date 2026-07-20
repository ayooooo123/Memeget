# Model-run speedups (Tier B) — verified findings + execution plan

This is the "make the AI model runs themselves faster" track. Unlike the search
and UI wins (which shipped in-repo with unit tests), every item here changes an
on-device model or the native build, so it can only be **accepted on a real
device / in CI**, not in a headless dev container. This doc records what was
verified against the actual `react-native-executorch@0.9.2` runtime so the team
doesn't chase dead ends, then lists the work in impact order.

## Verified runtime facts (checked against node_modules, July 2026)

- **Gemma 4 E2B multimodal is Vulkan-only on Android.** `GEMMA4_E2B_MM`
  resolves its `modelSource` to `Platform.OS === 'android' ? …/vulkan/… : …/mlx/…`.
  The `…/xnnpack/…` Gemma build is **text-only** (no vision capability). So the
  common "Vulkan underperforms XNNPACK, switch it" advice **does not apply** to
  the captioner — there is no XNNPACK multimodal Gemma to switch to. Flipping it
  would drop vision entirely. (A text-only helper model could run on XNNPACK, but
  that's not what captioning needs.)
- **The Gemma weights are already 8da4w-quantized** (`gemma_4_e2b_vulkan_8da4w.pte`).
  There is no "quantize Gemma" win left to take — it's done upstream.
- **The runtime's model catalog has no MobileCLIP / MobileCLIP2 / SigLIP /
  SmolVLM / FastVLM.** Faster encoders are only reachable via a **custom `.pte`
  export** wired through the existing `EXPO_PUBLIC_MEMEGET_*` seams — exactly the
  path `tools/model-export/` + `docs/embedding-roadmap.md` already set up.
- **QNN / Hexagon NPU is N/A on the primary test device.** The Pixel 9 Pro is a
  Google Tensor G4; its TPU has no ExecuTorch backend (see
  `docs/on-device-vlm.md`). QNN only helps on Snapdragon, so it's a
  device-specific experiment, not a general win.

Net: two of the four "obvious" model-run ideas (Gemma→XNNPACK, quantize Gemma)
are already-done-or-inapplicable, and NPU is hardware-specific. The one broadly
applicable win is a faster **primary encoder** via custom export.

## The work, in impact order

### B1 — Faster primary encoder: MobileCLIP-S2 (SHIPPED to the build; verify on device)
The highest-leverage model-run win, and it is **wired end-to-end** — the "swap"
is done, only the on-device confirmation is outstanding:

- Export: `tools/model-export/export_mobileclip_s2.py` produces the fp32 image
  ([1,3,256,256]→[1,512]) + text ([1,77]→[1,512]) towers + CLIP BPE tokenizer,
  with normalization baked into the graph and an eager cosine-parity gate.
- Publish: `.github/workflows/export-models.yml` ran and the `models-v1`
  release carries `mobileclip_s2_image_xnnpack_fp32.pte` (143 MB),
  `mobileclip_s2_text_xnnpack_fp32.pte` (254 MB), the tokenizer, and
  `dinov2_base_xnnpack_fp32.pte` (346 MB, visual similarity).
- Consume: `.github/workflows/android-apk.yml` sets
  `EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_*` + `EXPO_PUBLIC_MEMEGET_DINOV2_*` at build
  time, so **the APK it builds runs MobileCLIP-S2 as the primary image/text
  encoder** (replacing CLIP ViT-B/32) and DINOv2 for "More like this".
- App plumbing: `src/embeddingModels.ts` (`primaryEmbeddingModelFromEnv`),
  `src/embeddings.tsx` custom-model path, and the migration guards (index model
  stamp warning + exemplar re-teach/auto-migrate) all already handle the swap.

Remaining (device only): install the APK, confirm both towers load (Settings
shows MobileCLIP-S2 as the active model, no "model error"), measure embed
latency vs the old CLIP build, spot-check search quality, then the one-time
Clear index → re-Index → re-teach the mismatch warning prompts for.

MobileCLIP2 (Apple, TMLR Aug 2025) is the newer sibling; treat it as a later
weight bump — swap the `open_clip` model name in the exporter once its weights
are confirmed available and re-run the export workflow. S2 is already a large
step down in latency from ViT-B/32, so it's the safe thing to validate first.

### B3 — Quantize the CUSTOM S2/DINO exports (8da4w)
Only the *custom* exports need this — Gemma is already 8da4w. Roughly quarters
their download + RAM, easing model-load latency. Parked upstream on nine failed
CI attempts against executorch 1.0's quant stack; the eager cosine-parity gate
kept broken results from shipping. Do it in `tools/model-export` behind that same
gate, after B1's fp32 path is proven on device.

### B2 — VLM: single model (Gemma 4 E2B) + per-run speedups (SHIPPED in-repo)
The captioner is the heaviest run and **opt-in** ("AI descriptions").

**Model choice (resolved):** the `fast`/`max` tier toggle and the user-facing model
picker are removed — the app runs a **single** VLM. It is **Gemma 4 E2B multimodal**,
the only vision model in the RNE 0.9.2 catalog with a **GPU** build (Vulkan on
Android, MLX on iOS — its `modelSource` is a two-URL union of exactly those). Every
LFM-VL build is XNNPACK/CPU-only, so "smaller on the GPU" doesn't exist; the GPU
model is the bigger one. Full alternatives table in
[`vlm-model-decision.md`](./vlm-model-decision.md). **Confirmed on-device (July
2026):** Gemma-E2B on Vulkan beats LFM-1.6B on XNNPACK CPU end-to-end — the
"immature Vulkan can lose to XNNPACK on Tensor-G4" worry did not materialize, so
GPU wins on latency as well as quality.

**Per-run speedups (shipped, backend-agnostic):** the cost is **prefill-dominated** —
every `generate()` is stateless and the runtime exposes **no prefix/KV cache**, so the
full ~540-token instruction block + the image's vision tokens are re-prefilled per
meme. `GenerationConfig` has **no max-token field**, but a hard cap is reachable via
`getGeneratedTokenCount()` + `interrupt()` (both on the hook and the class).
- **Per-meme telemetry** — `runVision` (`visionCore.ts`) logs prompt vs generated
  tokens + wall time (`[vlm max] 1234p+150g tok in 900ms`) on both the foreground
  (`vision.tsx`) and headless (`headlessVision.ts`) paths — the prefill/decode split,
  finally measurable on device. Read it before flipping either seam.
- **Output cap** — `runVision` interrupts past `MAX_VLM_OUTPUT_TOKENS` (320), a
  runaway safety net; a truncated reply still parses (flat line format degrades).
- **De-render** — foreground `configure({ outputTokenBatchSize, batchTimeInterval })`
  widens the token-batch window so the `useLLM` hook stops re-running the provider
  ~12×/s during every generation (the describe path never reads the stream).
- **A/B seams (default OFF):** `EXPO_PUBLIC_MEMEGET_VLM_FRAME_WIDTH` (default 512;
  lower → fewer vision tokens) and `EXPO_PUBLIC_MEMEGET_VLM_PROMPT=terse` (~140-token
  prompt; drops the two worked examples). Both cut prefill; gate on an on-device A/B
  since they touch caption quality.

### B4 — QNN / Hexagon NPU spike (Snapdragon only)
Not applicable to the Tensor-G4 test device. Worth a spike **only** if a
Snapdragon device is in the test matrix: add the QNN Runtime Maven dependency +
QNN-variant build, QNN-compile one model (CLIP image is the cheapest to try),
and benchmark NPU vs XNNPACK. Deliver a go/no-go with numbers before committing.

## Status summary
- **B1 (MobileCLIP-S2 swap): shipped to the APK build**, pending on-device
  verification. This IS the model swap — the branch's APK build runs S2 + DINOv2.
- **B2 (VLM): single model + per-run speedups shipped; GPU win confirmed on-device.**
  Tiers/model picker removed (one default = Gemma 4 E2B, the only GPU VLM in the
  catalog). Plus telemetry, output cap, foreground de-render, and `VLM_FRAME_WIDTH` /
  terse-prompt A/B seams. On-device measurement (July 2026) confirms Gemma-on-Vulkan
  beats LFM-1.6B-on-CPU — GPU wins on latency and quality.
- **B3 (quantize custom exports): fp32 ships and works;** int8 stays parked
  behind the cosine gate until a coherent executorch/torch/torchao set passes it.
- **B4 (QNN/NPU): N/A** on the Tensor-G4 test device; revisit only with a
  Snapdragon device in the matrix.

The dead ends (B2a, B4) are ruled out so no device time is wasted on them; the
one real win (B1) is already in the build to test.
