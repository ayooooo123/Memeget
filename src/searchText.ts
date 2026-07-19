// Pure assembly of a meme's searchable text — the lexical haystack single-word
// queries hit via `.includes`. Factored out of indexer.ts (which pulls in native
// modules) and db.ts so this logic is unit-testable on its own: it's the exact
// path a VLM's facet tags travel to become findable, and the aspect-search eval
// showed that path is what single-word search depends on.

// The VLM's open-vocabulary output (verbatim TEXT, SUBJECTS, TAGS) plus the
// curated/association terms, lowercased into one extra-terms blob. This is where
// the facet words (action/emotion/situation/object/…) the model emits enter the
// index.
export function memeExtraTerms(
  curatedTerms: string,
  res: { text: string; subjects: string[]; tags: string[] }
): string {
  const extra = [res.text, res.subjects.join(' '), res.tags.join(' ')].join(' ').toLowerCase();
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
    fields.extraTerms
  ).toLowerCase();
}
