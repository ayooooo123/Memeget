import { boundedEditDistance, SearchVocab, typoBudget } from './fuzzySearch';

describe('typoBudget', () => {
  it('tolerates more typos as words get longer', () => {
    expect(typoBudget(2)).toBe(0);
    expect(typoBudget(3)).toBe(0);
    expect(typoBudget(4)).toBe(1);
    expect(typoBudget(6)).toBe(1);
    expect(typoBudget(7)).toBe(2);
    expect(typoBudget(12)).toBe(2);
  });
});

describe('boundedEditDistance', () => {
  it('returns the true distance within budget', () => {
    expect(boundedEditDistance('gigachad', 'gigachad', 2)).toBe(0);
    expect(boundedEditDistance('gigchad', 'gigachad', 2)).toBe(1); // one insertion
    expect(boundedEditDistance('kitten', 'sitting', 3)).toBe(3);
    expect(boundedEditDistance('', 'abc', 3)).toBe(3);
  });

  it('bails out to maxDist + 1 once the budget is provably blown', () => {
    // Length gap alone exceeds the budget.
    expect(boundedEditDistance('a', 'abcdef', 2)).toBe(3);
    // Distance is 3 but budget is 2, so it reports the sentinel, not 3.
    expect(boundedEditDistance('kitten', 'sitting', 2)).toBe(3);
  });
});

describe('SearchVocab.corrections', () => {
  const vocab = SearchVocab.build([
    'gigachad wojak pepe the frog',
    'gigachad distracted boyfriend',
    'wojak doomer',
    'wojak crying',
  ]);

  it('corrects a mistyped word to the nearest real vocab word', () => {
    expect(vocab.corrections('gigchad')).toContain('gigachad');
    expect(vocab.corrections('wojk')).toContain('wojak');
  });

  it('never corrects a word that already exists in the vocab', () => {
    expect(vocab.corrections('wojak')).toEqual([]);
    expect(vocab.corrections('pepe')).toEqual([]);
  });

  it('refuses to correct words too short to correct safely', () => {
    // 3 chars => budget 0 => no correction even though "the"/"frog" exist.
    expect(vocab.corrections('teh')).toEqual([]);
  });

  it('breaks distance ties toward the more frequent word', () => {
    // "wojak" appears 3x, "pepe"/"doomer" once. A query one edit from wojak
    // should prefer it.
    const hits = vocab.corrections('wojaz', 1);
    expect(hits[0]).toBe('wojak');
  });
});

describe('SearchVocab.prefixMatches', () => {
  const vocab = SearchVocab.build([
    'gigachad gigabyte gigantic',
    'gigachad gigachad',
    'wojak',
  ]);

  it('returns vocab words that start with the typed prefix, commonest first', () => {
    const hits = vocab.prefixMatches('giga');
    expect(hits[0]).toBe('gigachad'); // highest frequency completion
    expect(hits).toEqual(expect.arrayContaining(['gigabyte', 'gigantic']));
    expect(hits).not.toContain('wojak');
  });

  it('excludes an exact match and ignores 1-char prefixes', () => {
    expect(vocab.prefixMatches('gigachad')).not.toContain('gigachad');
    expect(vocab.prefixMatches('g')).toEqual([]);
  });
});

describe('SearchVocab.expand', () => {
  const vocab = SearchVocab.build([
    'gigachad wojak pepe',
    'gigachad gigabyte',
    'wojak wojak',
  ]);

  it('adds corrections for misspelled terms without echoing the input', () => {
    const out = vocab.expand(['gigchad']);
    expect(out).toContain('gigachad');
    expect(out).not.toContain('gigchad');
  });

  it('adds prefix completions only for the final token when mid-word', () => {
    const withPrefix = vocab.expand(['wojak', 'giga'], { lastIsPrefix: true });
    expect(withPrefix).toEqual(expect.arrayContaining(['gigachad', 'gigabyte']));

    const withoutPrefix = vocab.expand(['wojak', 'giga'], { lastIsPrefix: false });
    expect(withoutPrefix).not.toContain('gigachad');
  });

  it('never returns a term already present in the query', () => {
    const out = vocab.expand(['gigachad', 'giga'], { lastIsPrefix: true });
    expect(out).not.toContain('gigachad');
  });
});

// Growing an existing vocabulary is what keeps the search bar responsive while
// the VLM describes the library: one meme changes, and only that meme's words
// are learned. The contract is that the grown vocabulary behaves like one built
// from the same documents in one go — otherwise the cheap path quietly degrades
// typo tolerance.
describe('SearchVocab.addTexts', () => {
  const docs = ['gigachad wojak pepe the frog', 'gigachad distracted boyfriend', 'wojak doomer'];

  it('learns words from documents added after the build', () => {
    const vocab = SearchVocab.build(docs);
    expect(vocab.corrections('soyjk')).toEqual([]);

    vocab.addTexts(['soyjak pointing']);

    expect(vocab.has('soyjak')).toBe(true);
    expect(vocab.corrections('soyjk')).toContain('soyjak');
    expect(vocab.prefixMatches('soy')).toContain('soyjak');
  });

  it('answers the same as a full rebuild over the same documents', () => {
    const late = ['soyjak pointing', 'wojak crying'];
    const grown = SearchVocab.build(docs);
    grown.addTexts(late);
    const rebuilt = SearchVocab.build([...docs, ...late]);

    expect(grown.size).toBe(rebuilt.size);
    expect(grown.corrections('wojaz', 1)).toEqual(rebuilt.corrections('wojaz', 1));
    expect(grown.prefixMatches('woj')).toEqual(rebuilt.prefixMatches('woj'));
    expect(grown.expand(['gigchad'])).toEqual(rebuilt.expand(['gigchad']));
  });

  it('accumulates frequency for words it already knew, so ties still break by commonest', () => {
    // "pepe" starts rarer than "wojak"; three more sightings must flip the tie
    // exactly as a full rebuild would, which only works if the frequency of an
    // ALREADY-KNOWN word keeps counting.
    const vocab = SearchVocab.build(['wojak', 'wojak', 'pepe']);
    vocab.addTexts(['pepe pepe pepe']);

    expect(vocab.corrections('pepz', 1)).toEqual(['pepe']);
    expect(vocab.prefixMatches('p')).toEqual([]);
    expect(vocab.prefixMatches('pe')).toEqual(['pepe']);
  });

  it('is a no-op for documents with no new words', () => {
    const vocab = SearchVocab.build(docs);
    const before = vocab.size;
    vocab.addTexts(['wojak wojak', '']);
    expect(vocab.size).toBe(before);
  });
});

// The cold build is the one that froze the search bar (802ms on a 2272-meme
// library). Slicing it must not change the vocabulary it produces — a build that
// yields but drops words would quietly cost typo tolerance instead of time.
describe('SearchVocab.buildAsync', () => {
  const docs = [
    'gigachad wojak pepe the frog',
    'gigachad distracted boyfriend',
    'wojak doomer',
    'wojak crying',
    'soyjak pointing at the screen',
  ];

  it('produces exactly what the synchronous build produces', async () => {
    const sliced = await SearchVocab.buildAsync(docs, async () => {}, 2);
    const whole = SearchVocab.build(docs);

    expect(sliced.size).toBe(whole.size);
    expect(sliced.corrections('wojaz', 1)).toEqual(whole.corrections('wojaz', 1));
    expect(sliced.corrections('gigchad')).toEqual(whole.corrections('gigchad'));
    expect(sliced.prefixMatches('so')).toEqual(whole.prefixMatches('so'));
    expect(sliced.expand(['wojk', 'poi'], { lastIsPrefix: true })).toEqual(
      whole.expand(['wojk', 'poi'], { lastIsPrefix: true })
    );
  });

  it('hands the caller control between slices instead of running straight through', async () => {
    let yields = 0;
    await SearchVocab.buildAsync(
      docs,
      async () => {
        yields++;
      },
      2
    );
    // 5 documents at 2 per slice, then the term inserts: it must break up both
    // loops, not just tokenization.
    expect(yields).toBeGreaterThan(2);
  });

  it('never yields when everything fits in one slice', async () => {
    let yields = 0;
    const vocab = await SearchVocab.buildAsync(['wojak pepe'], async () => {
      yields++;
    });
    expect(yields).toBe(0);
    expect(vocab.has('wojak')).toBe(true);
  });
});
