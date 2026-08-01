import type { Tag } from './types';

const TAG_RANK: Record<NonNullable<Tag['source']>, number> = {
  manual: 6,
  exemplar: 5,
  propagated: 4,
  ocr: 3,
  vision: 2,
  prompt: 1,
};

const DURABLE_SOURCE: Partial<Record<NonNullable<Tag['source']>, true>> = {
  manual: true,
  exemplar: true,
  propagated: true,
};

export function normalizeTagLabel(label: string): string {
  return label.trim().toLowerCase();
}

export function tagRank(t: Tag): number {
  return TAG_RANK[t.source ?? 'prompt'] ?? 1;
}

function isDurableTag(t: Tag): boolean {
  return t.source ? DURABLE_SOURCE[t.source] === true : false;
}

function betterTag(a: Tag, b: Tag): Tag {
  const ar = tagRank(a);
  const br = tagRank(b);
  if (ar !== br) return ar > br ? a : b;
  return a.score >= b.score ? a : b;
}

function sortTags(tags: Tag[]): Tag[] {
  return [...tags].sort((a, b) => tagRank(b) - tagRank(a) || b.score - a.score);
}

// Merge and de-dupe tags without ever letting the auto-tag cap evict a user-owned
// label. `capAuto` bounds only model/OCR filler; manual, exemplar, and propagated
// tags are durable because they came directly from the user's tagging intent.
export function mergeDurableTags(tags: Tag[], capAuto = 6): Tag[] {
  const best = new Map<string, Tag>();
  for (const t of tags) {
    const label = normalizeTagLabel(t.label);
    if (!label) continue;
    const normalized = t.label === label ? t : { ...t, label };
    const cur = best.get(label);
    best.set(label, cur ? betterTag(normalized, cur) : normalized);
  }

  const durable: Tag[] = [];
  const auto: Tag[] = [];
  for (const t of best.values()) {
    if (isDurableTag(t)) durable.push(t);
    else auto.push(t);
  }
  return [...sortTags(durable), ...sortTags(auto).slice(0, capAuto)];
}

export function termsWithLabel(extraTerms: string, label: string): string {
  const set = new Set(extraTerms.split(/\s+/).filter(Boolean));
  for (const w of normalizeTagLabel(label).split(/\s+/)) if (w) set.add(w);
  return [...set].join(' ');
}

export function upsertDurableTag(
  tags: Tag[],
  extraTerms: string,
  tag: { label: string; category: string; source: 'manual' | 'exemplar' | 'propagated'; score?: number }
): { tags: Tag[]; extraTerms: string } {
  const label = normalizeTagLabel(tag.label);
  if (!label) return { tags: mergeDurableTags(tags), extraTerms };
  const next: Tag = {
    label,
    category: tag.category,
    score: tag.score ?? 1,
    source: tag.source,
  };
  return {
    tags: mergeDurableTags([...tags.filter((t) => normalizeTagLabel(t.label) !== label), next]),
    extraTerms: termsWithLabel(extraTerms, label),
  };
}
