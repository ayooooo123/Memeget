// Runs the eval harness against a REAL golden set when one is present at
// tools/eval/golden.json (produced by tools/eval/build_golden.py in CI/Colab).
// `npm run eval` picks this up and prints Recall@k / MRR. When there's no golden
// set yet, it no-ops with a hint — so the eval command works either way.

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { evaluateRetrieval, formatMetrics, type GoldenSet } from './evalCore';

const GOLDEN_PATH = join(process.cwd(), 'tools/eval/golden.json');

describe('eval harness — real golden set', () => {
  it('scores tools/eval/golden.json if present', () => {
    if (!existsSync(GOLDEN_PATH)) {
      console.log(
        '\n[eval] no tools/eval/golden.json yet — run the "Build eval golden set" ' +
          'workflow (tools/eval/build_golden.py) to generate one.\n'
      );
      return;
    }
    const golden = JSON.parse(readFileSync(GOLDEN_PATH, 'utf8')) as GoldenSet;
    const metrics = evaluateRetrieval(golden);
    console.log(`\n--- eval harness (real golden set, ${golden.memes.length} memes) ---\n${formatMetrics(metrics)}\n`);
    expect(metrics.n).toBeGreaterThan(0);
  });
});
