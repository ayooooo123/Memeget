# On-device learning: dedicated face/meme models that self-improve

> Design doc. How Memeget can run a few tiny, self-improving classifiers on the
> phone for near-perfect classification and tagging — including a dedicated face
> recognizer — and where (the rare cases) real model fine-tuning has to happen
> off-device.

## TL;DR

Memeget already *is* a tiny self-improving on-device model:
`buildExemplarHeads()` (`src/indexer.ts`) trains a per-label logistic-regression
head with `trainHead()` (`src/embeddings.tsx`) on top of frozen CLIP features,
in tens of milliseconds, from the user's taught examples. The `exemplars` table
already stores positives **and** negatives (`is_positive`).

This doc extends that pattern in two directions:

1. **A dedicated face pipeline** — face detection → a small face-embedding model
   → per-person heads (the one place a separate model clearly beats CLIP).
2. **A fully autonomous self-improving loop** — everyday usage and
   high-confidence predictions become training data, heads retrain in the
   background, and the index is re-tagged — guarded so it improves instead of
   drifting.

Nothing here requires writing custom GPU/compute kernels. ExecuTorch's backends
already provide tuned inference kernels; the leverage is in **specialized
fine-tuned models + tiny trainable heads**, not hand-written ops.

---

## 1. The constraint that shapes the whole design

There are two layers, and **only one of them can train on a phone.**

| Layer | What it does | Trains on device? | Size |
|---|---|---|---|
| **Encoder** (CLIP, face net) | pixels → vector | **No.** `react-native-executorch` is inference-only; ExecuTorch on-device training is not exposed in RN. Encoder fine-tuning is an off-device job. | 5–350 MB |
| **Head** (linear / logistic) | vector → label probability | **Yes.** Pure vector math in JS, ~tens of ms. This is `trainHead()`. | ~2 KB / label |

So the strategy is fixed by this table:

> **Freeze a strong encoder. Train many tiny heads on-device. Fine-tune the
> encoder off-device only when the heads provably plateau.**

The "few tiny tiny models on device" the user wants are **bags of linear heads**,
one bag per domain (meme formats, faces, NSFW, art-style, …). A 512-dim head is
~2 KB, so a hundred of them is a rounding error next to the encoder weights.

Personalization lives entirely in the head layer. The encoder gets *generically*
better for everyone via off-device releases; the heads get *personally* better
for each user, on-device, continuously.

---

## 2. Current architecture (what we build on)

```
image ──► toJpeg ──► CLIP image encoder ──► 512-d vector ─┬─► zero-shot vs label prompts (classifyImage)
                                                          ├─► per-label LR heads      (buildExemplarHeads/headProb)
                                                          └─► stored as float32 blob  (memes.embedding)
OCR text ──────────────────────────────────────────────────► OCR rules (ocrTags)
```

Key existing pieces this design reuses unchanged:

- `trainHead(label, category, positives, negatives, opts)` — balanced, L2-reg
  logistic regression. Domain-agnostic: feed it *any* embeddings.
- `buildExemplarHeads()` — groups exemplars by label, mean-centers against a
  library background sample, oversamples explicit "not this" corrections.
- `retagAll()` — re-applies current knowledge to every meme **reusing stored
  embeddings** (no re-embedding). This is what makes background retagging cheap.
- `exemplars` table with `is_positive`, `source_uri`, `associations`.

---

## 3. Dedicated face pipeline (new)

CLIP answers "is there a person" well and "*which specific person*" badly. The
standard, well-trodden stack:

### 3.1 Components

1. **Detect + crop.** ML Kit face detection (already on-device for OCR) or
   `expo-face-detector` → bounding boxes → crop each face.
2. **Embed.** A small **ArcFace / MobileFaceNet** (~5–25 MB, 512-d) exported to
   ExecuTorch `.pte`. Same-person crops land ~0.6+ cosine, different people
   ~0.2 — far cleaner separation than CLIP gives on faces.
3. **Classify.** Reuse `trainHead()` **verbatim** on face embeddings. "Tony
   Soprano" becomes a head trained on face-crop vectors instead of full-image
   CLIP vectors.

```
meme ─► detect faces ─► for each crop ─► face encoder ─► 512-d face vec ─► per-person heads
                                                          └─► stored: face_vectors(meme_id, bbox, vec)
```

### 3.2 Schema additions

```sql
-- one row per detected face (a meme can have several)
CREATE TABLE face_vectors (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  meme_id   INTEGER NOT NULL,
  bbox      TEXT NOT NULL,           -- JSON [x,y,w,h] for crop/preview
  vector    BLOB NOT NULL,           -- float32 face embedding
  FOREIGN KEY (meme_id) REFERENCES memes(id) ON DELETE CASCADE
);

-- person exemplars are just exemplars with category='person' but trained on
-- face vectors; either reuse `exemplars` with a `space` column, or add:
ALTER TABLE exemplars ADD COLUMN space TEXT NOT NULL DEFAULT 'clip'; -- 'clip' | 'face'
```

A `buildFaceHeads()` mirrors `buildExemplarHeads()` but pulls `space='face'`
exemplars and uses a face-vector background sample for mean-centering. At tag
time, run each meme's face vectors through the person heads and merge results
into the existing `Tag[]` with `source: 'face'` (rank it above zero-shot prompts,
below OCR — see `mergeTags`).

### 3.3 Cost

- One extra small model download (gate it behind a "Recognize people" toggle so
  privacy-conscious users can stay CLIP-only).
- Face detection + embedding adds ~tens of ms per face during indexing.
- Heads and storage are negligible.

---

## 4. Fully autonomous self-improving loop

The user chose **fully autonomous**: in addition to explicit teaching, the app
turns usage *and its own high-confidence predictions* into training data
(pseudo-labeling), retrains in the background, and re-tags. This is the most
"self-improving" mode and also the one most prone to drift — so the safeguards
in §4.3 are **not optional**; they are what make autonomy safe.

### 4.1 Signal sources → training data

| Signal | Becomes | Weight |
|---|---|---|
| User taps "wrong" / removes a tag | negative exemplar (`is_positive=0`) | **1.0** (ground truth) |
| User taps "correct" / teaches | positive exemplar | **1.0** (ground truth) |
| User opens a meme from a tag's search results | weak positive for that tag | ~0.2 |
| Head predicts a label with prob ≥ `PSEUDO_HI` (e.g. 0.97) | **pseudo-positive** | ~0.3, capped |
| Head predicts ≤ `PSEUDO_LO` (e.g. 0.02) on a meme tagged elsewhere | pseudo-negative | ~0.3, capped |

Add a `weight REAL DEFAULT 1` and `origin TEXT` (`'explicit'|'implicit'|'pseudo'`)
column to `exemplars`, and thread `weight` into `trainHead`'s per-sample class
weighting (it already weights by class; extend to per-sample).

### 4.2 Trigger (background, debounced)

Do **not** retrain per event. Accumulate, then retrain on a cheap trigger:

```
on new exemplar/feedback:
  pending[label] += 1
  if pending[label] >= RETRAIN_THRESHOLD (e.g. 8)  OR  app goes idle/background:
     scheduleRetrain(label)

scheduleRetrain(label):           # runs off the UI thread / on idle
  candidate = trainHead(label, ...current exemplars incl. pseudo...)
  if passesHoldOut(candidate, label):   # §4.3
     commitHead(candidate)              # versioned write
     retagAll() limited to affected memes
  else:
     discard candidate, keep current head, log rejection
```

`retagAll()` already reuses stored embeddings, so a background re-tag costs only
vector math — no re-embedding, no model load.

### 4.3 Drift safeguards (the part that makes autonomy safe)

Autonomous pseudo-labeling fails in a predictable way: the head gets confident,
labels more borderline memes as positives, trains on its own confident mistakes,
gets *more* confident, and the boundary collapses (confirmation bias). Guards:

1. **Hold-out gate.** Keep ~20% of each label's **explicit** exemplars out of
   training. After a retrain, score the candidate head on this hold-out. If
   accuracy/F1 drops vs the live head, **reject the candidate** and keep the old
   one. This single check is what lets the loop run unsupervised.
2. **Pseudo-label cap.** Pseudo-positives may never exceed, say, 50% of a label's
   training positives — the user's real examples must stay the majority. New
   pseudo-labels only from predictions **far** from the boundary (`PSEUDO_HI`).
3. **Confidence floor for implicit signals** so one accidental tap can't poison a
   head; require corroboration (e.g. 2 implicit signals) before it counts.
4. **Per-label negative cap + balance** (extends the existing `negBoost`
   oversampling at `indexer.ts`) so the boundary stays balanced.
5. **Versioned heads + rollback.** Persist weights with a generation id and the
   hold-out score:
   ```sql
   CREATE TABLE label_heads (
     label      TEXT NOT NULL,
     space      TEXT NOT NULL DEFAULT 'clip',
     generation INTEGER NOT NULL,
     w          BLOB NOT NULL,
     b          REAL NOT NULL,
     holdout    REAL NOT NULL,
     created_at INTEGER NOT NULL,
     PRIMARY KEY (label, space, generation)
   );
   ```
   Keep the last N generations; if a label's tagging quality regresses, revert.
6. **Quarantine, don't auto-delete.** Pseudo-labels are tagged `origin='pseudo'`
   and are always removable in bulk, so the user can reset to "explicit only"
   and rebuild clean.
7. **Decay.** Age out stale implicit/pseudo exemplars so the model tracks the
   user's evolving library instead of ossifying around early guesses.

### 4.4 Why this stays "near-perfect"

- Explicit ground truth always dominates training and is never overruled by
  pseudo-labels (cap + hold-out).
- OCR rules (`OCR_RULES`) remain the 100%-precision tier for watermarked
  formats — autonomy never touches them.
- The hold-out gate makes every autonomous update *monotonic on the metric that
  matters*: a head can only ship if it didn't get worse on held-out truth.

---

## 5. Off-device encoder fine-tuning (last resort)

Only after heads plateau on a domain (the hold-out F1 stops climbing no matter
how many exemplars are added) does it pay to fine-tune the encoder itself.

### Recipe

1. **Collect** a labeled meme/face corpus off-device.
2. **LoRA-fine-tune** the CLIP (or face) encoder with PyTorch + `peft`. LoRA
   keeps it cheap and the adapter small.
3. **Merge** the adapter into the base weights.
4. **Export to ExecuTorch:**
   ```python
   ep = torch.export.export(model, example_inputs)
   edge = to_edge(ep)
   exec_prog = edge.to_executorch()
   with open("clip_image_meme.pte", "wb") as f:
       f.write(exec_prog.buffer)
   ```
5. **Ship** the `.pte` in the APK and swap the single constant in
   `src/embeddings.tsx`:
   ```ts
   const IMAGE_MODEL = CLIP_VIT_BASE_PATCH32_IMAGE; // ← swap to the fine-tuned .pte
   ```
   Because everything downstream is backbone-agnostic, this is the only code
   change. (Re-embedding the existing library is required, since vectors from a
   new encoder live in a new space — bump an `embedding_version` and re-index.)

### What you cannot do

Ship *per-user* fine-tuned encoders — there's no on-device backprop. That's
exactly why personalization is carried by the head layer. The encoder improves
generically for everyone via releases; the heads improve personally per user,
continuously, on the phone.

### A stronger frozen backbone first

Before LoRA, just swapping ViT-B/32 for **ViT-L/14 or SigLIP** (still frozen,
still zero training) is usually the single biggest accuracy jump and needs only
the same constant swap + a larger download.

---

## 6. Runtime, acceleration & sparse experts

Device hardware is the real ceiling. This section is about putting each piece of
work on the *right* silicon, and about the architecture that lets a phone behave
like a much bigger multi-domain model without holding one in memory.

### 6.1 Put each job on the right silicon

The mistake is to reach for "custom kernels" for everything. The encoder is
heavy matmuls that ExecuTorch's delegates **already** run on accelerated
hardware; the gaps are elsewhere.

| Work | Runtime | Why |
|---|---|---|
| Encode image/face → vector | ExecuTorch delegate — **NPU** (Core ML/ANE on iOS, QNN on Snapdragon) or Vulkan GPU | heavy, already tuned by the backend authors; a transformer encoder is NPU-bound, and the NPU is the *best* silicon for it |
| Search the library (cosine over all embeddings) | **custom WebGPU compute** | embarrassingly parallel, scales with library size, and **not** covered by ExecuTorch — this is our code (`searchByVector`, `db.ts`) |
| Retrain many heads + re-tag everything | **WebGPU** batched matmul `[N×d]·[d×L]` | the inner loop the autonomous flow hammers after each update |
| Train one head on a correction | plain JS | a 512-d dot product is microseconds; GPU is pointless for one |

**Do not hand-write encoder kernels.** A WGSL matmul loses to ExecuTorch's
Vulkan/QNN/Core ML backends, and worse — WebGPU can't reach the **NPU**, which is
the right accelerator for the encoder on modern phones. Custom kernels move you
off the best silicon onto the second-best.

**WebGPU on native RN is real and in-ecosystem.** Software Mansion (authors of
`react-native-executorch`, which we already depend on) also ship
`react-native-wgpu` — native WebGPU/Dawn bindings, not a browser shim. So a
WebGPU compute path for search + batched re-tag is concrete for this stack, and
it targets exactly the gaps ExecuTorch leaves.

### 6.2 Make models small the right way (bigger lever than kernels)

For "fits on device," quantization and distillation beat any kernel:

- **Quantize (int8 / int4):** 4–8× smaller encoder, faster, and the NPU delegates
  *prefer* quantized graphs. This does more for on-device viability than any
  hand-written op.
- **Distill / specialize:** a tiny encoder distilled on *meme-domain* images can
  match a big general CLIP **on memes specifically** — smaller, faster, and more
  discriminative, which directly raises the heads toward "near-perfect."
  Specialized embeddings beat general embeddings + clever kernels.

### 6.3 "Mixture of experts" on a phone: what fits

The appealing MoE intuition — *route to small specialists, only pay for what
fires* — can port to a phone, but the scale decides everything, and there are
**two different things both called "MoE"** that behave very differently.

**Scale first.** MoE saves *compute* (FLOPs per input), not *memory* — every
expert in a token-routed MoE must be resident, because routing is per-token and
you can't predict the next expert. So the question is just "does the whole thing
fit in RAM?":

| Model size | int4 resident | int8 resident | Phone verdict |
|---|---|---|---|
| 500B (e.g. frontier MoE) | ~250 GB | ~500 GB | impossible — storage alone rules it out |
| **1B total (e.g. 5×~200M experts)** | **~0.5 GB** | ~1 GB | **feasible** on an 8 GB+ phone (people run 1–3B LLMs on-device) |
| one 300M expert | ~150 MB | ~300 MB | trivial |
| one 100M expert | ~50 MB | ~100 MB | trivial |

A vision encoder is *easier* than an on-device LLM because it fires during
indexing/search, not always-on. So **a ~1B model is a viable target**; 500B is
not.

**Two flavours of MoE, and they page differently:**

1. **Token-routed MoE** (what "1B MoE" technically means — sparse FFN experts
   inside the transformer). The router picks experts *per token*. A ViT has ~256
   patch tokens per image, so a single forward pass fans out across **all/most
   experts anyway** → you **cannot page within one inference; all experts stay
   resident** (~0.5 GB at 1B/int4). What you save is **compute** (each token only
   runs trunk + top-k experts): lower latency, less battery and thermal
   throttling on a big index run. A real phone win — just not a memory win. The
   risk: ExecuTorch's static-graph NPU delegates may not handle dynamic
   per-token gather/scatter dispatch cleanly — **prototype this before
   committing**, or routing falls back to CPU/Vulkan and the latency win
   evaporates.

2. **Image-level expert routing** (coarse: one look at the *whole image* picks a
   specialist). *This* pages — only the chosen specialist is resident; cold ones
   sit on disk. It is **not** a 1B MoE transformer; it's "swappable specialist
   encoders," and it's the recommended shape for Memeget — see §6.4.

**Do you even need MoE?** For a vision encoder, a dense ~300–400M model distilled
on meme+face data may match a 1B sparse one *on your domains* with far less
complexity. MoE earns its keep only if you want one model spanning many domains
(memes + faces + art + text-heavy + NSFW) and sparsity to keep per-image cost
down. If the domain is mostly "memes," dense-and-specialized likely wins on
simplicity.

### 6.4 Image router + cascaded specialist passes (recommended)

The phone-friendly realization of the MoE intuition. Think **receptionist +
specialists**: one cheap look routes each image to only the specialists it needs.

```
image ─► shared encoder (you already run this) ─► embedding
                                                   │
                                                   ▼
                                          tiny router (classifier on the embedding)
                                          "face? · anime? · text-heavy? · crypto? · generic?"
                                                   │  (per-image, coarse)
                 ┌─────────────────┬───────────────┼────────────────┐
                 ▼                 ▼               ▼                 ▼
          face specialist    anime/art spec.   text/layout spec.   (none → stop)
          (detector+embed)   (small encoder)   (OCR — already)      generic tags only
                 └─────────────────┴───────────────┴──► merge tags (mergeTags)
```

Why it fits a phone better than token-MoE:

- **Tiny memory.** Load only the specialist the router asked for; `mmap`/page the
  rest; cold specialists cost nothing.
- **Pay only for what's relevant.** Most memes trip 1–2 specialists, so the
  average image is *cheaper* than running one big model on everything.
- **It's a cascade.** Cheap pass first, escalate only when warranted — the
  biggest battery/thermal saver for bulk indexing.
- **It's the shape the code already has.** `processFile` is already a multi-stage
  pipeline (copy → thumbnail → embed → OCR → classify); router → specialists is
  just more stages.
- **Grows cleanly.** New domain = add a specialist + register it with the router.
  No retraining one giant model; each specialist self-improves via §4
  independently.

**The one gotcha — router blind spots.** If the router misses "this has a face,"
the face specialist never runs and the tag is silently lost (cascades inherit the
router's errors). Mitigations:

1. **Gate on cheap reliable detectors where they exist.** Fire the face
   specialist off ML Kit face *detection* (cheap, accurate), not the learned
   router's guess. Reserve the learned router for fuzzy domains (vibe, art-style).
2. **Periodic full sweeps.** While idle + charging, re-run *all* specialists on a
   sample regardless of routing — catches router misses **and** generates
   training data that improves the router, feeding back into the §4 loop.

**The thesis, assembled:** a quantized, domain-distilled trunk encoder on the NPU
produces specialized embeddings; a tiny per-image router fans out to paged
specialist encoders (only what's needed) and resident per-label heads (~2 KB
each); a WebGPU compute layer makes search and the retrain/re-tag loop instant at
any library size; and the autonomous loop (§4) keeps router and every specialist
improving on device. Each piece sits on the silicon it belongs on, and the
specialist count grows without growing resident memory.

---

## 7. Cold start: bundled reference corpus + kNN tag-transfer

The hardest product problem isn't the self-improving loop — it's that a
loop-based design is **bad by default and only good after the user labors**. That
is unacceptable: teaching must be optional delight, never the path to basic
usefulness. The app has to be good *out of the box, on niche memes, with zero
user work.*

### 7.1 Three layers of "good by default" (teaching is the last, optional one)

Separate **"improves passively with use"** (fine) from **"user must do homework"**
(not fine). Quality comes from three layers; the user only ever touches the
third, and only if they want to:

| Layer | What it does | User labor |
|---|---|---|
| **1 — Open-world perception** | caption (small VLM) + OCR + open-vocab embedding. A meme in no label set is still *describable* and *readable* → findable. Handles the infinite "a meme is an idea" tail. | **none** |
| **2 — Bundled world knowledge** | a precomputed reference corpus + kNN tag-transfer (this section). Names known/niche memes instantly. Grows for everyone via pack updates, not individual effort. | **none** |
| **3 — Personal self-improvement** | the autonomous loop (§4), learning passively from normal usage. Explicit teach-by-example stays for power users. | **optional** |

Layers 1–2 carry the experience. Layer 3 is gravy. This section is **Layer 2**.

### 7.2 The mechanism: kNN tag-transfer (label propagation)

Given a labeled reference corpus, default tagging needs **no training and no user
input**:

1. **Build time (offline / CI):** embed every reference image with the *app's*
   CLIP → bundle `{ embedding, tags }`.
2. **Runtime (on-device, no network):** embed the user's meme → find its nearest
   neighbours in the bundled set → **transfer their tags** by a
   similarity-weighted, frequency-aware vote, thresholded.

A user's pepe-brainlet meme retrieves other pepe-brainlet memes and inherits
`pepe, brainlet, wojak` instantly. This is "reverse image search" with the
database shipped *inside the app* — open-world recall, zero runtime calls, zero
labor.

### 7.3 First pack: the basedmemes corpus

The scraped `basedmemes_dataset.json` is a ready-made Layer-2 pack:

- **5,183 memes**, avg **4.2 tags** each (min 1, max 10), none untagged.
- **3,552 unique tags**, but a heavy long tail: **1,824 singletons**; only **276
  tags have ≥10 support**, **121 have ≥20**, **54 have ≥50**. The reliable core is
  the ≥10-support band.
- **Skew:** pepe (2,526) / wojak (1,762) / brainlet / bobo / boomer / doomer /
  apu / npc / soy… It is a *based/4chan* corpus — a fantastic pack for **exactly
  the niche generic CLIP is worst at**, weak on mainstream formats / faces / pop
  culture (those want a complementary broader pack later).

### 7.4 Ship embeddings, not images (privacy + legal win)

Bundle the **vectors + tags only**, never the scraped images:

- 5,183 × 512 × float32 ≈ **~10 MB**; int8 ≈ **~2.5 MB**. Trivially bundleable
  (or a one-time download like the CLIP model already is).
- No redistribution of copyrighted meme images — only embeddings. Fits the
  no-network, no-liability ethos exactly.

### 7.5 Free byproducts

- **A real vocabulary.** After cleaning (drop stopwords `in/on/with/by/at`, drop
  or downweight singletons and fragment tags like `face/head/big/red`, normalize)
  the ≥10-support core is a community label set far richer than the 97 hand-written
  `MEME_LABELS`.
- **Associations for free.** Tag **co-occurrence** *is* the knowledge graph —
  compute it from statistics instead of hand-writing `associations` in
  `memeLabels.ts` (e.g. tags that co-occur with `pepe`).
- **Bootstraps Layer 3.** kNN-transferred tags are free pseudo-labels, so the §4
  autonomous loop starts from a strong prior instead of cold.

### 7.6 Caveats

- **Tag noise** — needs the cleaning pass above before the vocabulary is usable as
  labels (kNN transfer tolerates more noise than head-training does).
- **Skew** — based-meme heavy; plan a second, broader pack for formats/faces/pop
  culture. Packs compose: kNN over the union.
- **Encoder lock** — embeddings must come from the app's CLIP; swapping the
  encoder (e.g. → SigLIP) means rebuilding every pack in CI.

### 7.7 Build pipeline (CI)

Mirror the existing APK workflow: a job that reads the dataset JSON, downloads
each image, transcodes (the `toJpeg` step already handles WebP/HEIC), embeds with
the same CLIP the app loads, and emits `pack.bin` (`{embedding,tags}`, int8) +
`vocab.json` (cleaned labels) + `associations.json` (co-occurrence graph). Ship as
a bundled asset or a versioned download.

---

## 8. Roadmap (ROI order)

1. **Bundled corpus + kNN tag-transfer** (§7) — fixes "average by default" with
   zero user labor, using the basedmemes pack we already have. Ship embeddings,
   not images. Highest priority: it makes the app good *before* any teaching.
2. **Caption pass** (small VLM at index time) — Layer-1 open-world recall for the
   niche/long tail. Probably the biggest recall lever after the pack.
3. **Close the autonomous head loop** on the CLIP heads we already have:
   feedback + pseudo-labeling → debounced retrain → hold-out gate → versioned
   commit → `retagAll`. Now *gravy on top of* a good default, not the engine.
4. **Face pipeline**: detection + a face-embedding `.pte` + `buildFaceHeads()`.
   The one place a dedicated model clearly beats CLIP.
5. **Stronger frozen backbone** (SigLIP/ViT-L) — biggest generic lift, constant
   swap + re-index (and rebuild the pack — encoder lock, §7.6).
6. **Image router + specialist passes** (§6.4) — once there are ≥2 specialists
   (e.g. the face pipeline + an art/anime one), add the per-image router so each
   meme only runs the specialists it needs. Detector-gated where possible.
7. **Off-device LoRA / distillation** on the meme/face encoder — only after the
   above plateau. (Token-routed 1B MoE only if a prototype proves ExecuTorch can
   dispatch it on the NPU — see §6.3.)

---

## 9. Open questions

- Where to run background retrains in RN (idle callback vs a background task vs
  on next app foreground) without jank.
- Whether faces reuse the `exemplars` table (+`space` column) or get their own —
  the doc assumes a `space` column for minimal surface area.
- Hold-out size for labels with very few explicit examples (cold start): below
  ~5 positives, skip pseudo-labeling entirely and stay explicit-only until there
  is enough ground truth to hold out.
- WebGPU search threshold: at what library size does the WGSL cosine kernel beat
  the JS loop (`searchByVector`) net of buffer-upload overhead? Below it, stay on
  CPU / move to `sqlite-vec`.
- Expert-paging policy: which specialist encoders stay resident vs `mmap`'d, and
  how to hide first-use fault latency (warm the likely expert from the router's
  top-2?).
- Router threshold tuning: how aggressively to fire specialists (recall vs cost),
  and which domains are detector-gated vs learned-router-gated (§6.4).
- Whether a token-routed 1B MoE is dispatchable on ExecuTorch's NPU delegates at
  all, or whether image-level routing (§6.4) is the only viable sparse path on
  device.
- kNN tag-transfer tuning (§7): k, the similarity floor, and how to weight a
  neighbour's tag (by cosine × tag-frequency? cap common tags like `pepe` so they
  don't drown specifics?). Needs the recall@k eval to tune against.
- Pack search cost: kNN over ~5k (and growing) bundled vectors per indexed meme —
  brute force in JS, the WebGPU search kernel (§6.1), or `sqlite-vec`? Shares the
  same engine as library search.
- Cleaning thresholds (§7.5): minimum tag support to keep as a label, and the
  stopword/fragment blocklist — tune so the vocabulary stays useful without
  dropping real niche entities.
