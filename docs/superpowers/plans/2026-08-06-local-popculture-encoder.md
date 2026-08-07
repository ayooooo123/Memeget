# Local Pop-Culture Identification (A→B→C) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship continuous local corpus tooling, on-device entity/reference retrieval via teaching packs, calibrated VLM skip-on-strong-identity, and a gated MobileCLIP finetune loop — so pop-culture ID improves without per-meme manual tagging and without cloud inference.

**Architecture:** Offline corpus plane (`tools/corpus/`) produces baselines, entity teaching packs, and train pairs. App gains pure `entityRetrieve` + `Tag.source: 'entity_pack'` merged through `tagMerge`, grounded into the VLM, with optional skip when identity is strong. Encoder track measures existing memeft text tower, then text-tower FT → entity text views → export/MODEL_ID. Track C is a timeboxed export spike only after A/B value ships.

**Tech Stack:** TypeScript/Jest (app cores), Python (corpus/finetune/export), MobileCLIP-S2 + ExecuTorch, existing teaching-pack v2, SQLite on device.

**Spec:** `docs/superpowers/specs/2026-08-06-local-popculture-encoder-design.md`

**Execution note:** Stop and report after each **Chunk**. Do not start Chunk 3 until Chunk 1–2 gates are green. Track C (Chunk 5) is optional and must not block A/B.

---

## File map

| Path | Role |
|---|---|
| `tools/corpus/` | NEW — ingest, status, mine, export-packs CLI (Python) |
| `tools/finetune/dataset.py` | Extend records with `source`, `entities`, multi-root load |
| `tools/finetune/textviews.py` | `entity_views` + entity-aware `training_views` |
| `tools/finetune/eval_memeft.py` | NEW — B0 score memeft text `.pte` / open_clip ckpt vs stock |
| `src/types.ts` | `Tag.source` += `entity_pack` |
| `src/tagMerge.ts` | ranks + durable set per spec |
| `src/entityRetrieve.ts` | NEW pure retrieval |
| `src/entityRetrieve.test.ts` | NEW |
| `src/visionSkip.ts` | NEW pure skip policy |
| `src/visionSkip.test.ts` | NEW |
| `src/visionCore.ts` | grounding accepts entity labels |
| `src/indexer.ts` | wire retrieve + skip |
| `src/tagMerge.test.ts` | extend |
| `tools/eval/` | entity slice fixtures (vectors only) when data ready |
| `docs/superpowers/specs/2026-08-06-local-popculture-encoder-design.md` | status → implementing |

Out of scope for early chunks: image LoRA (B4), full VLM re-export (C1+), ANN index.

---

## Chunk 1: Corpus hub + B0 measure

### Task 1: Corpus package skeleton + unified load

**Files:**
- Create: `tools/corpus/README.md`
- Create: `tools/corpus/schema.py`
- Create: `tools/corpus/load.py`
- Create: `tools/corpus/cli.py`
- Create: `tools/corpus/test_load.py`
- Modify: `tools/finetune/dataset.py` (keep backward compatible; corpus load may wrap it)

- [ ] **Step 1: Write failing tests for unified records**

Create `tools/corpus/test_load.py`:

```python
import os
import tempfile
import json
from pathlib import Path

from load import load_corpus, split_records


def test_load_merges_basedmemes_style_jsonl(tmp_path: Path):
    # minimal fake archive: dataset.jsonl + one image file
    img = tmp_path / "images_only" / "a.jpg"
    img.parent.mkdir(parents=True)
    img.write_bytes(b"\xff\xd8\xff\xd9")  # tiny jpeg stub — loader only checks exists
    (tmp_path / "dataset.jsonl").write_text(
        json.dumps({"image": "a.jpg", "suffix": "pepe, smug"}) + "\n",
        encoding="utf-8",
    )
    recs = load_corpus([str(tmp_path)])
    assert len(recs) == 1
    assert recs[0].id.endswith("a.jpg") or recs[0].id == "a.jpg"
    assert "pepe" in recs[0].tags
    assert recs[0].split in ("train", "eval")
    assert recs[0].license_tier == "research_local"


def test_split_is_deterministic():
    class R:
        def __init__(self, id):
            self.id = id
    a = [R("x"), R("y"), R("z")]
    tr1, ev1 = split_records(a)
    tr2, ev2 = split_records(a)
    assert [r.id for r in tr1] == [r.id for r in tr2]
    assert [r.id for r in ev1] == [r.id for r in ev2]
```

- [ ] **Step 2: Run tests — expect fail**

```bash
cd /Users/jd/projects/memeget && python3 -m pytest tools/corpus/test_load.py -v
```

Expected: FAIL (module missing) or collection error.

- [ ] **Step 3: Implement schema + load + CLI status**

`tools/corpus/schema.py`:

```python
from __future__ import annotations
from dataclasses import dataclass, field
from typing import Literal

EntityType = Literal["person", "character", "brand", "show", "event", "format", "other"]

@dataclass
class EntityRef:
    name: str
    type: EntityType = "other"
    aliases: list[str] = field(default_factory=list)

@dataclass
class CorpusRecord:
    id: str
    source: str
    media_path: str
    sha256: str
    tags: list[str] = field(default_factory=list)
    entities: list[EntityRef] = field(default_factory=list)
    text_views: list[str] = field(default_factory=list)
    license_tier: Literal["research_local", "redistributable_derived"] = "research_local"
    source_url: str | None = None
    fetched_at: str | None = None
    split: Literal["train", "eval"] = "train"
```

`tools/corpus/load.py`:
- Reuse hash split from `tools/finetune/dataset.is_eval` (import or copy the one-liner — prefer import via sys.path insert like other finetune scripts).
- `load_corpus(roots: list[str]) -> list[CorpusRecord]`:
  - For each root, if it looks like basedmemes dir (`dataset.jsonl` or `meme_dataset_kym.json`), delegate to `dataset.load_records` and map into `CorpusRecord` (compute sha256 of file bytes; source name from root basename).
  - Also accept memedepot-style: `collection.json` list + `images_only/` (inspect `/Users/jd/projects/basedmemes_archive/memedepot/collection.json` for fields; map tags if present).
- Dedupe by sha256 (keep union of tags).
- Assign `split` via `is_eval(id)`.

`tools/corpus/cli.py`:

```python
# argparse: status | ingest (stub ok) 
# status: print counts train/eval, sources, avg tags
```

`tools/corpus/README.md`: point at archive paths, legal note (local only), commands.

- [ ] **Step 4: Run tests — expect pass**

```bash
python3 -m pytest tools/corpus/test_load.py -v
```

- [ ] **Step 5: Smoke real archive**

```bash
python3 tools/corpus/cli.py status \
  --root /Users/jd/projects/basedmemes_archive/www.basedmemes.lol \
  --root /Users/jd/projects/basedmemes_archive/kym_bundle \
  --root /Users/jd/projects/basedmemes_archive/memedepot
```

Expected: nonzero record counts; train+eval sum = total.

- [ ] **Step 6: Commit**

```bash
git add tools/corpus tools/finetune/dataset.py
git commit -m "feat(corpus): unified local meme corpus loader and status CLI"
```

---

### Task 2: B0 — measure existing memeft text tower

**Files:**
- Create: `tools/finetune/eval_memeft.py`
- Create: `tools/finetune/B0_NOTES.md` (results table)

- [ ] **Step 1: Implement offline comparison script**

`eval_memeft.py` should:
1. Load stock MobileCLIP-S2 via existing `clipmodel.py`.
2. Confirm golden usability: `tools/eval/golden.json` has `queries[]` (each with `query` and/or `queryVec`) and `memes[]` with `imageVec`. If `queryVec` is missing, embed `query` text inside the script for stock vs candidate.
3. Load candidate text weights if a torch `ckpt` is provided. Search:
   ```bash
   find /Users/jd/projects/memeget-datasets /Users/jd/projects/memeget \( -name '*.pt' -o -name '*memeft*' \) 2>/dev/null | head -50
   ```
   **If only `.pte` exists** (current `dist-memeft`): write `B0_NOTES.md` with **BLOCKED — no torch ckpt; on-device or recover ckpt required** and exit 0. Do not invent metrics. Chunk 1 still completes.
4. When ckpt exists: rank images (image tower frozen/stock) per query under stock vs candidate text; print Recall@1/5 and MRR; write table to `B0_NOTES.md`.

Chunk 1 verification: `B0_NOTES.md` exists with either metrics **or** explicit BLOCKED reason.

- [ ] **Step 2: Run B0**

```bash
python3 tools/finetune/eval_memeft.py \
  --golden tools/eval/golden.json \
  --ckpt <path-if-any> \
  --out tools/finetune/B0_NOTES.md
```

- [ ] **Step 3: Commit notes + script**

```bash
git add tools/finetune/eval_memeft.py tools/finetune/B0_NOTES.md
git commit -m "chore(finetune): B0 baseline script for memeft text tower"
```

---

## Chunk 2: App entity path (Track A core)

### Task 3: `Tag.source` + merge ranks

**Files:**
- Modify: `src/types.ts`
- Modify: `src/tagMerge.ts`
- Modify: `src/tagMerge.test.ts` (or create if missing)
- Grep callers that exhaustiveness-check `source`

**Rank migration (breaking vs current code):** today `tagMerge` is
`manual(6) > exemplar(5) > propagated(4) > ocr(3) > vision(2) > prompt(1)`.
Spec requires `manual > propagated > ocr > entity_pack > exemplar > prompt > vision`
and durable OCR/entity_pack. Audit before editing:

```bash
rg "TAG_RANK|tagRank|DURABLE_SOURCE|'exemplar'|'propagated'" src --glob '*.ts'
rg "source\\?:" src --glob '*.ts' | head -80
```

- [ ] **Step 1: Write failing merge tests (include rank migration)**

```ts
import { mergeDurableTags, tagRank } from './tagMerge';

describe('entity_pack merge', () => {
  it('ranks entity_pack above exemplar and prompt', () => {
    expect(tagRank({ label: 'a', category: 'person', score: 1, source: 'entity_pack' }))
      .toBeGreaterThan(tagRank({ label: 'a', category: 'person', score: 1, source: 'exemplar' }));
    expect(tagRank({ label: 'a', category: 'person', score: 1, source: 'ocr' }))
      .toBeGreaterThan(tagRank({ label: 'a', category: 'person', score: 1, source: 'entity_pack' }));
  });

  it('keeps ocr over entity_pack on same label', () => {
    const out = mergeDurableTags([
      { label: 'keanu reeves', category: 'person', score: 0.7, source: 'entity_pack' },
      { label: 'keanu reeves', category: 'person', score: 1, source: 'ocr' },
    ]);
    expect(out).toHaveLength(1);
    expect(out[0].source).toBe('ocr');
  });

  it('treats entity_pack as durable (not auto-capped away)', () => {
    const tags = [
      { label: 'person a', category: 'person', score: 0.9, source: 'entity_pack' as const },
      ...Array.from({ length: 20 }, (_, i) => ({
        label: `auto${i}`,
        category: 'topic',
        score: 0.5,
        source: 'prompt' as const,
      })),
    ];
    const out = mergeDurableTags(tags, 4);
    expect(out.some((t) => t.label === 'person a' && t.source === 'entity_pack')).toBe(true);
  });

  it('rank migration: propagated beats exemplar; ocr beats exemplar', () => {
    expect(tagRank({ label: 'a', category: 'x', score: 1, source: 'propagated' }))
      .toBeGreaterThan(tagRank({ label: 'a', category: 'x', score: 1, source: 'exemplar' }));
    expect(tagRank({ label: 'a', category: 'x', score: 1, source: 'ocr' }))
      .toBeGreaterThan(tagRank({ label: 'a', category: 'x', score: 1, source: 'exemplar' }));
  });
});
```

Spec ranks: `manual > propagated > ocr > entity_pack > exemplar > prompt > vision`.

- [ ] **Step 2: Verify red**

```bash
npx jest src/tagMerge.test.ts --watchman=false
```

- [ ] **Step 3: Implement**

`types.ts`:

```ts
source?:
  | 'prompt'
  | 'exemplar'
  | 'entity_pack'
  | 'ocr'
  | 'vision'
  | 'manual'
  | 'propagated';
```

`tagMerge.ts` ranks (numeric high = wins):

```ts
const TAG_RANK: Record<NonNullable<Tag['source']>, number> = {
  manual: 7,
  propagated: 6,
  ocr: 5,
  entity_pack: 4,
  exemplar: 3,
  vision: 2,
  prompt: 1,
};

const DURABLE_SOURCE: Partial<Record<NonNullable<Tag['source']>, true>> = {
  manual: true,
  propagated: true,
  ocr: true,        // literal text — keep durable so auto-cap cannot drop OCR identity
  entity_pack: true,
  exemplar: true,
};
```

**Note:** Making `ocr` durable is a small behavior change (OCR labels no longer count against `capAuto`). Matches identity goals; cover with test. If existing tests assume otherwise, update them deliberately.

- [ ] **Step 4: Verify green + fix exhaustiveness**

```bash
npx jest src/tagMerge.test.ts src/recognition.test.ts src/learnCore.test.ts --watchman=false
npm run typecheck
```

- [ ] **Step 5: Commit**

```bash
git add src/types.ts src/tagMerge.ts src/tagMerge.test.ts
git commit -m "feat(tags): entity_pack source and identity-aware merge ranks"
```

---

### Task 4: `entityRetrieve` pure module

**Files:**
- Create: `src/entityRetrieve.ts`
- Create: `src/entityRetrieve.test.ts`

- [ ] **Step 1: Failing tests**

```ts
import { retrieveEntities, entityHitsToTags, ANCHOR_BIAS } from './entityRetrieve';

function norm(v: number[]) {
  const n = Math.hypot(...v) || 1;
  return v.map((x) => x / n);
}

describe('retrieveEntities', () => {
  const keanu = norm([1, 0, 0, 0]);
  const other = norm([0, 1, 0, 0]);
  const img = norm([0.99, 0.1, 0, 0]);

  it('returns best positive per label above margin', () => {
    const hits = retrieveEntities({
      imageVec: img,
      exemplars: [
        { label: 'Keanu Reeves', category: 'person', vector: keanu, associations: ['neo'], positive: true },
        { label: 'Keanu Reeves', category: 'person', vector: norm([0.5, 0.5, 0, 0]), associations: ['neo'], positive: true },
        { label: 'Other', category: 'person', vector: other, associations: [], positive: true },
      ],
      anchorSim: 0.05,
      minMargin: 0.1,
      topK: 5,
    });
    expect(hits[0].label).toBe('Keanu Reeves');
    expect(hits[0].source).toBe('entity_pack');
    expect(hits[0].associations).toContain('neo');
  });

  it('ignores negative exemplars as candidates', () => {
    const hits = retrieveEntities({
      imageVec: img,
      exemplars: [
        { label: 'Not Keanu', category: 'person', vector: keanu, associations: [], positive: false },
      ],
      minMargin: 0.0,
    });
    expect(hits).toHaveLength(0);
  });

  it('maps hits to tags', () => {
    const tags = entityHitsToTags([
      { label: 'Keanu Reeves', category: 'person', score: 0.8, margin: 0.2, associations: ['neo'], source: 'entity_pack' },
    ]);
    expect(tags[0]).toMatchObject({ label: 'keanu reeves', source: 'entity_pack', score: 0.8 });
  });
});
```

Use same margin formula as recognition: `cos - ANCHOR_BIAS * anchorSim` with `ANCHOR_BIAS = 0.5`. For score, reuse `labelConfidence` from `recognition.ts` **or** a local linear map — prefer importing `labelConfidence` to avoid dual calibrations until entity-specific calibration exists.

- [ ] **Step 2: Red**

```bash
npx jest src/entityRetrieve.test.ts --watchman=false
```

- [ ] **Step 3: Implement `src/entityRetrieve.ts`**

Pure TS, no RN imports. Dot product on plain arrays. `topK` default 5, `minMargin` default `0.13` (export as `DEFAULT_ENTITY_MIN_MARGIN`).

- [ ] **Step 4: Green + commit**

```bash
npx jest src/entityRetrieve.test.ts --watchman=false
git add src/entityRetrieve.ts src/entityRetrieve.test.ts
git commit -m "feat(entity): pure entity pack retrieval"
```

---

### Task 5: Grounding + VLM skip policy

**Files:**
- Modify: `src/visionCore.ts`
- Modify: `src/visionCore.test.ts` (or create)
- Create: `src/visionSkip.ts`
- Create: `src/visionSkip.test.ts`

- [ ] **Step 1: Tests for grounding entities + skip**

```ts
// visionSkip.test.ts
import { shouldSkipAutoVision, type VisionSkipMode } from './visionSkip';

it('skips on recognized tier when mode is on-uncertain', () => {
  expect(
    shouldSkipAutoVision({
      mode: 'on-uncertain',
      recognitionTier: 'recognized',
      entityHits: [],
    })
  ).toBe(true);
});

it('skips on strong entity hit', () => {
  expect(
    shouldSkipAutoVision({
      mode: 'on-uncertain',
      recognitionTier: 'unknown',
      entityHits: [{ score: 0.7 }],
      entitySkipConfidence: 0.56,
    })
  ).toBe(true);
});

it('never skips when mode is never', () => {
  expect(
    shouldSkipAutoVision({
      mode: 'never',
      recognitionTier: 'recognized',
      entityHits: [{ score: 0.99 }],
    })
  ).toBe(false);
});
```

Grounding: when `entities` passed and non-empty, output includes `entities:`; weak empty entities + unknown tier still yields `NO_FORMAT_GROUNDING` behavior.

- [ ] **Step 2: Implement**

`visionSkip.ts`:

```ts
export type VisionSkipMode = 'never' | 'on-uncertain' | 'always';
export const DEFAULT_VISION_SKIP_MODE: VisionSkipMode = 'on-uncertain';
export const ENTITY_VLM_SKIP_CONF = 0.56;

export function shouldSkipAutoVision(p: {
  mode: VisionSkipMode;
  recognitionTier: 'recognized' | 'weak' | 'unknown';
  entityHits: { score: number }[];
  entitySkipConfidence?: number;
}): boolean {
  if (p.mode === 'never') return false;
  if (p.mode === 'always') return true;
  if (p.recognitionTier === 'recognized') return true;
  const thr = p.entitySkipConfidence ?? ENTITY_VLM_SKIP_CONF;
  return p.entityHits.some((h) => h.score >= thr);
}
```

`formatGrounding`: add optional 4th arg `entities?: GroundingLabel[]`; append entity facet line when present.

- [ ] **Step 3: Green + commit**

```bash
npx jest src/visionSkip.test.ts src/visionCore.test.ts --watchman=false
git add src/visionSkip.ts src/visionSkip.test.ts src/visionCore.ts src/visionCore.test.ts
git commit -m "feat(vision): entity grounding and auto-describe skip policy"
```

---

### Task 6: Wire indexer

**Files:**
- Modify: `src/indexer.ts`
- Modify: settings keys if needed (`src/visionCore.ts` constants + Settings UI minimal: can default constant first without UI)

- [ ] **Step 1: Locate classify/enrich merge site**

In `indexer.ts`, after visual tags + OCR merge and before/around VLM:
1. Load exemplars with `origin === 'pack'` **or all positives** (entity packs import as pack origin — use all positive exemplars for retrieve; user teaches included = good).
2. `retrieveEntities({ imageVec, exemplars, anchorSim })`.
3. `mergeDurableTags([...existing, ...entityHitsToTags(hits)])`.
4. Pass entity grounding labels into `userTurn` / `formatGrounding`.
5. Before `runVision`, if `shouldSkipAutoVision(...)`, skip generate; leave `vision_state` pending (document in code comment). Forced `enrichLibrary` / single describe should pass `force: true` that bypasses skip.

- [ ] **Step 2: Unit-test any extracted pure helper**; typecheck

```bash
npm run typecheck
npx jest --watchman=false
```

- [ ] **Step 3: Commit**

```bash
git add src/indexer.ts src/vision.tsx src/headlessVision.ts
git commit -m "feat(indexer): entity retrieval and conditional VLM skip"
```

---

## Chunk 3: Pack export from corpus (A.1 offline)

### Task 7: Entity mining + pack exporter

**Files:**
- Create: `tools/corpus/mine_entities.py`
- Create: `tools/corpus/export_packs.py`
- Create: `tools/corpus/test_export_packs.py`

- [ ] **Step 1: Scaffold seed + exporter tests (no real encoder)**

Create `tools/corpus/seed_entities.json`:

```json
[
  {
    "name": "Keanu Reeves",
    "type": "person",
    "aliases": ["Neo", "John Wick"],
    "match_tags": ["keanu", "reeves", "john wick", "neo"]
  }
]
```

Document in `tools/corpus/README.md`: seed is hand-maintained; mine only proposes additions.

Test pure selection logic:
- Group images by entity name.
- Require `N >= 5` distinct sha256s else skip.
- Cap `K <= 16` vectors (vectors injected by fake embed fn).
- Output JSON matches `TeachingPack` shape: `format`, `version`, `model`, `dim`, `exemplars[]`.

```python
def test_skips_entity_below_n():
    ...
def test_pack_shape_and_cap():
    ...
```

- [ ] **Step 2: Implement mine + export**

`mine_entities.py` v1 heuristics:
- Tags that are Title-Case multiword or match a person denylist/allowlist file.
- Optional: load a hand-maintained `tools/corpus/seed_entities.json` list of `{name, type, aliases, match_tags[]}` and attach entities to records whose tags intersect `match_tags`.
- Write `entities` onto a derived jsonl under corpus cache dir (outside repo or `tools/corpus/.cache/` gitignored).

`export_packs.py`:
- Args: `--model-id mobileclip-s2 --dim 512 --embed-backend open_clip|precomputed`
- Embed with same MobileCLIP-S2 as app (`clipmodel.py`).
- Write pack JSON to `tools/corpus/dist/packs/<name>.json` (gitignored binaries ok; commit only tiny fixture pack for tests).

- [ ] **Step 3: Manual smoke — build one tiny pack from seed**

```bash
python3 tools/corpus/export_packs.py --seed tools/corpus/seed_entities.json --out tools/corpus/dist/packs/
```

- [ ] **Step 4: Commit code + seed + gitignore cache**

```bash
git add tools/corpus .gitignore
git commit -m "feat(corpus): entity mine and teaching-pack exporter"
```

---

### Task 8: Entity eval slice (minimal)

**Files:**
- Create: `tools/eval/entity-cases.sample.json`
- Create: `src/entityEval.ts` + test **or** extend `taggingEval` with entity cases

- [ ] **Step 1:** Define 3–5 hand cases shape `{ id, mustFind: string[], tags: predicted[] }` for offline scoring without images.
- [ ] **Step 2:** `npm run` script `entitytest` once real predictions exist; until then sample + unit test of scorer only.
- [ ] **Step 3: Commit**

```bash
git commit -m "test(eval): entity findability scorer scaffold"
```

---

## Chunk 4: Track B training loop

### Task 9: Entity text views

**Files:**
- Modify: `tools/finetune/textviews.py`
- Create: `tools/finetune/test_textviews.py`

- [ ] **Step 1: Tests**

```python
from textviews import training_views, entity_views

def test_entity_views_include_photo_and_meme_frames():
    v = entity_views("Keanu Reeves", ["Neo"], "person")
    assert any("Keanu Reeves" in x and "photo" in x.lower() for x in v)
    assert any("meme" in x.lower() for x in v)
    assert any("Neo" in x for x in v)

def test_training_views_merges_tags_and_entities():
    # extend signature: training_views(tags, entities=None)
    ...
```

- [ ] **Step 2: Implement + keep `training_views(tags)` backward compatible**

```python
def training_views(tags: list[str], entities: list[dict] | None = None) -> list[str]:
    views = ...existing...
    for ent in entities or []:
        views.extend(entity_views(ent["name"], ent.get("aliases") or [], ent.get("type") or "other"))
    return dedupe(views)
```

- [ ] **Step 3: Wire `finetune.py` / `adapter.py` to pass entities when present on records**

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(finetune): entity-aware contrastive text views"
```

---

### Task 10: Train text tower (operator machine)

**Files:** none in app; outputs under `tools/finetune/runs/` (gitignored)

- [ ] **Step 1:** Run freeze-image text FT on corpus train split (existing `finetune.py`).
- [ ] **Step 2:** Rebuild golden text query vectors; `npm run eval` + recognition with new label-vectors if text space moved.
- [ ] **Step 3:** Write `tools/finetune/B1_NOTES.md` with before/after tables. **Stop if gates regress.**
- [ ] **Step 4:** Commit notes only (not multi-hundred-MB weights unless release process).

---

### Task 11: Export + MODEL_ID (only if B1/B2 accept)

**Files:**
- Modify: export scripts / env docs
- Rebuild packs with new model id
- `src/embeddingModels.ts` only if default model id changes in-tree

- [ ] Follow `docs/memedepot-finetune.md` export contract.
- [ ] **Unit test before ship:** extend `src/embeddingModels.test.ts` / teaching-pack tests so `isTeachingPackCompatible` rejects the previous MODEL_ID and accepts the new one after bump.
- [ ] Verify stale pack rejected on import path; new pack imports + re-tag.
- [ ] On-device: load towers, re-index smoke.
- [ ] Commit wiring + docs; release weights via existing GitHub release flow if approved legally.

---

## Chunk 5: Track C0 spike (optional, timeboxed)

### Task 12: Vulkan/export go-no-go

**Files:**
- Create: `docs/superpowers/specs/c0-vlm-spike-notes.md`

- [ ] **Step 1:** Timebox 1–2 days per `docs/vlm-model-decision.md` appendix (SmolVLM or Gemma LoRA export).
- [ ] **Step 2:** Record delegate blob counts + latency vs Gemma E2B.
- [ ] **Step 3:** Explicit **GO** or **NO-GO**. No app wiring on NO-GO.
- [ ] **Step 4: Commit notes only.**

---

## Chunk 6: Docs + vault closeout

### Task 13: Spec status + README pointer

**Files:**
- Modify: spec status → `Implementing` / `Partial`
- Modify: `README.md` culture-layer section — one paragraph on entity packs + corpus loop
- Modify: `docs/composite-meme-understanding.md` — Stage 3 pointer to entity packs (no cloud)

- [ ] **Step 1: Edit docs**
- [ ] **Step 2: `npm run typecheck` && full jest**
- [ ] **Step 3: Commit**

```bash
git commit -m "docs: link pop-culture A/B path into README and composite stages"
```

---

## Verification matrix (definition of done per chunk)

| Chunk | Done when |
|---|---|
| 1 | `cli.py status` works on real archive; B0 notes exist (or blocked with reason) |
| 2 | jest green; entity retrieve + skip + merge ranks shipped; typecheck clean |
| 3 | exporter enforces N≥5; at least one seed pack JSON produced |
| 4 | textviews tests green; B1 notes with metrics if train run |
| 5 | GO/NO-GO written |
| 6 | docs consistent |

---

## Risks for implementers

1. **Rank change** for `exemplar` vs `ocr` / durable OCR — update any tests that encoded old ranks.  
2. **Indexer complexity** — keep retrieve pure; don’t embed pack logic inside React.  
3. **Do not commit scraped images** or huge `.pte` without release process.  
4. **Skip ≠ done** — users must still be able to force describe.  
5. **Calibration** — entity margins may need separate tune; start with recognition constants, measure entity slice before shipping aggressive skip.

---

## Suggested first worker session

Execute **Chunk 1 Task 1** only (corpus load + status), then **Chunk 2 Tasks 3–5** (pure app cores) in parallel if two agents — they do not conflict. **Do not** train (Chunk 4) until corpus status and entity path unit tests are green.
