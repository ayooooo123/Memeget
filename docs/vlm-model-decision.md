# VLM model decision — one on-device model, no tiers

*Decision doc, July 2026. Companion to `on-device-vlm.md` (how the VLM pass works)
and `model-run-speedups.md` (the broader model-run track). This one records
**which** VLM the app runs, **why**, and the paths that were evaluated and ruled
out — so nobody re-chases them.*

## Decision

Memeget runs **exactly one** on-device vision-language model —
**LFM2.5-VL 1.6B** (`LFM2_5_VL_1_6B_QUANTIZED` from `react-native-executorch`) —
and exposes **no user-facing model picker**. The previous `fast`/`max` toggle
(LFM 450M vs Gemma 4 E2B) is removed. A settings knob that asks the user to
choose a model is app bloat; the product should feel like magic by default.

Wired in `src/visionCore.ts` (`MODEL` is now a single descriptor). The tier
concept (`VisionQuality`, `QUALITY_KEY`, `DEFAULT_QUALITY`) is gone from
`visionCore.ts`, `vision.tsx`, `headlessVision.ts`, `backgroundTask.ts`, and the
Settings screen.

## Why LFM2.5-VL 1.6B

The task is **caption + in-image OCR + subject/tag extraction for search**, and
the VLM is an *enrichment* pass on top of CLIP — it doesn't have to carry
retrieval on its own. Against that job, LFM2.5-VL 1.6B is the best single pick:

- **It's faster than Gemma E2B and still smart.** 1.6B params (LFM2 backbone +
  ~400M SigLIP2 encoder) vs Gemma E2B's ~5B raw params (MatFormer/PLE overhead).
  Liquid positions LFM2-VL among the fastest on-device VLMs; strong English OCR,
  low hallucination.
- **It's turnkey.** It's already in the RNE 0.9.2 catalog with working vision
  (`useLLM({ capabilities: ['vision'] })`). No custom export, no CI pipeline —
  a one-line model swap.
- **The quality gap vs Gemma is cushioned** by what already feeds the prompt: ML
  Kit OCR hints (so the VLM doesn't re-read small text off a 512px downscale) and
  the CLIP zero-shot grounding line (format/character/emotion guesses). Gemma's
  main edge was broader meme-culture *naming*; grounding already injects most of
  that.

### ⚠️ Open verification item

Gemma E2B ran on the **Vulkan GPU** backend; LFM runs on **XNNPACK (CPU)**. A
smaller model on CPU is *not automatically* faster than a bigger one on GPU —
GPU delegation helps VLM prefill a lot (image tokens dominate). So the speed win
is **expected but unproven in this container** — per `model-run-speedups.md`, a
model-run change can only be accepted on a real device / in CI. **Before calling
this a latency win, measure P50/P90 end-to-end (including image encode) on the
target Android device tiers, LFM2.5-VL 1.6B vs the old Gemma build**, alongside a
quality spot-check (OCR exactness on text-heavy memes, caption usefulness, tag
recall). If LFM loses on quality-per-latency on your device floor, the fallback
is LFM 450M (fastest, lower quality) or reverting to Gemma.

## Alternatives weighed (on-device VLMs)

Only **two** generative VLMs run with working vision in `react-native-executorch`
today: **Gemma E2B** and **LFM2-VL**. Everything else is disqualified by the
runtime, not by quality:

| Model | Status for this stack |
|---|---|
| **Gemma 4 / 3n E2B** | The prior default. Higher meme-culture knowledge, but heavier/slower and its MatFormer + Per-Layer-Embeddings design is export-hostile. Kept as a possible revert target. |
| **LFM 450M** | The prior `fast` tier. Faster still, but a noticeable quality drop. Fallback only. |
| **FastVLM** (Apple) | Best latency-per-quality in the class, but CoreML/MLX only — no ExecuTorch/Android/Vulkan path. Off the table. |
| **Qwen3-VL 2B/4B** | Likely edges Gemma on document-OCR; an ExecuTorch vision export just landed in `optimum-executorch` (PR #214). **Not in the RNE catalog, not Vulkan-validated.** The one to watch as a future upgrade if OCR becomes the bottleneck. |
| **SmolVLM2 / moondream 2·3 / MiniCPM-V / InternVL / PaliGemma 2** | No working ExecuTorch vision export. Disqualified regardless of quality. |

## Ruled out: switching runtime to ONNX Runtime

Evaluated and rejected. The blocker is structural, not tuning:

- The maintained **`onnxruntime-react-native`** package does **plain
  `session.run()` inference only** — no generative decode loop, so it cannot run
  a VLM as-is.
- The stack that *can* run vision VLMs — **`onnxruntime-genai`** (Phi-3.5-vision,
  etc.) — has **no React Native binding**, a build-your-own Android AAR, no iOS
  release, and vision-on-NPU still in flux.
- You'd be hand-building and maintaining a native bridge that Software Mansion
  already gives you for free via ExecuTorch — and likely changing models
  (Gemma 3n ONNX export is community/web-oriented and reported broken).
- Quantization is ~parity (ONNX int4 `MatMulNBits` vs ExecuTorch 8da4w), so no
  win there either.

If **NPU acceleration** is ever the real motivation, use ExecuTorch's *own*
QNN/Hexagon backend (same NPU, no runtime swap) — though that's moot on the
Tensor-G4 test device, whose TPU has no ExecuTorch backend.

## Ruled out (for now): finetuning / RL the generative VLM

Making the VLM "smarter about memes" is attractive, and the *training* is cheap —
Unsloth/TRL/LLaMA-Factory all LoRA-finetune a Gemma/Qwen-VL on a single
12–16 GB GPU, and GRPO is a clean fit because the reward is machine-checkable
(caption↔image CLIP similarity using our own MobileCLIP tower as the reward
model, format/schema adherence, OCR exact-match).

**The blocker is deployment, not training.** This repo's export pipeline
(`tools/model-export/`) only handles **encoder-only** `.pte` exports (single
tensor → embedding: MobileCLIP/DINOv2). Re-exporting a finetuned **generative
multimodal** model into an RNE-loadable 8da4w `.pte` — split encoder+decoder
graphs, KV-cache, image-token stitching, matching SWM's exact catalog contract —
is a multi-week, high-risk project, and Gemma 3n's MatFormer/PLE is notoriously
export-hostile.

Highest-ROI ways to make the model "smarter about memes" (aligns with
`memedepot-finetune.md`), in order:

1. **Prompt + grounding + distilled meme-knowledge** — improve what the VLM
   emits with *no `.pte` change*. Distill template/character knowledge from a
   frontier model into the CLIP grounding line. Ships today, zero deployment risk.
2. **LoRA-finetune the CLIP *encoder* + re-export** — the drop-in this repo is
   already architected for (`export_mobileclip_s2.py` contract, env-var swap).
3. **Eval harness first** — a meme finetune is easy to silently make worse; this
   gates everything.
4. **Distillation-SFT of the generative VLM** — highest ceiling, blocked by the
   re-export wall above. Only if we commit to owning that pipeline.
5. **GRPO/DPO RL** — a polish pass *after* distillation-SFT, downstream of the
   same blocker. Not a first move.

## Sources

On-device VLM landscape / ExecuTorch support:
- ExecuTorch multimodal RFC: https://github.com/pytorch/executorch/issues/12913
- optimum-executorch Qwen3-VL export PR: https://github.com/huggingface/optimum-executorch/pull/214
- RNE releases + VLM catalog: https://github.com/software-mansion/react-native-executorch/releases
- RNE LFM2-VL model: https://huggingface.co/software-mansion/react-native-executorch-lfm-2.5
- LFM2-VL: https://www.liquid.ai/blog/lfm2-vl-efficient-vision-language-models
- FastVLM (Apple): https://machinelearning.apple.com/research/fast-vision-language-models

ONNX Runtime:
- onnxruntime-react-native: https://www.npmjs.com/package/onnxruntime-react-native
- onnxruntime-genai (models/platforms): https://github.com/microsoft/onnxruntime-genai
- NNAPI deprecation: https://github.com/microsoft/onnxruntime/issues/23565
- QNN EP + dynamic-shape limit: https://github.com/microsoft/onnxruntime/issues/23832

Finetune / RL:
- Unsloth Gemma 3n finetune: https://unsloth.ai/blog/gemma-3n
- Unsloth Vision RL (VLM GRPO): https://www.unsloth.ai/blog/vision-rl
- CLIP-reward RL captioning: https://arxiv.org/abs/2205.13115
- DPO for VLMs: https://huggingface.co/blog/dpo_vlm
- Distillation data (ShareGPT-4V/ALLaVA): https://arxiv.org/pdf/2402.11684
- MemeCap: https://arxiv.org/abs/2305.13703
- optimum-executorch (8da4w export): https://github.com/huggingface/optimum-executorch
