# VLM model decision — one on-device model, no tiers

*Decision doc, July 2026. Companion to `on-device-vlm.md` (how the VLM pass works)
and `model-run-speedups.md` (the broader model-run track). This one records
**which** VLM the app runs, **why**, and the paths that were evaluated and ruled
out — so nobody re-chases them.*

## Decision

Memeget runs **exactly one** on-device vision-language model —
**Gemma 4 E2B multimodal** (`GEMMA4_E2B_MM` from `react-native-executorch`) — and
exposes **no user-facing model picker**. The previous `fast`/`max` toggle
(LFM 450M vs Gemma 4 E2B) is removed, and **LFM is dropped entirely**. A settings
knob that asks the user to choose a model is app bloat; the product should feel
like magic by default.

Gemma is chosen deliberately over the lighter LFM tiers: it is the only catalog
VLM with a **GPU** build (Vulkan on Android, MLX on iOS) and has the strongest
meme-culture knowledge and caption/tag quality. This **reverses an earlier lean**
toward LFM-1.6B-on-CPU — and the on-device measurement (below) has since confirmed
Gemma-on-GPU is also the faster path here, so it wins on both latency and quality.

Wired in `src/visionCore.ts` (`MODEL` is now a single descriptor). The tier
concept (`VisionQuality`, `QUALITY_KEY`, `DEFAULT_QUALITY`) is gone from
`visionCore.ts`, `vision.tsx`, `headlessVision.ts`, `backgroundTask.ts`, and the
Settings screen.

## Why Gemma 4 E2B

The task is **caption + in-image OCR + subject/tag extraction for search**, and
the VLM is an *enrichment* pass on top of CLIP — it doesn't have to carry
retrieval on its own. Against that job, Gemma 4 E2B is the single pick:

- **It's the only GPU VLM in the catalog.** `GEMMA4_E2B_MM` runs on Vulkan
  (Android) / MLX (iOS); every LFM-VL build is XNNPACK/CPU-only. Choosing Gemma is
  the only way to actually use the GPU for captioning.
- **Highest quality of the two turnkey options.** Broader meme-culture *naming*,
  strong captions/tags, and it is also multimodal-audio-capable. It was the prior
  `max` default for exactly this reason.
- **Turnkey.** Already in the RNE 0.9.2 catalog with working vision
  (`useLLM({ capabilities: ['vision'] })`) and a hand-engineered Vulkan export
  (SWM PR #1162). No custom export, no CI pipeline — a one-line descriptor.
- **Prefill cost is mitigated** by the per-run speedups in `model-run-speedups.md`
  (output cap, batch de-render, and the frame-width / terse-prompt A/B seams) plus
  the existing ML Kit OCR hints and the CLIP zero-shot grounding line.

### ✅ Verified on-device

Gemma runs on the **Vulkan GPU** on Android. The open question was whether
ExecuTorch's immature Vulkan delegate would trail XNNPACK on the Pixel 9 Pro
(Tensor G4 / Mali-G715) — i.e. whether CPU-LFM might actually be faster.
**Measured on-device (maintainer-confirmed, July 2026): it does not.** Gemma-E2B
on Vulkan beats LFM2.5-VL 1.6B on XNNPACK CPU end-to-end, so the GPU choice wins
on **both** latency and quality — no revert needed. (LFM tiers stay documented
only as a fallback if a future device regresses.)

## Alternatives weighed (on-device VLMs)

Only **two** generative VLMs run with working vision in `react-native-executorch`
today: **Gemma E2B** and **LFM2-VL**. Everything else is disqualified by the
runtime, not by quality:

| Model | Status for this stack |
|---|---|
| **Gemma 4 / 3n E2B** | **CHOSEN.** The one catalog VLM with a GPU (Vulkan/MLX) build and the highest meme-culture knowledge. Heavier, and its MatFormer + Per-Layer-Embeddings design is export-hostile, but it's turnkey and GPU-backed. |
| **LFM2-VL 1.6B / 450M** | The prior `max`-alt / `fast` tiers. CPU-only (XNNPACK); lighter and possibly faster on immature-Vulkan devices, but lower quality. **Dropped** — kept only as a documented revert target. |
| **FastVLM** (Apple) | Best latency-per-quality in the class, but CoreML/MLX only — no ExecuTorch/Android/Vulkan path. Off the table. |
| **Qwen3-VL 2B/4B** | Likely edges Gemma on document-OCR; an ExecuTorch vision export just landed in `optimum-executorch` (PR #214). **Not in the RNE catalog, not Vulkan-validated.** The one to watch as a future upgrade if OCR becomes the bottleneck. |
| **SmolVLM2 / moondream 2·3 / MiniCPM-V / InternVL / PaliGemma 2** | No working ExecuTorch vision export. Disqualified regardless of quality. |

## GPU vs CPU — why "a smaller model on the GPU" isn't an option

The obvious instinct is "run a small model on the GPU for speed." On this stack
that combination **does not exist** — there is no small Vulkan VLM. (An earlier
worry that GPU might not even be the faster path was **disproven on-device**: see
the verified item above — Gemma-on-Vulkan beat CPU-LFM.) Facts, checked against
the installed RNE 0.9.2 package and upstream ExecuTorch docs:

- **The only GPU (Vulkan) VLM in RNE is Gemma E2B.** `GEMMA4_E2B_MM` resolves to
  `gemma_4_e2b_vulkan_8da4w.pte` on Android (`Platform.OS === 'android' ?
  GEMMA4_E2B_VULKAN_MM : GEMMA4_E2B_MLX_MM`). **Every** LFM2-VL variant (450M,
  1.6B) ships XNNPACK-only — there is no Vulkan LFM build. So the GPU model is the
  *bigger* one; going smaller means going to CPU.
- **ExecuTorch's Vulkan delegate is immature.** It's a partitioner-based backend
  with **CPU fallback** (not full-graph); RNE's own FAQ says Vulkan "operator
  support is very limited meaning that the resulting performance is often
  **inferior to XNNPACK**," and ExecuTorch's LLM docs warn a partially-lowered
  model is "very slow due to the high amount of delegate blobs… transfer to and
  from the GPU for each subgraph." Gemma runs well on Vulkan only because Software
  Mansion hand-engineered that one export (PR #1162).
- **No NPU path on the test device.** Pixel 9 Pro = Tensor G4 / Mali-G715. Vulkan
  (Mali GPU) is the *only* ExecuTorch GPU option — there is no ExecuTorch backend
  for Google's Tensor TPU/NPU, and the Qualcomm (QNN) / MediaTek backends don't
  apply to Pixel. The only thing that genuinely accelerates the Mali GPU is Google
  **LiteRT-LM (OpenCL / ML Drift)** — a different runtime, i.e. leaving RNE.
- **There is no vision-token-budget knob.** RNE exposes `getVisualTokenCount()`
  read-only (context math); the only `generationConfig` levers are temperature /
  topP / minP / repetitionPenalty / batching. The "selectable 70–1120 vision
  tokens" some sources cite is not reachable from the app.

**Pick two, never three:**

| Want | Turnkey model | Backend |
|---|---|---|
| Smaller + turnkey | LFM2-VL 1.6B / 450M | XNNPACK **CPU** |
| **GPU + turnkey (shipped)** | **Gemma E2B** (bigger) | Vulkan **GPU** |
| Smaller + GPU | custom Vulkan export — R&D (see appendix) | Vulkan (partial) |

**Conclusion (confirmed):** ship the **GPU model (Gemma E2B)**. It wins on
caption/tag quality *and* — per the on-device measurement — on latency over the
CPU-LFM tier the analysis had hedged toward. The "immature Vulkan can lose to
XNNPACK" worry did not materialize on the Mali-G715 target; revisit only if a
future device regresses.

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

GPU / Vulkan:
- ExecuTorch Vulkan backend overview: https://github.com/pytorch/executorch/blob/main/docs/source/backends/vulkan/vulkan-overview.md
- RNE Vulkan Gemma PR: https://github.com/software-mansion/react-native-executorch/pull/1162
- RNE FAQ (Vulkan vs XNNPACK): https://docs.swmansion.com/react-native-executorch/docs/fundamentals/frequently-asked-questions
- ExecuTorch upstream LFM2 example (XNNPACK/MLX only): https://github.com/pytorch/executorch/blob/main/examples/models/lfm2/README.md
- LFM2 export control-flow blocker: https://github.com/huggingface/transformers/issues/39436
- ExecuTorch backend-for-Pixel question (unanswered): https://github.com/pytorch/executorch/issues/15670
- SmolVLM2: https://huggingface.co/blog/smolvlm2

---

## Appendix: SmolVLM2-256M Vulkan lowering-feasibility spike

**Purpose.** Answer *one* question cheaply before anyone commits to the
"smaller + GPU" R&D path: **can a small VLM's graph actually lower to the
ExecuTorch Vulkan delegate without shattering into CPU-fallback subgraphs?** This
is a go/no-go probe, not a productionization. Timebox: ~1 day. Do NOT wire
anything into the app during the spike.

**Status (SmolVLM Vulkan weights):** no prebuilt Vulkan — or any ExecuTorch —
SmolVLM `.pte` is published; executorch-community / HF host XNNPACK/CoreML builds
at most, and optimum-executorch has no `vulkan` recipe. A Vulkan SmolVLM would
have to be self-exported, which is exactly what this spike probes. Confirmed
July 2026; not shipped.

**Why SmolVLM2-256M is the target.** It's the smallest credible caption/OCR VLM
(93M SigLIP-B/16 encoder + SmolLM2-135M), and critically its vision encoder is a
**plain fixed-resolution SigLIP-B/16** — unlike LFM2-VL's dynamic SigLIP2-NaFlex,
whose variable shapes are exactly what fragments a Vulkan partition. If SmolVLM2
can't lower cleanly, LFM2-VL almost certainly can't either, and the whole
GPU-small-VLM idea is dead. If it *can*, SmolVLM2 itself becomes a candidate.

**Steps.**
1. **Export both halves to Vulkan** via `optimum-executorch` /
   `executorch` export with `--task multimodal-text-to-text` and the Vulkan
   backend (PT2E quant, 4-bit weight / 8-bit activation per the Vulkan doc).
   Export the **vision encoder** and the **text decoder** as separate `.pte`s
   (EarlyFusion split, mirroring the upstream Gemma3 multimodal runner).
2. **Inspect partitioner tagging — the actual measurement.** After
   `to_edge_transform_and_lower(..., partitioner=[VulkanPartitioner()])`, dump the
   lowered graph and **count delegate boundaries**: how many ops landed on Vulkan
   vs fell back to CPU, and how many distinct `executorch_call_delegate` blobs
   result. Many blobs = many CPU⇄GPU transfers = the documented "very slow" mode.
   The vision encoder is the risk: if its conv/attention ops don't lower, it runs
   on CPU regardless and there's no GPU win.
3. **Micro-benchmark, if it lowers at all.** On the Pixel (Mali-G715), run the
   Vulkan `.pte` vs an XNNPACK export of the *same* model on ~20 representative
   memes; record image-encode + prefill + decode P50/P90. The bar is not "works"
   — it's "beats XNNPACK on the same model on this GPU."

**Decision gate.**
- **No-go** if: the vision encoder won't lower to Vulkan (runs CPU anyway), the
  graph shatters into many delegate blobs, or Vulkan loses to XNNPACK on Mali.
  → Record the delegate-boundary counts here and close the GPU track.
- **Go** (rare) only if: mostly-full-graph Vulkan delegation *and* a measured win
  over XNNPACK on the target device. Then evaluate SmolVLM2-256M quality on the
  golden meme eval before considering it as the shipped model.

**Explicitly out of scope for the spike:** app wiring, quality tuning, any
LFM2-VL Vulkan attempt (blocked upstream — see the control-flow issue above),
and iOS. Point RNE at a custom `.pte` via `useLLM({ modelSource, tokenizerSource,
tokenizerConfigSource })` only *after* a Go.
