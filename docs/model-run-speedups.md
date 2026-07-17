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

### B1 — Faster primary encoder: MobileCLIP2 / MobileCLIP-S2 (recommended)
The highest-leverage model-run win, and the groundwork is already in the app:
`src/embeddingModels.ts` (`primaryEmbeddingModelFromEnv`), the custom-model path
in `src/embeddings.tsx`, migration guards (index model stamp, exemplar
re-teach/auto-migrate), and `tools/model-export/export_mobileclip_s2.py` +
`.github/workflows/export-models.yml`.

MobileCLIP2 (Apple, TMLR Aug 2025, ~3–15 ms image encode) is the newer, faster
successor to CLIP ViT-B/32 and a drop-in for the same image/text-embedding role.

Execution:
1. Get the export producing an on-device-loadable `.pte` (fp32 first; the export
   contract and shape checks already live in the CI workflow). Prefer MobileCLIP2
   weights over S1 if `open_clip` exposes them; otherwise ship S2 now and treat
   the "2" upgrade as a later weight bump.
2. Publish to the `models-v1` release, point `EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_*`
   at it, build the APK.
3. On device: confirm it loads, measure image/text embed latency vs current CLIP,
   spot-check search quality, then one-time Clear index → re-Index → re-teach
   (the migration guards make this safe, not silent).

### B3 — Quantize the CUSTOM S2/DINO exports (8da4w)
Only the *custom* exports need this — Gemma is already 8da4w. Roughly quarters
their download + RAM, easing model-load latency. Parked upstream on nine failed
CI attempts against executorch 1.0's quant stack; the eager cosine-parity gate
kept broken results from shipping. Do it in `tools/model-export` behind that same
gate, after B1's fp32 path is proven on device.

### B2 — VLM tier tuning (measure before changing the default)
The captioner is inherently the heaviest run, but it's **opt-in** ("AI
descriptions"), so it isn't what a non-opted-in user feels as sluggish. Levers
that don't require a new export:
- The `fast` tier (LFM2.5-VL 450M, XNNPACK) already exists for weaker devices /
  big backlogs and is dramatically lighter than Gemma. Consider auto-suggesting
  it on low-RAM devices instead of defaulting everyone to `max`.
- `LFM2_5_VL_1_6B_QUANTIZED` is available as a middle option if a quality/speed
  point between 450M and Gemma is wanted.
Any default change is a quality tradeoff — gate it on an on-device A/B, don't
flip it blind.

### B4 — QNN / Hexagon NPU spike (Snapdragon only)
Not applicable to the Tensor-G4 test device. Worth a spike **only** if a
Snapdragon device is in the test matrix: add the QNN Runtime Maven dependency +
QNN-variant build, QNN-compile one model (CLIP image is the cheapest to try),
and benchmark NPU vs XNNPACK. Deliver a go/no-go with numbers before committing.

## Why nothing here was changed in this pass
Changing a default model or backend that can't be exercised in a headless
container would ship an unverifiable (and, for B2a/B4, likely wrong or
inapplicable) change. Tier A delivered the measurable in-repo wins; Tier B is
teed up here so it can be executed against a device with the dead ends already
ruled out.
