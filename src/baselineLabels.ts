// Bundled tagging baseline harvested from memedepot.
//
// memedepot depots are curated *by meme format / character*, so the popular tags
// there are a real-world, frequency-ranked vocabulary of the templates people
// actually care about. Folding a capped, de-duplicated slice of them into the
// zero-shot label set gives a fresh install a much broader "knowledge" layer on
// day one — without a human hand-writing every entry.
//
// This is intentionally a SEPARATE, machine-generated tier from the hand-authored
// `CURATED_MEME_LABELS`: the curated prompts are the quality core; the baseline
// is breadth. The committed data file (`data/memedepotBaseline.json`) ships
// EMPTY and is populated by CI — the `harvest-memedepot-tags` workflow runs
// `tools/memedepot/harvest.mjs` on a GitHub runner (which, unlike the app and the
// dev sandbox, can reach memedepot) and opens a PR with the regenerated file. So
// until a harvest PR lands, this module contributes nothing and
// `MEME_LABELS === CURATED_MEME_LABELS`.
//
// Every extra label is another text embedding to compute once, another
// comparison per image, and another chance at a false-positive tag — so the
// baseline is deliberately bounded (`MAX_BASELINE_LABELS`) and should be tuned
// against the search-quality eval harness (see docs/memedepot-corpus.md).

import baseline from './data/memedepotBaseline.json';
import basedmemesBaseline from './data/basedmemesBaseline.json';
import type { LabelDef } from './memeLabels';

// One harvested tag as it appears in the generated JSON. `prompt` is a
// template ("a <tag> meme") authored by the harvester; `count` is the
// cross-depot frequency used to rank breadth.
export interface BaselineTag {
  label: string;
  prompt: string;
  category: string;
  associations?: string[];
  count?: number;
}

interface BaselineFile {
  source?: string;
  generatedAt?: string | null;
  labels?: BaselineTag[];
}

const VALID_CATEGORIES: readonly LabelDef['category'][] = [
  'format',
  'character',
  'emotion',
  'topic',
  'person',
  // Facets that make a meme findable by a natural-language description of ANY
  // aspect — a verb, a prop, a place, the moment you'd send it, the humor style.
  'action',
  'object',
  'setting',
  'situation',
  'tone',
];

// Cap on how many harvested labels become active zero-shot classes. Bounded so a
// noisy long tail can't swamp the curated core or slow classification. Tune with
// the eval harness before raising.
export const MAX_BASELINE_LABELS = 150;

const file = baseline as BaselineFile;
const basedmemesFile = basedmemesBaseline as BaselineFile;

export const BASELINE_META = {
  source: file.source ?? 'memedepot.com',
  generatedAt: file.generatedAt ?? null,
  total: Array.isArray(file.labels) ? file.labels.length : 0,
} as const;

const normLabel = (s: string): string => s.trim().toLowerCase();

// Turn harvested tags into LabelDefs: rank by frequency, drop anything already
// covered by the curated set (case-insensitive), sanitize categories, dedupe,
// and cap. Pure and curated-injected so `memeLabels.ts` can compose the two
// tiers without an import cycle.
export function buildBaselineLabels(
  curated: LabelDef[],
  tags: BaselineTag[] = file.labels ?? [],
  max: number = MAX_BASELINE_LABELS
): LabelDef[] {
  if (!Array.isArray(tags) || tags.length === 0 || max <= 0) return [];

  const taken = new Set(curated.map((d) => normLabel(d.label)));
  const ranked = [...tags].sort((a, b) => (b?.count ?? 0) - (a?.count ?? 0));
  const out: LabelDef[] = [];

  for (const t of ranked) {
    if (out.length >= max) break;
    const label = typeof t?.label === 'string' ? t.label.trim() : '';
    const prompt = typeof t?.prompt === 'string' ? t.prompt.trim() : '';
    if (!label || !prompt) continue;

    const key = normLabel(label);
    if (taken.has(key)) continue; // curated wins; also de-dupes within the baseline
    taken.add(key);

    const category = (VALID_CATEGORIES as string[]).includes(t.category)
      ? (t.category as LabelDef['category'])
      : 'topic';
    const associations = Array.isArray(t.associations)
      ? t.associations.map((a) => String(a).trim()).filter(Boolean)
      : [];

    out.push({
      label,
      prompt,
      category,
      ...(associations.length ? { associations } : {}),
    });
  }
  return out;
}

// Cap on the SECOND machine-generated tier — mined from the local basedmemes.lol
// + Know Your Meme archive (see tools/basedmemes). Same bounding rationale as
// MAX_BASELINE_LABELS: breadth without swamping the curated core. This tier is
// deduped AGAINST both the curated core and the memedepot tier, so it only
// contributes vocabulary neither of those already covers.
export const MAX_BASEDMEMES_LABELS = 150;

export const BASEDMEMES_META = {
  source: basedmemesFile.source ?? 'basedmemes.lol + knowyourmeme.com',
  generatedAt: basedmemesFile.generatedAt ?? null,
  total: Array.isArray(basedmemesFile.labels) ? basedmemesFile.labels.length : 0,
} as const;

// Compose BOTH machine-generated breadth tiers under a curated core, with a
// single shared dedup so no label is emitted twice. The memedepot tier leads
// (higher-signal, human-adjacent depot vocabulary); the basedmemes tier fills in
// behind it, deduped against curated + the first tier. Order is preserved:
// [...memedepot, ...basedmemes].
export function buildAllBaselineLabels(curated: LabelDef[]): LabelDef[] {
  const firstTier = buildBaselineLabels(curated, file.labels ?? [], MAX_BASELINE_LABELS);
  const secondTier = buildBaselineLabels(
    [...curated, ...firstTier],
    basedmemesFile.labels ?? [],
    MAX_BASEDMEMES_LABELS
  );
  return [...firstTier, ...secondTier];
}
