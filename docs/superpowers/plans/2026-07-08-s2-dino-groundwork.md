# S2/DINO Groundwork Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prepare Memeget for MobileCLIP-S2 as a future primary text/image encoder and DINOv2 as a future visual-similarity encoder without pretending either model is currently available in `react-native-executorch`.

**Architecture:** Add a small model-space registry that names current and candidate embedding spaces, centralizes model/dimension stamps, and exposes compatibility helpers. Use those stamps in teaching packs now, and add nullable visual-embedding storage plus pure routing helpers so DINOv2 can later power "More like this" without changing search semantics.

**Tech Stack:** TypeScript, Jest, Expo SQLite, React Native ExecuTorch.

---

### Task 1: Model-Space Registry

**Files:**
- Create: `src/embeddingModels.ts`
- Create: `src/embeddingModels.test.ts`

- [ ] **Step 1: Write failing tests**

Create `src/embeddingModels.test.ts` with tests that require:
- the current primary model stamp is `clip-vit-base-patch32@512`
- MobileCLIP-S2 and DINOv2 are represented as unavailable future candidates
- teaching-pack compatibility accepts only the current primary image space

- [ ] **Step 2: Verify red**

Run: `npx jest src/embeddingModels.test.ts --watchman=false`
Expected: FAIL because `src/embeddingModels.ts` does not exist.

- [ ] **Step 3: Implement registry**

Create `src/embeddingModels.ts` with:
- `EmbeddingSpace` union type: `primary` and `visual`
- `EmbeddingModelSpec`
- `PRIMARY_EMBEDDING_MODEL`
- `VISUAL_EMBEDDING_MODEL`
- `FUTURE_EMBEDDING_MODELS`
- `modelStamp(spec)`
- `isTeachingPackCompatible(model, dim)`

- [ ] **Step 4: Verify green**

Run: `npx jest src/embeddingModels.test.ts --watchman=false`
Expected: PASS.

### Task 2: Teaching-Pack Stamps

**Files:**
- Modify: `src/teachingPack.ts`
- Test: `src/embeddingModels.test.ts`

- [ ] **Step 1: Write failing compatibility test**

Extend `src/embeddingModels.test.ts` to assert that `PACK_MODEL` and `PACK_DIM` equal `PRIMARY_EMBEDDING_MODEL.id` and `.dim`.

- [ ] **Step 2: Verify red**

Run: `npx jest src/embeddingModels.test.ts --watchman=false`
Expected: FAIL until `teachingPack.ts` imports the shared constants.

- [ ] **Step 3: Wire constants**

Modify `src/teachingPack.ts` to import `PRIMARY_EMBEDDING_MODEL` and derive `PACK_MODEL` / `PACK_DIM` from it.

- [ ] **Step 4: Verify green**

Run: `npx jest src/embeddingModels.test.ts --watchman=false`
Expected: PASS.

### Task 3: Visual-Embedding Groundwork

**Files:**
- Modify: `src/db.ts`
- Create: `src/visualSearch.ts`
- Create: `src/visualSearch.test.ts`

- [ ] **Step 1: Write failing tests**

Create `src/visualSearch.test.ts` with tests that require:
- `selectVisualSimilarityVector` returns `visualEmbedding` when the model stamp matches the active visual model
- falls back to `imageEmbedding` when visual embedding is missing, stale, or DINOv2 is unavailable
- `visualEmbeddingNeedsRefresh` is true when no visual vector exists for the active visual model

- [ ] **Step 2: Verify red**

Run: `npx jest src/visualSearch.test.ts --watchman=false`
Expected: FAIL because `src/visualSearch.ts` does not exist.

- [ ] **Step 3: Implement helper**

Create `src/visualSearch.ts` with pure helpers and no Expo imports.

- [ ] **Step 4: Add nullable DB columns**

Modify `src/db.ts`:
- `visual_embedding BLOB`
- `visual_model TEXT NOT NULL DEFAULT ''`
- migration `ALTER TABLE` clauses
- `MemeRow` fields
- preserve existing CLIP fallback behavior in `getSimilarMemes`

- [ ] **Step 5: Verify green**

Run: `npx jest src/visualSearch.test.ts --watchman=false`
Expected: PASS.

### Task 4: Documentation

**Files:**
- Modify: `docs/embedding-roadmap.md`
- Modify: `README.md`

- [ ] **Step 1: Update roadmap**

Document the concrete groundwork now in place:
- shared model-space stamps
- teaching-pack compatibility gate
- nullable DINO visual vector slot
- current CLIP fallback until a native/custom export exists

- [ ] **Step 2: Update README roadmap line**

Mention the app is schema-ready for DINO visual similarity and has model stamps for S2 migration, but still requires actual model exports.

### Task 5: Verification, Commit, Push

**Files:**
- All modified files

- [ ] **Step 1: Run typecheck**

Run: `npm run typecheck`
Expected: exit 0.

- [ ] **Step 2: Run tests**

Run: `npx jest --watchman=false`
Expected: all suites pass.

- [ ] **Step 3: Commit**

Run:
```bash
git add docs/superpowers/plans/2026-07-08-s2-dino-groundwork.md src/embeddingModels.ts src/embeddingModels.test.ts src/visualSearch.ts src/visualSearch.test.ts src/teachingPack.ts src/db.ts docs/embedding-roadmap.md README.md
git commit -m "Prepare embedding spaces for S2 and DINO"
```

- [ ] **Step 4: Push**

Run: `git push`
Expected: branch pushes to `origin/claude/gemma-local-vl-model-7viqpi`.
