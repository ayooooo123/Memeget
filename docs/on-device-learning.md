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

### 6.3 Phone-native "mixture of experts"

The appealing MoE intuition — *route sparsely to small specialists, only pay for
what fires* — ports to a phone **only if the experts are tiny and resident
budget is bounded.** The literal big-MoE dream does not, for one reason:

> **MoE saves FLOPs, not memory.** Every expert must be resident (or paged),
> because routing is per-input and you can't predict which expert is next. A
> 500B model at int4 is ~250 GB just to *store* — it optimizes the axis phones
> aren't bottlenecked on (compute) and ignores the one they are (memory + load).
> A small dense model of equal *active* params is a strictly better phone fit.

But the *pattern* is already how Memeget is built — it just was never named:

| MoE concept | Datacenter | Memeget on-device |
|---|---|---|
| Shared trunk | huge shared layers | one frozen encoder (on the NPU) |
| Router / gate | learned per-token | coarse zero-shot classifier picks the domain |
| Experts | billions of params each | per-label heads (~2 KB) + small specialist encoders (face/anime/NSFW nets, MB) |
| Sparsity win | skip most FLOPs | fire 1–5 heads; don't load cold specialists |

The one big-MoE trick that *does* port: **expert paging.** Keep resident only the
trunk, the router, and the "hot" experts the user actually hits; `mmap` the cold
specialist encoders from storage and fault them in when the router calls them
(à la `llama.cpp` mmap weights). Because the experts are small, load latency is
tolerable, and you get the *feel* of a giant multi-domain model while holding
only a few hundred MB in RAM.

**The thesis, assembled:** a quantized, domain-distilled trunk encoder on the
NPU produces specialized embeddings; a coarse router fans out sparsely to tiny
per-label heads (resident) and small specialist encoders (paged on demand); a
WebGPU compute layer makes search and the retrain/re-tag loop instant at any
library size; and the autonomous head loop (§4) keeps every expert improving on
device. Each piece sits on the silicon it belongs on, and the expert count can
grow without growing resident memory.

---

## 7. Roadmap (ROI order)

1. **Close the autonomous head loop** on the CLIP heads we already have:
   feedback + pseudo-labeling → debounced retrain → hold-out gate → versioned
   commit → `retagAll`. Zero new models; ~90% of "self-improving"; reuses
   `trainHead`/`buildExemplarHeads`/`retagAll`.
2. **Face pipeline**: detection + a face-embedding `.pte` + `buildFaceHeads()`.
   The one place a dedicated model clearly beats CLIP.
3. **Stronger frozen backbone** (SigLIP/ViT-L) — biggest generic lift, constant
   swap + re-index.
4. **Off-device LoRA** on the meme/face encoder — only after 1–3 plateau.

---

## 8. Open questions

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
