// Tests for the tag-agreement eval, plus the runner: `npm run agreement` scores
// tools/eval/collection-manifest.json (from Settings → Export collection zip)
// when present — grading the model's descriptions against every tag the user
// personally applied or taught.

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { scoreAgreement, formatAgreement, type AgreementMeme } from './tagAgreement';

describe('scoreAgreement', () => {
  it('counts agreement when the model description surfaces the user label', () => {
    const memes: AgreementMeme[] = [
      {
        id: '1',
        caption: 'pepe the frog smiles smugly',
        tags: [
          { label: 'Pepe', source: 'exemplar' }, // user truth
          { label: 'smug', source: 'vision' },
        ],
      },
    ];
    const s = scoreAgreement(memes);
    expect(s.assertions).toBe(1);
    expect(s.agreementRate).toBeCloseTo(1, 6); // "pepe" appears in the caption
  });

  it('flags a miss when the model saw something else entirely', () => {
    const memes: AgreementMeme[] = [
      {
        id: '2',
        caption: 'a green cartoon character looks at the camera',
        tags: [
          { label: 'Milady', source: 'manual' }, // user truth the model missed
          { label: 'cartoon', source: 'vision' },
        ],
      },
    ];
    const s = scoreAgreement(memes);
    expect(s.agreementRate).toBe(0);
    expect(s.labels[0]).toMatchObject({ label: 'milady', asserted: 1, agreed: 0, missedIds: ['2'] });
  });

  it('grades against model output only — user/CLIP/OCR tags are not the haystack', () => {
    const memes: AgreementMeme[] = [
      {
        id: '3',
        caption: '',
        tags: [
          { label: 'Wojak', source: 'manual' },
          { label: 'wojak', source: 'prompt' }, // CLIP guess must NOT count as the model describing it
          { label: 'wojak crying', source: 'ocr' },
        ],
      },
    ];
    // No vision tags and no caption → the model never described it: skipped, not failed.
    const s = scoreAgreement(memes);
    expect(s.undescribed).toBe(1);
    expect(s.assertions).toBe(0);
  });

  it('ignores memes with no user-truth labels and is 0-safe when empty', () => {
    const s = scoreAgreement([
      { id: '4', caption: 'anything', tags: [{ label: 'cat', source: 'vision' }] },
    ]);
    expect(s.memesWithTruth).toBe(0);
    expect(s.agreementRate).toBe(0);
  });
});

describe('agreement runner (npm run agreement)', () => {
  it('scores a real collection manifest when present', () => {
    const path = join(process.cwd(), 'tools/eval/collection-manifest.json');
    if (!existsSync(path)) {
      console.log(
        '\n[agreement] no tools/eval/collection-manifest.json — export a collection ' +
          'zip from the app and drop its manifest.json there to grade the model ' +
          'against your own tags/teachings.\n'
      );
      return;
    }
    const manifest = JSON.parse(readFileSync(path, 'utf8')) as { memes: AgreementMeme[] };
    const s = scoreAgreement(manifest.memes);
    console.log(
      `\n--- tag agreement (model vs your ${s.assertions} assertions) ---\n${formatAgreement(s)}\n`
    );
    expect(manifest.memes.length).toBeGreaterThan(0);
  });
});
