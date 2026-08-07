# Local pop-culture identification: A → B → C design

**Date:** 2026-08-06  
**Status:** Draft for review  
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

### A1. Entity / reference retrieval at index time

After primary image embed (and OCR):

1. Query an on-device **entity index** (brute-force cosine until N forces HNSW/sqlite-vec).  
2. Candidates = pack exemplars + (optional) logo/text hits from OCR.  
3. Score with margin vs negative anchors (reuse recognition calibration ideas).  
4. Emit tags with `source: 'entity_pack' | 'exemplar'` and confidence.  
5. Feed top hits into `formatGrounding` **with the same tier honesty** as zero-shot (`recognized` / `weak` / `unknown`).

No template registry. Open-ended naming + graceful unknown — per composite-meme doc.

### A2. Teaching / entity packs as the scale path

Reuse `src/teachingPack.ts` (`memeget-teaching-pack` v2):

- Build packs offline: for each entity, embed K representative images with the **shipping** primary encoder; store vectors + aliases as `associations`.  
- First-launch or Settings: import first-party packs (or seed DB from bundled pack JSON).  
- On primary `MODEL_ID` change: regenerate packs (vectors invalid); app already rejects mismatched stamps.

**Do not** grow zero-shot `MEME_LABELS` to tens of thousands. Packs + heads scale; giant label matrices do not (Twetch lesson).

### A3. Auto-mine → human review

| Output | Path |
|---|---|
| Baseline label candidates | `src/data/*Baseline.json` via existing harvest/mine |
| Association edges | co-occurrence + entity aliases → PR diff against curated associations |
| Entity packs | `tools/corpus/export_packs.py` → pack JSON |
| OCR rule suggestions | high-precision watermark patterns only |

Curated core stays hand-authored. Machines propose; humans merge.

### A4. Speed wins tied to A

- When tier is `recognized` **or** entity pack hit ≥ calibrated threshold: **skip or defer VLM** (user setting: always / on-uncertain / never skip).  
- Identity searchable immediately from pack tags + associations even before caption.  
- Keep VLM for joke/situation language — not for “who is this.”

### A5. Accept gates (A)

- New **entity/person slice** in tagging eval: `mustFind` celeb/brand names on held-out images.  
- `npm run recognition` must not regress format precision when packs add parallel paths.  
- Pack import + re-tag wall time budget on a 2k library (document target on device).

---

## Track B — Encoder specialization

### B0. Measure what exists

Score `memeget-datasets/dist-memeft/mobileclip_s2_text_memeft_xnnpack_fp32.pte` against stock text tower on golden/recognition. Keep, discard, or use as warm start. **No new training until B0 numbers exist.**

### B1. Text-tower FT (implemented path)

`tools/finetune/finetune.py`: freeze image, InfoNCE on tag/title text views.  
Accept: ↑ retrieval Recall@k / MRR / aspect MAP; no generic-probe collapse.

### B2. Entity-aware training views

Extend pair construction:

- `"a photo of {person}"`, `"{name} meme"`, alias lists, brand wordmarks as text.  
- Multi-positive entity batches; hard negatives (lookalikes, same-template different subject).  
- Still freeze image tower until B2 plateaus.

### B3. Optional image LoRA

Only if visual confusions remain (twins, heavy crops). Merge adapters → dense weights → `tools/model-export` contract. Triggers full re-index + pack rebuild (app model-stamp guards already exist).

### B4. Ship

- New `MODEL_ID` (e.g. `mobileclip-s2-memeft-v2`).  
- APK env / release assets via existing workflows.  
- Rebuild `label-vectors.json`, golden text queries, and **all entity packs** in the new space.

### B accept gates

| Gate | Rule |
|---|---|
| `npm run eval` | no regression beyond tolerance; prefer gains on meme queries |
| `npm run recognition` | precision/tier structure holds or improves |
| Entity slice | person/brand Recall↑ vs stock |
| Generic probes | dog/car/screenshot/social-UI queries do not collapse |
| Latency | embed P50 not worse than stock S2 by >15% on Pixel-class device |

---

## Track C — Generative VLM (last)

### Why last

- Prefill-dominated; fights “instant.”  
- Finetune is easy; **ExecuTorch multimodal 8da4w re-export matching RNE catalog contract is not** (`docs/vlm-model-decision.md`).  
- A+B already supply names via retrieval; C’s job shrinks to **situation/analogy language** (Stages 4–5), not identity.

### C0. Go/no-go spike (timeboxed)

Before any product commitment:

1. Export candidate small VLM (e.g. SmolVLM2-class) or prove Gemma LoRA re-export path.  
2. Measure Vulkan delegate blob count / CPU fallback (appendix in vlm-model-decision).  
3. Micro-bench vs current Gemma E2B on device.  
4. **No-go** if no latency win or graph shatters; record and stop.

### C1. If go: distillation data from corpus

- Frontier (or large local) teacher captions on train split only: two-layer tags, entity names from corpus ground truth, analogy line when applicable.  
- SFT/LoRA student; reward options later (CLIP-similarity to our tower, schema adherence, OCR match).  
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

| Phase | Deliverable | Exit criteria |
|---|---|---|
| **0.1** | Corpus hub unifying basedmemes+KYM+memedepot | one `load_records`, status counts, eval split |
| **0.2** | B0 score existing memeft text `.pte` | keep/drop decision written in eval notes |
| **A.1** | Entity ingest v1 (people/brands) + pack exporter | packs stamp-compatible; import works |
| **A.2** | Runtime entity retrieve + grounding + VLM skip policy | entity eval slice ↑; no recognition regression |
| **A.3** | Continuous mine → candidate PR workflow | labels/associations/packs from `corpus mine` |
| **B.1** | Text FT on full corpus | gates green vs stock |
| **B.2** | Entity-aware pairs | person/brand slice ↑ again |
| **B.3** | Export + APK model id + pack rebuild | on-device load + re-index path verified |
| **B.4** | Image LoRA only if needed | gates + latency |
| **C.0** | Export/Vulkan spike | go/no-go |
| **C.1+** | Distill + ship only on go | tagtest/coverage/latency |

Parallelism: 0.1 and 0.2 start immediately; A.1 can proceed while B0 runs; B.1 needs 0.1; C never blocks A/B.

---

## Component boundaries (implementation sketch)

| Component | Responsibility |
|---|---|
| `tools/corpus/` (new) | ingest adapters, sqlite/jsonl, mine, pack export, train orchestration |
| `tools/finetune/` | extend pair builders for entities; keep freeze-image default |
| `tools/model-export/` | memeft export + MODEL_ID; pack rebuild hook |
| `src/entityRetrieve.ts` (new) | pure: query vectors × pack exemplars → scored hits |
| `src/indexer.ts` | call retrieve; merge tags; VLM skip policy |
| `src/visionCore.ts` | grounding consumes entity hits; tier honesty preserved |
| `src/baselineLabels.ts` / packs | breadth vs exemplar scale policy |
| `tools/eval/` | entity slice + generic probes + pack-aware recognition |

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

## Open decisions (resolve in implementation plan, not blockers for this spec)

1. Entity index: brute force vs `sqlite-vec` / HNSW at what N.  
2. First-party packs: bundled in APK vs first-run download vs Settings import only (download contradicts zero-network-from-install unless optional).  
3. Default VLM skip policy.  
4. Public release of memeft weights vs personal builds only.  
5. Minimum images N per entity (start 5).  

---

## References

- `docs/composite-meme-understanding.md`  
- `docs/memedepot-corpus.md` / `docs/memedepot-finetune.md`  
- `docs/vlm-model-decision.md` / `docs/model-run-speedups.md` / `docs/embedding-roadmap.md`  
- `docs/on-device-vlm.md`  
- `tools/eval/README.md`  
- `src/recognition.ts`, `src/visionCore.ts`, `src/teachingPack.ts`, `src/learnCore.ts`  
- Local: `~/projects/basedmemes_archive`, `~/projects/memeget-datasets/dist-memeft/`
