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

### B2 — VLM model choice (DECIDED: single LFM2.5-VL 1.6B, no tiers)
The captioner is inherently the heaviest run, but it's **opt-in** ("AI
descriptions"), so it isn't what a non-opted-in user feels as sluggish.

**Resolved (July 2026):** the `fast`/`max` tier toggle is removed and the app now
runs a **single** VLM — **LFM2.5-VL 1.6B** — chosen as the fastest turnkey RNE
catalog model that keeps strong caption/OCR quality. Rationale, alternatives, and
the ruled-out runtime/finetune paths are in
[`vlm-model-decision.md`](./vlm-model-decision.md). One caveat carried forward:
LFM runs on XNNPACK (CPU) where Gemma ran on Vulkan (GPU), so the expected speed
win is **still to be confirmed on-device** (P50/P90 including image encode) — do
not book it as a win until measured.

### B4 — QNN / Hexagon NPU spike (Snapdragon only)
Not applicable to the Tensor-G4 test device. Worth a spike **only** if a
Snapdragon device is in the test matrix: add the QNN Runtime Maven dependency +
QNN-variant build, QNN-compile one model (CLIP image is the cheapest to try),
and benchmark NPU vs XNNPACK. Deliver a go/no-go with numbers before committing.

## Status summary
- **B1 (MobileCLIP-S2 swap): shipped to the APK build**, pending on-device
  verification. This IS the model swap — the branch's APK build runs S2 + DINOv2.
- **B2 (VLM): decided — single LFM2.5-VL 1.6B, tiers removed.** The model picker
  is gone (one magic default); LFM2.5-VL 1.6B replaces the Gemma/LFM-450M toggle.
  Speed win vs Gemma-on-Vulkan is expected but must be confirmed on-device. See
  `vlm-model-decision.md`.
- **B3 (quantize custom exports): fp32 ships and works;** int8 stays parked
  behind the cosine gate until a coherent executorch/torch/torchao set passes it.
- **B4 (QNN/NPU): N/A** on the Tensor-G4 test device; revisit only with a
  Snapdragon device in the matrix.

The dead ends (B2a, B4) are ruled out so no device time is wasted on them; the
one real win (B1) is already in the build to test.
