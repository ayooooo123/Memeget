import {
  buildExpandedLexicalQuery,
  ftsMatchQuery,
  fuseDenseAndLexicalRanks,
  reciprocalRankFusion,
  rankSemanticLabels,
  searchScopeEntries,
  searchLabelPrompt,
  tagTermScore,
  lexicalRankQuery,
  searchTermsForText,
  type LabelExpansionCandidate,
} from './searchExpansion';

const v = (...xs: number[]) => Float32Array.from(xs);

const candidates: LabelExpansionCandidate[] = [
  {
    label: 'Angry',
    terms: ['angry', 'rage', 'mad'],
    vec: v(1, 0),
    source: 'curated',
  },
  {
    label: 'Crying / Sad',
    terms: ['crying', 'sad', 'sobbing'],
    vec: v(0, 1),
    source: 'library',
  },
  {
    label: 'Office Reaction',
    terms: ['office reaction', 'work', 'meeting'],
    vec: v(0.5, 0.5),
    source: 'taught',
  },
];

describe('rankSemanticLabels', () => {
  it('uses the query vector to select synonym terms from nearby labels', () => {
    const hits = rankSemanticLabels(v(0.96, 0.04), candidates, { minScore: 0.7, limit: 2 });

    expect(hits.map((h) => h.label)).toEqual(['Angry']);
    expect(hits[0].terms).toEqual(['angry', 'rage', 'mad']);
  });

  it('returns no semantic expansion when the dense query vector is unavailable', () => {
    expect(rankSemanticLabels(null, candidates)).toEqual([]);
  });
});

describe('buildExpandedLexicalQuery', () => {
  it('keeps exact terms separate from semantic expansion terms', () => {
    const hits = rankSemanticLabels(v(1, 0), candidates, { minScore: 0.7 });
    const query = buildExpandedLexicalQuery(['mad'], hits);

    expect(query.exactTerms).toEqual(['mad']);
    expect(query.expandedTerms).toEqual(['angry', 'rage']);
  });

  it('keeps semantic expansions out of lexical rank fusion when exact terms exist', () => {
    expect(
      lexicalRankQuery({ exactTerms: ['mad'], expandedTerms: ['angry', 'rage'] })
    ).toEqual({ exactTerms: ['mad'] });
    expect(lexicalRankQuery({ exactTerms: [], expandedTerms: ['angry'] })).toEqual({
      exactTerms: [],
      expandedTerms: ['angry'],
    });
  });
});

describe('ftsMatchQuery', () => {
  it('quotes exact and expanded terms for a safe OR-style FTS5 query', () => {
    expect(
      ftsMatchQuery({ exactTerms: ['mad'], expandedTerms: ['angry', 'office reaction'] })
    ).toBe('"mad" OR "angry" OR "office reaction"');
  });
});

describe('searchTermsForText', () => {
  it('drops short terms for dense search but can keep them for lexical fallback', () => {
    expect(searchTermsForText('me vs angry')).toEqual(['angry']);
    expect(searchTermsForText('me vs angry', true)).toEqual(['me', 'vs', 'angry']);
  });
});

describe('searchScopeEntries', () => {
  it('relaxes a media filter for non-empty queries when that filter has nothing to rank', () => {
    const entries = [
      { id: 1, kind: 'image' },
      { id: 2, kind: 'image' },
    ];

    expect(searchScopeEntries(entries, 'video', 'angry reaction').map((e) => e.id)).toEqual([1, 2]);
    expect(searchScopeEntries(entries, 'video', '').map((e) => e.id)).toEqual([]);
  });
});

describe('tagTermScore', () => {
  it('gives direct tag-label matches a strong query-independent boost', () => {
    expect(tagTermScore(['wojak', 'crying wojak'], { exactTerms: ['crying', 'wojak'] })).toBeGreaterThan(
      tagTermScore(['nazi', 'goku'], { exactTerms: ['crying', 'wojak'] })
    );
  });
});

describe('reciprocalRankFusion', () => {
  it('promotes documents that appear near the top of both dense and lexical lists', () => {
    const fused = reciprocalRankFusion([
      { ids: [1, 2, 3], weight: 1 },
      { ids: [3, 2, 4], weight: 2 },
    ]);

    expect(fused[0].id).toBe(3);
    expect(fused.map((r) => r.id)).toEqual([3, 2, 4, 1]);
  });

  it('preserves the full dense tail when lexical search only matches a subset', () => {
    const fused = fuseDenseAndLexicalRanks(
      [
        { id: 1, score: 0.9, modifiedAt: 100 },
        { id: 2, score: 0.8, modifiedAt: 90 },
        { id: 3, score: 0.7, modifiedAt: 80 },
        { id: 4, score: 0.6, modifiedAt: 70 },
      ],
      [3]
    );

    expect(fused.map((r) => r.id)).toEqual([3, 1, 2, 4]);
  });
});

describe('searchLabelPrompt', () => {
  it('embeds library-only labels as meme-search concepts', () => {
    expect(searchLabelPrompt('Office Reaction')).toBe('a meme about Office Reaction');
  });
});
