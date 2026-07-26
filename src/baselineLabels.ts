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
import twetchBaseline from './data/twetchBaseline.json';
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

// Cap on the SECOND (basedmemes.lol + KYM) breadth tier. Separate from the
// memedepot cap so each source can be tuned independently against the eval
// harness. Kept modest for the same reason: every label is another embedding
// and another false-positive chance.
export const MAX_BASEDMEMES_LABELS = 150;

// Cap on the THIRD (Twetch meme library) breadth tier — currently ZERO, on
// purpose.
//
// Twetch is the best-shaped source we have: 6.5k memes, human-applied tags,
// folders curated by format. But `npm run recognition` says the tier does not
// pay for itself, on two independent corpora:
//
//                           holdout (595 KYM/basedmemes)   a real 2k library
//   399 labels                546 right / 632 wrong        140/323 correct
//   + 3 twetch folder names   545 / 633                    140/323
//   + 20 twetch labels        542 / 638                    140/323
//   + 60 twetch labels        540 / 649                    140/325
//
// The reason is in the data, not the source: 10 of Twetch's 14 folders (Pepe,
// Wojak, Bobo, Brainlet, Boomer, Apu, Grug, Zoomer, Reaction…) are ALREADY in
// the vocabulary from the curated core and the earlier tiers, so every slot
// this tier wins goes to what is left — generic aspect words (Outdoors, Laugh,
// Fight, Work), which measure as weak visual classes and add false positives.
//
// The harvest still ships and still runs (tools/twetch): it is the input the
// eval needs, and the day Twetch adds formats the other sources lack, raising
// this number is a one-line change that the harness can justify.
export const MAX_TWETCH_LABELS = 0;

const file = baseline as BaselineFile;
const basedmemesFile = basedmemesBaseline as BaselineFile;
const twetchFile = twetchBaseline as BaselineFile;

export const BASELINE_META = {
  source: file.source ?? 'memedepot.com',
  generatedAt: file.generatedAt ?? null,
  total: Array.isArray(file.labels) ? file.labels.length : 0,
} as const;

export const BASEDMEMES_META = {
  source: basedmemesFile.source ?? 'basedmemes.lol + knowyourmeme.com',
  generatedAt: basedmemesFile.generatedAt ?? null,
  total: Array.isArray(basedmemesFile.labels) ? basedmemesFile.labels.length : 0,
} as const;

export const TWETCH_META = {
  source: twetchFile.source ?? 'twetch.com',
  generatedAt: twetchFile.generatedAt ?? null,
  total: Array.isArray(twetchFile.labels) ? twetchFile.labels.length : 0,
} as const;

const normLabel = (s: string): string => s.trim().toLowerCase();

// Turn harvested tags into LabelDefs: rank by frequency, drop anything already
// covered by the curated set (case-insensitive), sanitize categories, dedupe,
// and cap. Pure and curated-injected so `memeLabels.ts` can compose the
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

// Compose EVERY machine-generated breadth tier under the curated core, sharing
// one de-dupe pass so a term can appear in at most one place. The trick is to
// reuse the pure `buildBaselineLabels` for each tier: each one de-dupes against
// `curated` PLUS the tiers already built (passed as the `curated` arg), so an
// earlier source always wins a shared term and none collide with the curated
// labels. Order is source quality: memedepot, then basedmemes+KYM, then Twetch.
//
// Twetch runs last not because it is worst — its folders are hand-curated and
// its tags are human-applied — but because by the time it runs, the formats it
// shares with the other sources (pepe, wojak, bobo) are already covered, so its
// slots go to what only it has: the on-chain/BSV corner of meme culture.
export function buildAllBaselineLabels(curated: LabelDef[]): LabelDef[] {
  const memedepotTags = file.labels ?? [];
  const basedmemesTags = basedmemesFile.labels ?? [];
  const twetchTags = twetchFile.labels ?? [];

  const firstTier = buildBaselineLabels(curated, memedepotTags, MAX_BASELINE_LABELS);
  const secondTier = buildBaselineLabels(
    [...curated, ...firstTier],
    basedmemesTags,
    MAX_BASEDMEMES_LABELS
  );
  const thirdTier = buildBaselineLabels(
    [...curated, ...firstTier, ...secondTier],
    twetchTags,
    MAX_TWETCH_LABELS
  );

  return [...firstTier, ...secondTier, ...thirdTier];
}
