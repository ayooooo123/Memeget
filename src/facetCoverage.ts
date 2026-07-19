// Facet-coverage scorer — the yardstick for the VLM prompt-tuning loop.
//
// The aspect-search eval proved single-word search rides on the facet word being
// written into a meme's tags. So the question a prompt change has to answer is:
// "of the memes it describes, what fraction actually get a tag in EACH facet?"
// (an action, an emotion, the situation, …). This scores exactly that, using the
// app's own MEME_LABELS taxonomy to decide which facet a free tag belongs to —
// so a prompt tweak that finally makes the model emit situation/action tags
// shows up as coverage going up, measured, not eyeballed.
//
// Input is whatever the model produced: a list of described memes with their
// tags (export a sample from a device — see tools/eval/README.md). No model or
// native runtime here; it's pure and unit-testable.

import { MEME_LABELS, type LabelCategory } from './memeLabels';

export const FACETS: LabelCategory[] = [
  'format',
  'character',
  'person',
  'action',
  'object',
  'setting',
  'emotion',
  'situation',
  'tone',
  'topic',
];

// A compact hand-curated lexicon of everyday facet words. MEME_LABELS names are
// meme-specific ("Smug", "Distracted Boyfriend") and miss the plain vocabulary a
// VLM actually emits ("jealousy", "calm", "procrastinating"), which would make
// coverage read falsely low for the abstract facets. This backfills the common
// cases; extend it as the model's vocabulary reveals gaps.
const FACET_LEXICON: Partial<Record<LabelCategory, string[]>> = {
  emotion: [
    'happy', 'sad', 'angry', 'anger', 'fear', 'scared', 'afraid', 'anxious', 'anxiety',
    'calm', 'excited', 'proud', 'pride', 'embarrassed', 'disgust', 'disgusted', 'joy',
    'love', 'lonely', 'loneliness', 'confident', 'confidence', 'nervous', 'shocked',
    'surprised', 'hopeful', 'nostalgic', 'nostalgia', 'jealous', 'jealousy', 'frustrated',
    'annoyed', 'bored', 'boredom', 'relief', 'relieved', 'guilt', 'shame', 'awe',
  ],
  action: [
    'running', 'walking', 'sitting', 'standing', 'dancing', 'eating', 'drinking',
    'laughing', 'crying', 'screaming', 'yelling', 'pointing', 'staring', 'sipping',
    'flexing', 'hugging', 'falling', 'jumping', 'typing', 'sleeping', 'reading',
    'driving', 'fighting', 'hiding', 'chasing', 'waving', 'nodding', 'shrugging',
    // seen in real teachings the app was mis-filing as "character":
    'thinking', 'drooling', 'smiling', 'clapping', 'kneeling', 'shushing',
  ],
  situation: [
    'procrastinating', 'procrastination', 'avoiding', 'arguing', 'waiting', 'failing',
    'winning', 'coping', 'regret', 'regretting', 'celebrating', 'giving up', 'overthinking',
    'pretending', 'realizing', 'panicking', 'relatable', 'awkward', 'flexing on',
    'when you', 'me when', 'trying to', 'about to',
    // reaction/use phrasings the model actually emits (were landing unclassified)
    'ignoring', 'ignore', 'busy', 'being busy', 'tired', 'exhausted', 'stressed',
    'overwhelmed', 'forgetting', 'remembering', 'missing', 'stuck', 'struggling',
    'quitting', 'escaping', 'dodging', 'hiding', 'refusing', 'denying', 'bragging',
    'showing off', 'messing up', 'no messages', "can't reply", 'cant reply', 'slow down',
    'self-affirmation', 'asserting', 'sleep deprivation', 'no explanation', 'wanting',
    'be quiet', 'keep it a secret', 'keep quiet',
  ],
  tone: [
    'ironic', 'irony', 'sarcastic', 'sarcasm', 'absurd', 'surreal', 'wholesome', 'dark',
    'edgy', 'deadpan', 'satire', 'parody', 'cringe', 'unhinged', 'chaotic', 'ominous',
  ],
  object: [
    'phone', 'computer', 'laptop', 'car', 'money', 'food', 'weapon', 'sword', 'book',
    'screen', 'door', 'window', 'clock', 'mirror', 'bottle', 'cup',
  ],
  setting: [
    'bedroom', 'bathroom', 'street', 'road', 'beach', 'park', 'restaurant', 'bar',
    'hospital', 'church', 'prison', 'store', 'shop', 'club', 'stadium', 'arena', 'home',
  ],
  // Public figures that recur as meme subjects — mostly harvested from real
  // teachings the classifier was filing as 'character' for lack of a name list.
  person: [
    'trump', 'obama', 'biden', 'elon', 'musk', 'putin', 'xi jinping', 'epstein',
    'alex jones', 'nick fuentes', 'mamdani', 'jd vance', 'sam hyde', 'ben affleck',
    'mel gibson', 'ryan gosling', 'haaland', 'ansem', 'tim dillon', 'john kiriakou',
    'nigel farage', 'tony soprano', 'walter white', 'patrick bateman', 'andrew tate',
    'joe rogan', 'kanye', 'messi', 'ronaldo', 'zelensky', 'vance',
  ],
  topic: [
    'china', 'russia', 'ukraine', 'monero', 'bitcoin', 'ethereum', 'immigration',
    'immigrants', 'nazi', 'communism', 'wwe', 'ufc', 'soccer', 'football', 'nba',
  ],
};

// Per-facet keyword sets built from the label vocabulary + the lexicon above.
// Single words match a tag's words; multi-word keywords match as a substring.
interface FacetKeywords {
  words: Map<LabelCategory, Set<string>>; // single-token keywords
  phrases: Map<LabelCategory, Set<string>>; // multi-token keywords
}

function buildKeywords(): FacetKeywords {
  const words = new Map<LabelCategory, Set<string>>();
  const phrases = new Map<LabelCategory, Set<string>>();
  for (const f of FACETS) {
    words.set(f, new Set());
    phrases.set(f, new Set());
  }
  const add = (cat: LabelCategory, term: string) => {
    const t = term.trim().toLowerCase();
    if (t.length < 3) return;
    (t.includes(' ') ? phrases : words).get(cat)?.add(t);
  };
  for (const l of MEME_LABELS) {
    if (!FACETS.includes(l.category)) continue;
    // The label's own name, split into words, plus the whole name as a phrase.
    for (const w of l.label.toLowerCase().split(/[^a-z0-9]+/).filter(Boolean)) add(l.category, w);
    add(l.category, l.label);
    for (const a of l.associations ?? []) add(l.category, a);
  }
  for (const f of FACETS) for (const term of FACET_LEXICON[f] ?? []) add(f, term);
  return { words, phrases };
}

const KW = buildKeywords();

// Which facets a single tag belongs to. A tag can hit more than one (e.g.
// "smug pepe" → character + emotion).
export function tagFacets(tag: string): LabelCategory[] {
  const t = tag.trim().toLowerCase();
  if (!t) return [];
  const tagWords = new Set(t.split(/[^a-z0-9]+/).filter(Boolean));
  const out: LabelCategory[] = [];
  for (const f of FACETS) {
    const wordHit = [...(KW.words.get(f) ?? [])].some((w) => tagWords.has(w));
    const phraseHit = !wordHit && [...(KW.phrases.get(f) ?? [])].some((p) => t.includes(p));
    if (wordHit || phraseHit) out.push(f);
  }
  return out;
}

// Best single facet for a label — used to categorize a teaching/exemplar from
// its text instead of assuming everything is a character. Identity facets win
// over aspect facets when a label matches several (a named character that also
// reads as an emotion is filed as the character); unknown labels keep the
// caller's fallback.
const FACET_PRIORITY: LabelCategory[] = [
  'character',
  'person',
  'format',
  'action',
  'object',
  'setting',
  'situation',
  'emotion',
  'tone',
  'topic',
];

export function guessFacet(label: string, fallback: LabelCategory = 'character'): LabelCategory {
  const hits = new Set(tagFacets(label));
  for (const f of FACET_PRIORITY) if (hits.has(f)) return f;
  return fallback;
}

export interface DescribedMeme {
  id?: string;
  tags: string[];
}

export interface CoverageMetrics {
  n: number; // memes scored
  perFacet: Record<string, number>; // fraction of memes with ≥1 tag in this facet
  avgFacetsPerMeme: number; // mean distinct facets covered per meme
  avgTagsPerMeme: number;
  unclassifiedRate: number; // fraction of tags that matched no facet
}

export function facetCoverage(memes: DescribedMeme[]): CoverageMetrics {
  const n = memes.length;
  const denom = n || 1;
  const facetCounts: Record<string, number> = Object.fromEntries(FACETS.map((f) => [f, 0]));
  let facetsSum = 0;
  let tagsSum = 0;
  let tagsTotal = 0;
  let unclassified = 0;

  for (const m of memes) {
    const covered = new Set<LabelCategory>();
    for (const tag of m.tags) {
      const fs = tagFacets(tag);
      if (fs.length === 0) unclassified++;
      for (const f of fs) covered.add(f);
    }
    for (const f of covered) facetCounts[f]++;
    facetsSum += covered.size;
    tagsSum += m.tags.length;
    tagsTotal += m.tags.length;
  }

  const perFacet: Record<string, number> = {};
  for (const f of FACETS) perFacet[f] = facetCounts[f] / denom;
  return {
    n,
    perFacet,
    avgFacetsPerMeme: facetsSum / denom,
    avgTagsPerMeme: tagsSum / denom,
    unclassifiedRate: tagsTotal ? unclassified / tagsTotal : 0,
  };
}

export function formatCoverage(m: CoverageMetrics): string {
  const pct = (x: number) => `${(x * 100).toFixed(0)}%`.padStart(4);
  const lines = [
    `memes:     ${m.n}   (avg ${m.avgTagsPerMeme.toFixed(1)} tags, ${m.avgFacetsPerMeme.toFixed(1)} facets each)`,
    `unclassified tags: ${pct(m.unclassifiedRate)}`,
    'coverage per facet (fraction of memes with a tag in it):',
  ];
  // Weakest facets last so they're the eye's last stop — those are the ones a
  // prompt change should target.
  const ranked = [...FACETS].sort((a, b) => m.perFacet[b] - m.perFacet[a]);
  for (const f of ranked) lines.push(`  ${f.padEnd(10)} ${pct(m.perFacet[f])}`);
  return lines.join('\n');
}
