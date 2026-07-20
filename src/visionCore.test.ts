// Tests for the VLM prompt core: retrieval-augmented grounding (feeding CLIP's
// format/character guesses into the caption prompt) and the user-turn assembly.

import {
  formatGrounding,
  userTurn,
  parseVision,
  runVision,
  USER_PROMPT,
  MAX_VLM_OUTPUT_TOKENS,
  type GroundingLabel,
} from './visionCore';

describe('formatGrounding', () => {
  it('returns empty when there are no labels', () => {
    expect(formatGrounding([])).toBe('');
  });

  it('groups every facet by name and keeps emotion/action (does not drop them)', () => {
    const labels: GroundingLabel[] = [
      { label: 'happy', category: 'emotion' },
      { label: 'crypto', category: 'topic' },
      { label: 'distracted boyfriend', category: 'format' },
      { label: 'milady', category: 'character' },
      { label: 'pointing', category: 'action' },
    ];
    const g = formatGrounding(labels);
    // Grouped, labeled by facet — and emotion/action survive rather than being
    // truncated in favor of format/character.
    expect(g).toContain('format: distracted boyfriend');
    expect(g).toContain('character: milady');
    expect(g).toContain('action: pointing');
    expect(g).toContain('emotion: happy');
    // Facet order: format leads, topic trails.
    expect(g.indexOf('format:')).toBeLessThan(g.indexOf('action:'));
    expect(g.indexOf('action:')).toBeLessThan(g.indexOf('topic:'));
  });

  it('caps at two labels per facet and dedupes case-insensitively', () => {
    const labels: GroundingLabel[] = [
      { label: 'Wojak', category: 'character' },
      { label: 'wojak', category: 'character' }, // dup
      { label: 'gigachad', category: 'character' },
      { label: 'pepe', category: 'character' }, // 3rd distinct in facet → dropped
    ];
    const g = formatGrounding(labels);
    expect(g).toContain('Wojak');
    expect(g).toContain('gigachad');
    expect(g).not.toContain('pepe'); // per-facet cap of 2
    expect(g.toLowerCase().match(/wojak/g)?.length).toBe(1); // deduped
  });

  it('includes related themes and carries the "only if it matches" caveat', () => {
    const g = formatGrounding([{ label: 'this is fine', category: 'format' }], ['denial', 'chaos', 'denial']);
    expect(g).toContain('related: denial, chaos'); // deduped
    expect(g.toLowerCase()).toContain('match what you actually see');
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

describe('runVision', () => {
  const reply = ['CAPTION: hi', 'TAGS: a, b, c'].join('\n');

  beforeEach(() => {
    jest.useFakeTimers();
    jest.spyOn(console, 'log').mockImplementation(() => {});
  });
  afterEach(() => {
    jest.useRealTimers();
    (console.log as jest.Mock).mockRestore();
  });

  it('parses the reply and never interrupts when output stays under the cap', async () => {
    let interrupted = false;
    const result = await runVision(
      {
        generate: async () => reply,
        interrupt: () => {
          interrupted = true;
        },
        getGeneratedTokenCount: () => 40,
        getPromptTokenCount: () => 200,
      },
      [],
      'test'
    );
    expect(interrupted).toBe(false);
    expect(result.caption).toBe('hi');
    expect(result.tags).toEqual(['a', 'b', 'c']);
  });

  it('interrupts generation once it blows past MAX_VLM_OUTPUT_TOKENS', async () => {
    let interrupted = false;
    let resolveGen: (s: string) => void = () => {};
    const p = runVision(
      {
        generate: () =>
          new Promise<string>((res) => {
            resolveGen = res;
          }),
        interrupt: () => {
          interrupted = true;
          resolveGen(reply); // a real run resolves generate() shortly after interrupt
        },
        getGeneratedTokenCount: () => MAX_VLM_OUTPUT_TOKENS + 1,
        getPromptTokenCount: () => 200,
      },
      [],
      'test'
    );
    await jest.advanceTimersByTimeAsync(300); // fire the 250ms watchdog → interrupt → resolve
    const result = await p;
    expect(interrupted).toBe(true);
    expect(result.caption).toBe('hi');
  });
});
