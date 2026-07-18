// Runs the eval harness against a REAL golden set when one is present at
// tools/eval/golden.json (produced by tools/eval/build_golden.py in CI/Colab).
// `npm run eval` picks this up and prints Recall@k / MRR. When there's no golden
// set yet, it no-ops with a hint — so the eval command works either way.

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  evaluateRetrieval,
  formatMetrics,
  evaluateTagging,
  formatTagging,
  evaluateAspectSearch,
  formatAspect,
  type GoldenSet,
} from './evalCore';

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
    console.log(`\n--- retrieval (real golden set, ${golden.memes.length} memes) ---\n${formatMetrics(metrics)}\n`);
    expect(metrics.n).toBeGreaterThan(0);

    // Tagging: given a meme image, does zero-shot classification pick its right
    // format? This is the metric that responds to label/prompt quality — the
    // north star (every aspect of a meme searchable) lives here, not in retrieval.
    const tagging = evaluateTagging(golden);
    console.log(`\n--- tagging / zero-shot format (${tagging.labels} labels) ---\n${formatTagging(tagging)}\n`);
    expect(tagging.n).toBeGreaterThan(0);

    // Aspect search: single-word queries ("smug", "pointing") over the lexical
    // tag channel — how the app is really searched. Needs a golden set with
    // per-meme tags (aspects[]); older sets omit it, so this just reports 0.
    const aspects = golden.aspects ?? [];
    if (aspects.length) {
      const aspect = evaluateAspectSearch(golden);
      console.log(`\n--- aspect search (single-word, ${aspect.n} queries) ---\n${formatAspect(aspect)}\n`);
      expect(aspect.n).toBeGreaterThan(0);
    } else {
      console.log(
        '\n[eval] this golden set has no aspects[] (per-meme tags) yet — re-run the ' +
          '"Build eval golden set" workflow to add single-word aspect-search scoring.\n'
      );
    }
  });
});
