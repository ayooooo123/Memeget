// Tests for the VLM prompt core: retrieval-augmented grounding (feeding CLIP's
// format/character guesses into the caption prompt) and the user-turn assembly.

import { formatGrounding, userTurn, parseVision, USER_PROMPT, type GroundingLabel } from './visionCore';

describe('formatGrounding', () => {
  it('returns empty when there are no labels', () => {
    expect(formatGrounding([])).toBe('');
  });

  it('surfaces format/character/person before other facets', () => {
    const labels: GroundingLabel[] = [
      { label: 'happy', category: 'emotion' },
      { label: 'crypto', category: 'topic' },
      { label: 'distracted boyfriend', category: 'format' },
      { label: 'milady', category: 'character' },
    ];
    const g = formatGrounding(labels);
    // format + character lead; emotion/topic come after and get truncated last.
    expect(g.indexOf('distracted boyfriend')).toBeGreaterThan(-1);
    expect(g.indexOf('milady')).toBeGreaterThan(-1);
    expect(g.indexOf('distracted boyfriend')).toBeLessThan(g.indexOf('happy'));
    expect(g.indexOf('milady')).toBeLessThan(g.indexOf('crypto'));
  });

  it('caps the label list at four and dedupes case-insensitively', () => {
    const labels: GroundingLabel[] = [
      { label: 'Wojak', category: 'character' },
      { label: 'wojak', category: 'character' }, // dup
      { label: 'gigachad', category: 'character' },
      { label: 'pepe', category: 'character' },
      { label: 'doomer', category: 'character' },
      { label: 'coomer', category: 'character' }, // 5th distinct → dropped
    ];
    const g = formatGrounding(labels);
    expect(g).toContain('Wojak');
    expect(g).not.toContain('coomer');
    // "wojak" appears once (the original casing), not twice.
    expect(g.toLowerCase().match(/wojak/g)?.length).toBe(1);
  });

  it('includes related themes and carries the strict "only if it matches" caveat', () => {
    const g = formatGrounding([{ label: 'this is fine', category: 'format' }], ['denial', 'chaos', 'denial']);
    expect(g).toContain('related: denial, chaos'); // deduped
    expect(g.toLowerCase()).toContain('only if they match');
  });
});

describe('userTurn', () => {
  it('is just the base prompt with no hints', () => {
    expect(userTurn()).toBe(USER_PROMPT);
  });

  it('appends the OCR hint and the grounding when both are present', () => {
    const g = formatGrounding([{ label: 'drake', category: 'format' }]);
    const turn = userTurn('some ocr text', g);
    expect(turn.startsWith(USER_PROMPT)).toBe(true);
    expect(turn).toContain('some ocr text');
    expect(turn).toContain('drake');
  });

  it('ignores an empty grounding string', () => {
    expect(userTurn(undefined, '')).toBe(USER_PROMPT);
    expect(userTurn(undefined, '   ')).toBe(USER_PROMPT);
  });
});

describe('parseVision still parses the enriched prompt example', () => {
  it('reads the four labeled lines into a clean result', () => {
    const reply = [
      'CAPTION: a dog sips coffee in a burning room, calmly pretending everything is ok',
      'TEXT: this is fine',
      'SUBJECTS: dog, fire',
      'TAGS: this is fine, denial, forced calm, when everything is falling apart',
    ].join('\n');
    const r = parseVision(reply);
    expect(r.caption).toContain('burning room');
    expect(r.text).toBe('this is fine');
    expect(r.subjects).toEqual(['dog', 'fire']);
    expect(r.tags).toContain('when everything is falling apart');
  });
});
