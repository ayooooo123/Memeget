// End-to-end verification of the tag→searchable-text path — the pipeline the
// aspect-search eval proved single-word search depends on. It stitches the REAL
// shipped functions: a facet-rich VLM reply → parseVision → the extra-terms /
// searchText assembly used by indexer.ts + db.ts. If the model emits a facet
// word, this proves that word ends up findable. (What only a device can confirm
// — that Gemma actually emits these words — is a separate on-device check.)

import { parseVision, formatGrounding, userTurn, type GroundingLabel } from './visionCore';
import { memeExtraTerms, captionSearchText, assembleSearchText } from './searchText';

// A representative reply in the exact shape the enriched USER_PROMPT requests,
// covering every facet the taxonomy added.
const REPLY = [
  'CAPTION: a cartoon dog sips coffee in a burning room, calmly insisting everything is fine',
  'TEXT: this is fine',
  'SUBJECTS: dog, fire, coffee',
  'TAGS: this is fine, dog, sipping coffee, forced calm, denial, fire, kitchen, dark humor, when everything is falling apart',
].join('\n');

// Reproduce indexer.ts's assembly for a described meme: parseVision → tags →
// extraTerms → the stored searchText (via db.ts's rowSearchText path).
function searchTextFor(reply: string, ocr = '', name = '', curated = ''): string {
  const res = parseVision(reply);
  const tagList = res.tags.map((label) => ({ label }));
  const extraTerms = memeExtraTerms(curated, res);
  return assembleSearchText({
    ocr,
    name,
    caption: res.caption,
    transcript: '',
    tagLabels: tagList.map((t) => t.label),
    extraTerms,
  });
}

describe('a facet-rich VLM reply becomes searchable by every facet', () => {
  const hay = searchTextFor(REPLY, 'this is fine', 'This Is Fine', 'this is fine dog fire');

  // One representative single-word/phrase query per facet the taxonomy added.
  const facetQueries: [string, string][] = [
    ['format', 'this is fine'],
    ['character', 'dog'],
    ['action', 'sipping'],
    ['emotion', 'forced calm'],
    ['tone', 'dark humor'],
    ['object', 'fire'],
    ['setting', 'kitchen'],
    ['situation', 'when everything is falling apart'],
  ];

  it.each(facetQueries)('a %s query ("%s") hits the searchable text', (_facet, query) => {
    expect(hay.includes(query.toLowerCase())).toBe(true);
  });

  it('is lowercased, so a lowercased query matches regardless of model casing', () => {
    expect(searchTextFor('TAGS: SMUG, Pointing').includes('smug')).toBe(true);
    expect(searchTextFor('TAGS: SMUG, Pointing').includes('pointing')).toBe(true);
  });

  it('drops a reply that echoed a field hint instead of filling it (no junk indexed)', () => {
    const junk = searchTextFor('TAGS: 4-8 comma-separated lowercase keywords');
    expect(junk.includes('comma-separated')).toBe(false);
  });
});

describe('the model is actually asked for facets, grounded by the CLIP guess', () => {
  it('injects the CLIP per-facet guess into the user turn', () => {
    const clipGuess: GroundingLabel[] = [
      { label: 'This Is Fine', category: 'format' },
      { label: 'Fire / Burning', category: 'object' },
      { label: 'False Confidence', category: 'situation' },
    ];
    const turn = userTurn('this is fine', formatGrounding(clipGuess));
    // The prompt requests the new facets…
    expect(turn).toMatch(/how a person would search/i);
    expect(turn).toMatch(/real-life situation/);
    expect(turn).toMatch(/be quiet/); // the gesture-meaning teaching example
    // …and hands the model CLIP's facet guess to confirm/expand.
    expect(turn).toContain('format: This Is Fine');
    expect(turn).toContain('situation: False Confidence');
  });
});

describe('captionSearchText (the caption-vector text) carries the tags too', () => {
  it('includes caption + tag labels + extra terms', () => {
    const res = parseVision(REPLY);
    const text = captionSearchText(res.caption, res.tags.map((label) => ({ label })), 'extra term');
    expect(text).toContain('burning room');
    expect(text).toContain('denial');
    expect(text).toContain('extra term');
  });
});
