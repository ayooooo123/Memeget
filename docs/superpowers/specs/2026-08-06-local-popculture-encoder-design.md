# Local pop-culture identification: A → B → C design

**Date:** 2026-08-06  
**Status:** Draft — post-review interfaces filled; pending human approval
**Scope:** Speed + insanely good local pop-culture / celeb / personality ID + end manual-per-meme tagging, via continuous corpus growth.

## Summary

Memeget will close the gap between “has a good dataset” and “knows pop culture like a frontier model” **without leaving the device at inference time**, by stacking three tracks already implied by the repo’s own docs:

| Track | Name | Job |
|---|---|---|
| **A** | Knowledge plane | Offline entity/reference retrieval + auto-mined teaching packs + association graph. Identity in milliseconds on the encoder path. |
| **B** | Encoder specialization | Continuously improve MobileCLIP-S2 (text tower → entity-aware pairs → optional image LoRA) on a growing local corpus. |
| **C** | Generative VLM upgrade | Only after A+B plateau: distill / export a stronger on-device captioner. Highest risk, parked behind a go/no-go gate. |

**Continuous corpus** is the shared fuel: scrape and refine offline forever; the app only ever receives **derived artifacts** (baselines, packs, golden vectors, `.pte` weights). Raw scraped media never ships in the APK.

This is not “one bigger local LLM.” It is retrieval + specialized embedding geometry + (later) a better describer — the only stack that can feel **instant** on phone hardware while naming people, brands, formats, and events.

## Problem

Measured today (`npm run recognition`, 595-meme holdout, MobileCLIP-S2, ~399 labels):

- **26%** `recognized` (~81% precise)
- **49%** `weak` (~27% precise)
- **25%** `unknown` (~5% if forced)

There is **no face/entity/reference KB**. Person ID is ~9 hand prompts + teach-by-example. Composite reference memes need Stages 2–4 from `docs/composite-meme-understanding.md` (name refs → resolve entities → analogy); only perceive + abstain + tag-what-you’re-told are shipped.

Manual tagging remains the bridge for every new celeb, niche format, or personal meme dialect. A good dataset that still needs hand labels is a **pipeline** problem, not a data-volume problem.

Speed: MobileCLIP-S2 primary is already the right fast path; Gemma 4 E2B is enrichment and prefill-bound. “Instant ID” means **not waiting on the VLM** for identity.

## Goals

1. **Local-only inference** — no upload, no cloud describe tier required for core ID.
2. **Pop-culture ID** — people, personalities, characters, brands, shows, events, meme formats named when visually/textually present.
3. **Feels instant** — identity on embed + retrieval (ms–low hundreds ms); VLM optional/enrichment.
4. **Stop manual-per-meme tagging** — humans review mined diffs and correct edge cases; corpus + packs carry the bulk.
5. **Continuous improvement** — scrape/ingest → mine → train → eval → ship artifacts on a repeatable loop.
6. **Measurable** — every track gated by eval; no silent “feels smarter.”
7. **Compose with what exists** — `learnCore`, teaching packs, baselines, grounding, finetune tools, export contract, recognition tiers.

## Non-goals

- Multi-trillion-param weights on device.
- Runtime web scrape inside the app.
- Bundling copyrighted meme images or unreviwed face galleries in releases.
- Replacing hybrid search with a generative chatbot.
- Auto-merging machine labels into `CURATED_MEME_LABELS` without human review.
- Shipping Track C before A+B accept gates and a Vulkan/export go decision.

## Existing system (anchors)

| Piece | Role |
|---|---|
| MobileCLIP-S2 (`PRIMARY_EMBEDDING_MODEL`) | Fast image/text space; zero-shot + search + exemplars |
| `src/recognition.ts` | Calibrated abstention; feeds VLM grounding honesty |
| `src/memeLabels.ts` + `baselineLabels.ts` | Curated core + harvested breadth tiers |
| `src/learnCore.ts` + teaching packs | Few-shot heads; shareable exemplar vectors |
| `src/visionCore.ts` `formatGrounding` | CLIP → VLM knowledge injection |
| `tools/finetune/` | Freeze-image, tune-text InfoNCE on local archive |
| `tools/model-export/` | ExecuTorch `.pte` contract (norm baked, 512-d) |
| `tools/eval/` | Retrieval, tagging, aspect, recognition, coverage |
| `docs/composite-meme-understanding.md` | Five-stage composite meme plan |
| Local corpus `~/projects/basedmemes_archive` | basedmemes ~13.7k rows, KYM ~8.5k, memedepot ~2.4k images |
| Prior FT artifact `memeget-datasets/dist-memeft/` | Text-tower memeft `.pte` already produced — must be measured (B0) |

## Chosen approach: A → B → C with shared corpus

Rejected alternatives as *solo* strategies:

- **B-only:** improves meme-tag geometry; weak on named celebs without entity pairs/packs.
- **C-only:** fights latency; generative re-export is the known deployment wall (`docs/vlm-model-decision.md`).
- **Giant zero-shot label list:** false positives explode past ~hundreds of classes (Twetch tier already measured useless at cap).

**Stacking** matches the repo’s written priority order (grounding/KB → encoder FT → generative distill) and the product constraint (instant + local).

```
 CORPUS PLANE (dev/CI)                    ARTIFACTS                 APP (offline)
 ─────────────────────                    ─────────                 ────────────
 scrapers → normalize → corpus DB  ──►  baselines.json          → zero-shot vocab
                    │                   entity/teaching packs   → learnCore heads
                    ├─ mine labels      associations            → search text
                    ├─ mine entities    golden + label-vectors  → CI gates
                    ├─ train pairs      memeft .pte (B)         → primary encoder
                    └─ distill data     (later) VLM .pte (C)    → optional captioner
```

---

## Track 0 — Continuous corpus plane

**Home:** data lives outside the app repo (extend `~/projects/basedmemes_archive` or `memeget-corpus`). App repo receives PRs of derived JSON and release assets only.

### Unified record

```ts
// conceptual schema — implementation may be sqlite or jsonl shards
{
  id: string;              // stable, source-qualified
  source: string;          // basedmemes | kym | memedepot | wikidata_faces | ...
  source_url?: string;
  media_path: string;      // local only
  sha256: string;
  tags: string[];
  entities: { name: string; type: 'person'|'character'|'brand'|'show'|'event'|'format'|'other'; aliases: string[] }[];
  text_views: string[];    // titles/captions for InfoNCE
  license_tier: 'research_local' | 'redistributable_derived';
  fetched_at: string;
  split: 'train' | 'eval';  // deterministic holdout; golden ⊆ eval
}
```

### Ingest adapters

1. **basedmemes / KYM / memedepot** — wrap `tools/finetune/dataset.py` + existing harvesters.  
2. **Pop-culture entities (new)** — multi-view stills + aliases for people/brands/shows/events from license-aware sources (Wikidata/Commons-class first); rate-limited, resumable, robots-respecting.  
3. **Meme template pages** — extend KYM-style entries.  
4. **User feedback (optional)** — collection zip / `described.json` / confirmed teaches → gold tags.

### Hygiene

- Dedupe `sha256` + near-dup cosine (stock S2).  
- Shared tag normalize with `tools/memedepot/harvest.mjs` (`aggregatePages` / `buildBaseline`).  
- Entity classes require **≥ N distinct images** (start N=5) before pack emission.  
- Eval split via existing hash buckets (`is_eval`); **never train on golden**.  
- Politeness and ToS per source; scrapers are dev/CI tools, not app code.  
- NSFW / illegal content policy: drop or quarantine per operator rules before train.

### Commands (target UX)

```bash
corpus ingest <adapter> [--max N]
corpus status
corpus mine labels|entities|associations
corpus build-golden
corpus train-text          # Track B
corpus export-packs        # Track A
corpus eval                # runs app gates against rebuilt vectors
```

Cadence: ingest anytime; `mine` + `eval` on demand or weekly; train only when mine deltas clear noise filters.

### Legal / ethical

- Scraped media: **local research** and **derived taxonomy** only.  
- Ship: prompts, association strings, embedding vectors in packs, metrics, model weights **only after** a human call if weights could memorize training images.  
- Prefer LoRA / text-tower FT (less memorization) before full image FT.  
- Provenance (`source_url`) retained for takedown.

---

## Track A — Knowledge plane (first ship value)

### A0. Type and merge contracts (app)

Extend `Tag.source` in `src/types.ts`:

```ts
source?:
  | 'prompt'      // zero-shot CLIP label
  | 'exemplar'    // learnCore / user teach / imported pack head
  | 'entity_pack' // first-party entity retrieval hit (this track)
  | 'ocr'
  | 'vision'
  | 'manual'
  | 'propagated';
```

**Merge precedence** when the same normalized label appears from multiple sources (keep highest rank; if tied, keep higher `score`):

`manual > propagated > ocr > entity_pack > exemplar > prompt > vision`

Rationale: user intent wins; OCR is literal text; entity_pack is offline-curated multi-view retrieval; user/pack exemplars next; zero-shot prompts are weakest visual guesses; VLM open-vocab last for identity (strong for situation words once present).

**Tie-break across packs:** one global exemplar table after import (existing pack import already flattens). Duplicate labels from two packs → same label head; more positives win via `learnCore` training, not runtime pack IDs. Export packs may still carry distinct `name` for provenance in Settings → Imported packs.

### A1. `entityRetrieve` module (new, pure)

File: `src/entityRetrieve.ts` (React-free, unit-tested).

```ts
export type EntityKind =
  | 'person' | 'character' | 'brand' | 'show' | 'event' | 'format' | 'other';

export interface EntityExemplar {
  label: string;
  category: EntityKind | string;
  vector: Float32Array | number[]; // L2-normalized, primary space
  associations: string[];          // aliases folded into search_text
  positive: boolean;               // negatives supported for hard vetoes
}

export interface EntityHit {
  label: string;
  category: string;
  score: number;       // calibrated P(correct)-style 0..1 when possible
  margin: number;      // raw retrieval margin before calibration
  associations: string[];
  source: 'entity_pack';
}

export interface RetrieveParams {
  imageVec: Float32Array | number[];
  exemplars: readonly EntityExemplar[]; // positives only for ranking
  /** Optional per-image bias, e.g. max cos to NEGATIVE_ANCHORS (same as recognition). */
  anchorSim?: number;
  /** Max labels emitted. Default 5. */
  topK?: number;
  /** Min margin after anchor correction. Calibrate on entity golden slice. */
  minMargin?: number;
}

/** Best positive exemplar per label, margin = cos - ANCHOR_BIAS * anchorSim. */
export function retrieveEntities(p: RetrieveParams): EntityHit[];

/** Map hits → Tag[] for indexer merge. */
export function entityHitsToTags(hits: EntityHit[]): Tag[];
```

- Default `minMargin` starts from recognition’s `MIN_LABEL_MARGIN` (0.13) and is **re-tuned** on an entity holdout; do not assume meme-format calibration transfers to faces.
- Brute-force matmul until exemplar count exceeds ~10k, then revisit ANN (`sqlite-vec` / HNSW) — open decision #1.
- Negatives (`positive: false`) are **not** ranked as labels; they feed `learnCore` veto paths when imported as pack exemplars (existing behavior).

### A1b. OCR → entity path

- Keep high-precision `OCR_RULES` as today (`source: 'ocr'`).
- Additional path: if OCR token/phrase **exact-matches** an entity label or alias (casefold), emit `source: 'ocr'` tag with score 1.0 — no visual required.
- Do **not** double-count: merge precedence collapses ocr + entity_pack on same label to `ocr`.
- Fuzzy OCR↔entity is out of scope for v1 (too many false friends).

### A1c. Indexer integration

After embed + OCR + zero-shot + exemplar heads:

1. `hits = retrieveEntities({ imageVec, exemplars: entityPositives, anchorSim })`
2. Merge `entityHitsToTags(hits)` with other tags via precedence above.
3. Build grounding for VLM from **both** zero-shot labels and entity hits (see A1d).
4. Apply VLM skip policy (A4) before `runVision`.

### A1d. Grounding channel

Extend `formatGrounding` (or a thin wrapper `formatGroundingWithEntities`) so the prompt can include:

```
entities: Keanu Reeves (person), Brand X (brand)
```

Rules:

- Entity lines only for hits above the entity `recognized`-equivalent threshold.
- On full miss (no entity hits and zero-shot `unknown`): keep existing `NO_FORMAT_GROUNDING` open-ended ask.
- Never present weak entity guesses as facts — same honesty contract as `recognitionTier`.

Suggested signature evolution:

```ts
formatGrounding(
  labels: GroundingLabel[],
  related?: string[],
  tier?: RecognitionTier,
  entities?: GroundingLabel[], // NEW — already-thresholded entity names
): string
```

### A2. Teaching / entity packs as the scale path

**Format:** reuse `TeachingPack` / `PackExemplar` v2 unchanged. `Exemplar.associations` **already exists** in `src/types.ts` — entity aliases go there. Offline pack builder fills:

| Field | Entity pack content |
|---|---|
| `label` | Canonical display name (`Keanu Reeves`) |
| `category` | `person` / `brand` / … |
| `vector` | primary-encoder image embedding of one still |
| `associations` | aliases, handle, franchise terms |
| `positive` | true (false only for curated hard-neg stills) |

Build rule: emit a label only if **≥ N distinct source images** survive dedupe (default **N=5**, K≤16 positives packed per entity to match `MAX_MANUAL_POSITIVES` spirit). Pack export **fails** the entity (skip, log) if N not met — this is an exporter gate, not an `npm run eval` metric.

- Embed with the **shipping** primary encoder only.
- First-party distribution: **Settings import** and/or optional bundled asset; default recommendation = ship a modest bundled seed pack in APK for zero-network identity core, larger packs via explicit import (open decision #2).
- On `MODEL_ID` change: exporter rebuilds all first-party packs; app rejects stale stamps (`isTeachingPackCompatible`) — **B.3 accept criterion** includes “old pack import fails closed; new pack import + re-tag succeeds.”

**Do not** grow zero-shot `MEME_LABELS` to tens of thousands. Packs + heads scale; giant label matrices do not (Twetch lesson).

### A3. Auto-mine → human review

| Output | Path |
|---|---|
| Baseline label candidates | `src/data/*Baseline.json` via existing harvest/mine |
| Association edges | co-occurrence + entity aliases → PR diff against curated associations |
| Entity packs | `tools/corpus/export_packs.py` → pack JSON |
| OCR rule suggestions | high-precision watermark patterns only |

Curated core stays hand-authored. Machines propose; humans merge.

### A4. VLM skip policy

New setting key (name TBD in plan), default **`on-uncertain`**:

| Mode | Behavior |
|---|---|
| `never` | Current behavior: every pending meme may be described |
| `on-uncertain` | Skip auto VLM when identity is strong (below) |
| `always` | Skip auto VLM whenever vision enabled would have run; user can still “Describe” one-shot |

**Strong identity** (skip auto VLM under `on-uncertain`) when **either**:

1. `recognitionTier(tags) === 'recognized'`, **or**
2. ≥1 `entity_pack` hit with `score ≥ ENTITY_VLM_SKIP_CONF` (calibrate; start 0.56 to mirror `RECOGNIZED_CONFIDENCE`)

Skipped memes:

- Remain searchable via tags/OCR/associations immediately.
- Stay `vision_state = pending` **or** gain a distinct `skipped_identity` state only if implementation needs retry semantics — prefer keeping `pending` and a separate `vision_skip_reason` setting bit so “Describe library” can still force captions later. Final field choice is an implementation detail; product rule is: **skip is not failure, and forced describe still works.**

Identity is not the VLM’s job once A works; VLM remains for joke/situation language.

### A5. Accept gates (A)

- New **entity/person slice** in tagging eval: `mustFind` celeb/brand names on held-out images (vectors + expected names only in repo).
- `npm run recognition` must not regress format precision when entity path is enabled.
- Pack exporter enforces N≥5; unit test on exporter.
- Pack import + re-tag on a ~2k library completes without UI deadlock; record wall time on device in plan verification.
- Grounding unit tests: entity hits appear; weak hits do not assert; unknown path unchanged.

---

## Track B — Encoder specialization

### B0. Measure what exists

Score `memeget-datasets/dist-memeft/mobileclip_s2_text_memeft_xnnpack_fp32.pte` against stock text tower on golden/recognition. Keep, discard, or use as warm start. **No new training until B0 numbers exist.**

### B1. Text-tower FT (implemented path)

`tools/finetune/finetune.py`: freeze image, InfoNCE on tag/title text views.  
Accept: ↑ retrieval Recall@k / MRR / aspect MAP; no generic-probe collapse.

### B2. Entity-aware training views

Extend `tools/finetune/textviews.py` (and dataset records that carry `entities[]`):

```python
def entity_views(name: str, aliases: list[str], kind: str) -> list[str]:
    # examples — exact templates tuned offline against val R@1
    # "a photo of {name}"
    # "{name} meme"
    # "a meme featuring {name}"
    # alias each as additional views
    ...

def training_views(record) -> list[str]:
    views = tag_and_title_views(record)  # existing
    for ent in record.entities:
        views.extend(entity_views(ent.name, ent.aliases, ent.type))
    return dedupe(views)
```

Batching:

- **Multi-positive:** sampler may include ≥2 images of the same entity in a batch so text towers see within-identity variance.
- **Hard negatives:** optional second phase — mine batches where image cos(stock) is high but entity id differs (lookalikes). Not required for first B2 run; add if person-slice plateaus.
- Image tower remains frozen through B2.

### B3. Ship encoder

- New `MODEL_ID` (e.g. `mobileclip-s2-memeft-v2`).  
- APK env / release assets via existing workflows.  
- Rebuild `label-vectors.json`, golden text queries, and **all entity packs** in the new space.  
- Accept criterion: stale packs fail `isTeachingPackCompatible`; new packs import + re-tag succeed.

### B4. Optional image LoRA

Only if visual confusions remain after B3 (twins, heavy crops). Merge adapters → dense weights → `tools/model-export` contract. Triggers another full re-index + pack rebuild.

### B accept gates

| Gate | Rule |
|---|---|
| `npm run eval` | no regression beyond tolerance; prefer gains on meme queries |
| `npm run recognition` | precision/tier structure holds or improves |
| Entity slice | person/brand Recall↑ vs stock |
| Generic probes | dog/car/screenshot/social-UI queries do not collapse |
| Latency | embed P50 not worse than stock S2 by >15% on Pixel-class device |
| Stale pack rejection | After MODEL_ID bump, old packs fail closed |

---

## Track C — Generative VLM (last)

### Why last

- Prefill-dominated; fights “instant.”  
- Finetune is easy; **ExecuTorch multimodal 8da4w re-export matching RNE catalog contract is not** (`docs/vlm-model-decision.md`).  
- A+B already supply names via retrieval; C’s job shrinks to **situation/analogy language** (composite Stages 4–5), not identity.

### Stage 4 (analogy) without a cloud tier

`composite-meme-understanding.md` once suggested cloud for Stage 4. **This design does not add a cloud describe path** (conflicts with product contract and `vlm-model-decision.md`). Analogy handling:

1. **Near term (A+B):** two-layer *searchability* via entity hits on both domains when present + VLM tags/captions; no guaranteed one-line analogy synthesis.  
2. **Track C:** teacher models (operator machine, offline) may write analogy lines into distill targets; student VLM learns to emit them on-device.  
3. If C0 is **no-go**, Stage 4 stays best-effort on Gemma with entity grounding — accept residual miss rate; do not stand up a server.

### C0. Go/no-go spike (timeboxed ~1–2 days)

Before any product commitment:

1. Export candidate small VLM (e.g. SmolVLM2-class) or prove Gemma LoRA re-export path.  
2. Measure Vulkan delegate blob count / CPU fallback (appendix in vlm-model-decision).  
3. Micro-bench vs current Gemma E2B on device.  
4. **No-go** if no latency-or-quality win or graph shatters; record and stop.

### C1. If go: distillation data from corpus

- Teacher (frontier API or large local) runs **offline on train split only**.  
- Targets: two-layer tags, entity names from corpus ground truth, optional analogy line.  
- SFT/LoRA student; later rewards optional (CLIP sim to our tower, schema adherence, OCR match).  
- Accept on `tagtest` / facet coverage / agreement — not vibes.

### C2. Runtime

- Still a single default model (no user picker).  
- Prefer shorter prompt once entities arrive from A (less prefill).  
- Keep flat `CAPTION/TEXT/SUBJECTS/TAGS` parse contract unless eval proves JSON superior.

---

## End-to-end index path (target)

```
media → frames → primary embed + OCR
     → zero-shot labels (calibrated tiers)
     → entity pack retrieval (A)
     → exemplar heads (user + seeded packs)
     → merge tags (sources ranked: user > ocr > entity_pack > exemplar > clip > vision)
     → optional VLM if uncertain or user wants full describe (A4 policy)
     → caption embed + search_text (ocr + tags + associations + caption)
     → sqlite
```

Search unchanged in structure: hybrid image + caption + lexical; denser correct tokens in `search_text` is the dominant lever (aspect MAP evidence in eval README).

---

## Speed budget (targets)

| Stage | Target (Pixel-class, order of magnitude) |
|---|---|
| Primary embed (image) | at or below current S2 |
| Entity retrieve (≤10k exemplars brute force) | << embed time; add ANN if N grows |
| Zero-shot vs ~400 labels | keep; do not scale to 10k prompts |
| VLM describe | optional; not on critical path for browse/search-after-index |
| First searchable identity | available after embed+retrieve, before VLM |

Exact numbers locked in implementation plan via on-device telemetry already logged for VLM (`[vlm …]`) plus new embed/retrieve timings.

---

## Build order

| Phase | Deliverable | Exit criteria | Depends on |
|---|---|---|---|
| **0.1** | `tools/corpus/` hub: basedmemes+KYM+memedepot → unified records | `load_records`, status counts, eval split | — |
| **0.2** | B0 score existing memeft text `.pte` | keep/drop note in eval notes | golden vectors tooling |
| **A.1** | Entity ingest v1 + pack exporter (N≥5) | pack parses; stamp matches primary | 0.1 for scale; can prototype on tiny hand set |
| **A.2** | `entityRetrieve` + `Tag.source` + grounding + VLM skip | entity slice ↑; recognition non-regress; unit tests | A.1 packs |
| **A.3** | Continuous mine → candidate PR workflow | labels/associations/packs from `corpus mine` | 0.1 |
| **B.1** | Text FT on full corpus | gates green vs stock | 0.1, B0 decision |
| **B.2** | Entity-aware `textviews` | person/brand slice ↑ | B.1, entity fields in corpus |
| **B.3** | Export + MODEL_ID + **rebuild all packs** + APK | load + re-index; stale packs fail closed | B.1/B.2 accept |
| **B.4** | Image LoRA only if needed | gates + latency | B.3 |
| **C.0** | Export/Vulkan spike | go/no-go written | A.2 helpful not required |
| **C.1+** | Distill + ship only on go | tagtest/coverage/latency | C.0 go, preferably A.2 |

**Parallelism DAG:** 0.1 ∥ 0.2; A.1 after minimal corpus or hand seed; A.2 after A.1; B.1 after 0.1+B0; B.2 after B.1+entities; C never blocks A/B. Single primary `MODEL_ID` at a time in the app — do not run B.3 while A.2 device testing expects stock vectors without a restamp plan.

---

## Component boundaries

| Component | Responsibility |
|---|---|
| `tools/corpus/` (new) | ingest adapters, store, mine, pack export, train/eval orchestration CLI |
| `tools/finetune/` | `textviews.entity_views`, dataset `entities[]`, freeze-image default |
| `tools/model-export/` | memeft `.pte` + pack rebuild hook on MODEL_ID bump |
| `src/types.ts` | `Tag.source` includes `entity_pack` |
| `src/entityRetrieve.ts` (new) | pure retrieve + hit→tag |
| `src/indexer.ts` | call retrieve; merge precedence; VLM skip policy |
| `src/visionCore.ts` | grounding accepts entity labels; tier honesty |
| `src/teachingPack.ts` | unchanged format; first-party packs are normal packs |
| `src/learnCore.ts` | heads train on imported pack exemplars (existing) |
| `src/recognition.ts` | zero-shot tiers unchanged; entity path has its own margin constants |
| `tools/eval/` | entity slice, generic probes, exporter N-gate tests |

React-free cores stay unit-testable; native only at embed/VLM edges.

---

## Data flow — continuous improvement

1. Operator or scheduler runs `corpus ingest`.  
2. Hygiene + split assignment.  
3. `corpus mine` emits candidate baselines, associations, entity pack specs.  
4. Human reviews high-impact diffs (curated prompts, denylist, N for new entities).  
5. `corpus train-text` (if data delta warrants) → checkpoint.  
6. Rebuild golden/label-vectors/packs in candidate space.  
7. `corpus eval` / app npm gates — **merge only if green**.  
8. Publish `.pte` + pack assets; bump MODEL_ID when vectors move.  
9. App users: re-index once; teach path remains for personal residue.

---

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Copyright / face rights on packs & weights | research_local default; human gate before public weight release; text-tower/LoRA first |
| Scrape ToS / blocks | polite adapters, dumps/APIs preferred, CI-only where needed |
| Silent FT regression | B0 first; frozen image; generic probes; early stop |
| Label / pack bloat | caps; N-image minimum; eval before raising caps |
| Lookalike celebs | multi-view + hard negs; margin abstention; user corrections |
| Track C time sink | hard timebox C0; default no-go |
| Re-index pain on MODEL_ID bump | existing stamp warnings + exemplar auto-migrate from source memes |

---

## Success metrics

1. **Entity findable %** on held-out person/brand cases (new) — primary north-star for pop culture.  
2. **Recognition** format precision/coverage — must not pay for entities with format lies.  
3. **Aspect MAP / retrieval** — search quality.  
4. **% library needing manual teach** for top-N trending entities — labor metric (drive down).  
5. **Time-to-searchable-identity** after import — speed metric (VLM not required).  
6. **VLM skip rate** with no drop in tagtest mustFind — efficiency.

---

## Open decisions (implementation plan may pick defaults below)

1. Entity index: brute force until ~10k exemplars; then `sqlite-vec` / HNSW.  
2. First-party packs: **default = small bundled seed + Settings import for large packs** (preserves optional zero-network core).  
3. Default VLM skip policy: **`on-uncertain`**.  
4. Public release of memeft weights vs personal builds only — **human/legal gate before any public weight upload**.  
5. Minimum images N per entity: **5** (exporter-enforced).

---

## References

- `docs/composite-meme-understanding.md`  
- `docs/memedepot-corpus.md` / `docs/memedepot-finetune.md`  
- `docs/vlm-model-decision.md` / `docs/model-run-speedups.md` / `docs/embedding-roadmap.md`  
- `docs/on-device-vlm.md`  
- `tools/eval/README.md`  
- `src/recognition.ts`, `src/visionCore.ts`, `src/teachingPack.ts`, `src/learnCore.ts`  
- Local: `~/projects/basedmemes_archive`, `~/projects/memeget-datasets/dist-memeft/`
