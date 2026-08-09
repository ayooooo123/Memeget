// End-to-end verification of the tag→searchable-text path — the pipeline the
// aspect-search eval proved single-word search depends on. It stitches the REAL
// shipped functions: a facet-rich VLM reply → parseVision → the extra-terms /
// searchText assembly used by indexer.ts + db.ts. If the model emits a facet
// word, this proves that word ends up findable. (What only a device can confirm
// — that Gemma actually emits these words — is a separate on-device check.)

import { parseVision, formatGrounding, userTurn, type GroundingLabel } from './visionCore';
import {
  assembleSearchText,
  captionSearchText,
  classificationContextTerms,
  containsPhrase,
  memeExtraTerms,
  phraseKey,
  phraseTokenCount,
} from './searchText';

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

  it('adds bounded context phrases from the whole classification without creating tags', () => {
    const extra = memeExtraTerms('', {
      caption: 'a furious worker reacts in a meeting',
      text: '',
      subjects: ['office worker'],
      tags: ['angry reaction', 'work meeting', 'frustration'],
    });

    expect(extra).toContain('angry reaction office worker');
    expect(extra).toContain('angry reaction work meeting');
    expect(extra).toContain('furious worker');
    expect(classificationContextTerms({ tags: ['Angry Reaction', 'Office / Work'] })).toContain(
      'angry reaction office work'
    );
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

describe('already-indexed rows gain context without a DB backfill', () => {
  it('adds classification context at search assembly time', () => {
    const text = assembleSearchText({
      ocr: '',
      name: '',
      caption: '',
      transcript: '',
      tagLabels: ['Angry Reaction', 'Office / Work'],
      extraTerms: '',
    });

    expect(text).toContain('angry reaction office work');
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

describe('phrase matching (find the clip that SAYS the query)', () => {
  // The exact on-device failure: a video transcribed as "I'm so old..." was
  // unfindable by typing "im so old" — the term path drops "im"/"so" and the
  // apostrophe blocked a literal compare.
  const haystack = assembleSearchText({
    ocr: 'me alone in my room once it turns 8pm',
    name: 'tweet_2078262789844668506.mp4',
    caption: '',
    transcript: "I'm so old. I'm just getting older all the time. And then I'm gonna die.",
    tagLabels: ['surprise'],
    extraTerms: '',
  });

  it('normalizes apostrophes and case into a phrase key', () => {
    expect(phraseKey("I'm so OLD.")).toBe('im so old');
    expect(phraseKey('  multiple   spaces\t& punctuation!! ')).toBe('multiple spaces punctuation');
  });

  it('counts phrase tokens, with empty string as zero', () => {
    expect(phraseTokenCount(phraseKey('im so old'))).toBe(3);
    expect(phraseTokenCount(phraseKey('  '))).toBe(0);
  });

  it('matches the spoken phrase despite apostrophes and dropped short words', () => {
    expect(containsPhrase(phraseKey(haystack), phraseKey('im so old'))).toBe(true);
    expect(containsPhrase(phraseKey(haystack), phraseKey('gonna die'))).toBe(true);
  });

  it('respects whole-token boundaries (no substring hits)', () => {
    expect(containsPhrase(phraseKey('a golden retriever'), phraseKey('old'))).toBe(false);
    expect(containsPhrase(phraseKey('so young at heart'), phraseKey('so old'))).toBe(false);
  });

  it('does not match a phrase whose words are present but not contiguous', () => {
    // "so ... old" appears, but not as the run "so old".
    expect(containsPhrase(phraseKey('so tired and very old'), phraseKey('so old'))).toBe(false);
  });
});
