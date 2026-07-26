// Pure assembly of a meme's searchable text — the lexical haystack single-word
// queries hit via `.includes`. Factored out of indexer.ts (which pulls in native
// modules) and db.ts so this logic is unit-testable on its own: it's the exact
// path a VLM's facet tags travel to become findable, and the aspect-search eval
// showed that path is what single-word search depends on.

// The VLM's open-vocabulary output (verbatim TEXT, SUBJECTS, TAGS) plus the
// curated/association terms, lowercased into one extra-terms blob. This is where
// the facet words (action/emotion/situation/object/…) the model emits enter the
// index.
const CONTEXT_CONCEPT_LIMIT = 8;
const CONTEXT_PHRASE_LIMIT = 24;

function normalizedConcept(raw: string): string {
  return raw
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function addUniqueTerm(out: string[], seen: Set<string>, raw: string): void {
  const term = normalizedConcept(raw);
  if (!term || seen.has(term)) return;
  seen.add(term);
  out.push(term);
}

export function classificationContextTerms(res: {
  caption?: string;
  subjects?: string[];
  tags: string[];
}): string {
  const concepts: string[] = [];
  const seen = new Set<string>();
  for (const term of [...res.tags, ...(res.subjects ?? [])]) {
    addUniqueTerm(concepts, seen, term);
    if (concepts.length >= CONTEXT_CONCEPT_LIMIT) break;
  }

  const context: string[] = [];
  const contextSeen = new Set<string>();
  for (const tag of concepts.slice(0, 5)) {
    for (const other of concepts) {
      if (tag === other) continue;
      addUniqueTerm(context, contextSeen, `${tag} ${other}`);
      if (context.length >= CONTEXT_PHRASE_LIMIT) return context.join(' ');
    }
  }

  const caption = normalizedConcept(res.caption ?? '');
  const captionWords = caption.split(' ').filter((w) => w.length > 3);
  for (let i = 0; i < captionWords.length - 1; i++) {
    addUniqueTerm(context, contextSeen, `${captionWords[i]} ${captionWords[i + 1]}`);
    if (context.length >= CONTEXT_PHRASE_LIMIT) break;
  }
  return context.join(' ');
}

export function memeExtraTerms(
  curatedTerms: string,
  res: { caption?: string; text: string; subjects: string[]; tags: string[] }
): string {
  const extra = [res.text, res.subjects.join(' '), res.tags.join(' '), classificationContextTerms(res)]
    .join(' ')
    .toLowerCase();
  return `${curatedTerms} ${extra}`.replace(/\s+/g, ' ').trim();
}

// The text embedded as the caption vector: caption + tag labels + extra terms.
export function captionSearchText(
  caption: string,
  tags: { label: string }[],
  extraTerms: string
): string {
  return [caption, tags.map((t) => t.label).join(' '), extraTerms]
    .join(' ')
    .replace(/\s+/g, ' ')
    .trim();
}

// The full stored haystack for one meme, mirroring db.ts's rowSearchText exactly
// (ocr + name + caption + transcript + tag labels + extra terms, lowercased).
// A single-word query matches this via `.includes`, so every facet word present
// here is findable.
export function assembleSearchText(fields: {
  ocr: string;
  name: string;
  caption: string;
  transcript: string;
  tagLabels: string[];
  extraTerms: string;
}): string {
  const labels = fields.tagLabels.map((l) => ' ' + l).join('');
  const labelContext = classificationContextTerms({ tags: fields.tagLabels });
  return (
    fields.ocr +
    ' ' +
    fields.name +
    ' ' +
    fields.caption +
    ' ' +
    fields.transcript +
    labels +
    ' ' +
    fields.extraTerms +
    ' ' +
    labelContext
  ).toLowerCase();
}
