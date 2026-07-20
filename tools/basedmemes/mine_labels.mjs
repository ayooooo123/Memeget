// Mine a breadth tier of zero-shot meme labels from the LOCAL basedmemes.lol +
// Know Your Meme archive, writing `src/data/basedmemesBaseline.json`.
//
// This is the local-archive sibling of `tools/memedepot/harvest.mjs`. memedepot
// is egress-blocked from the dev sandbox, so that harvester only runs in CI; this
// one mines a big archive that already lives on disk. It reuses the harvester's
// pure machinery wholesale — normalization, per-page aggregation, ranking, plural
// collapse, category/prompt heuristics — so both tiers share one convention.
//
// MODEL: `loadDataset` returns one "page" per meme image (its de-duplicated tag
// list). `aggregatePages` counts DISTINCT IMAGES per tag; `buildBaseline`'s
// count>=2 floor then keeps only tags seen on >=2 memes, ranks by that count,
// collapses plural variants, Title-Cases, and caps. The result is a second
// machine-generated tier the app folds UNDER the curated core (see
// src/baselineLabels.ts) — bounded and meant to be tuned via the eval harness.

import { writeFile } from 'node:fs/promises';
import { aggregatePages, buildBaseline } from '../memedepot/harvest.mjs';
import { loadDataset, DEFAULT_DATA_DIR } from './dataset.mjs';

const DEFAULTS = {
  dataDir: DEFAULT_DATA_DIR,
  out: 'src/data/basedmemesBaseline.json',
  max: 300,
  source: 'basedmemes.lol + knowyourmeme.com',
};

function parseArgs(argv) {
  const opts = { ...DEFAULTS };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--data-dir') opts.dataDir = argv[++i];
    else if (arg === '--out') opts.out = argv[++i];
    else if (arg === '--max') opts.max = Number(argv[++i]);
    else if (arg === '--help' || arg === '-h') opts.help = true;
    else throw new Error(`unknown argument: ${arg}`);
  }
  if (!Number.isFinite(opts.max) || opts.max <= 0) throw new Error(`--max must be a positive number`);
  return opts;
}

const HELP = `Usage: node tools/basedmemes/mine_labels.mjs [options]

Mines a capped breadth tier of meme labels from the local basedmemes.lol +
Know Your Meme archive into a baseline JSON file.

Options:
  --data-dir <path>  Archive directory (default: ${DEFAULTS.dataDir})
  --out <path>       Output JSON file (default: ${DEFAULTS.out})
  --max <n>          Max labels to keep (default: ${DEFAULTS.max})
  -h, --help         Show this help
`;

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) {
    process.stdout.write(HELP);
    return;
  }

  const pages = await loadDataset(opts.dataDir);
  const freq = aggregatePages(pages);
  const uniqueTags = Object.keys(freq).length;
  const baseline = buildBaseline(freq, {
    max: opts.max,
    source: opts.source,
    generatedAt: new Date().toISOString(),
  });

  // Never overwrite a good file with an empty one: an empty result means the
  // archive wasn't found or was unreadable, not that the vocabulary is empty.
  if (baseline.labels.length === 0) {
    console.error(
      `No labels mined from ${opts.dataDir} (found ${pages.length} images, ${uniqueTags} unique tags). ` +
        `Refusing to overwrite ${opts.out}.`
    );
    process.exitCode = 1;
    return;
  }

  await writeFile(opts.out, JSON.stringify(baseline, null, 2) + '\n');

  console.log(`Mined ${opts.source}`);
  console.log(`  images:      ${pages.length}`);
  console.log(`  unique tags: ${uniqueTags}`);
  console.log(`  labels:      ${baseline.labels.length} (cap ${opts.max})`);
  console.log(`  wrote:       ${opts.out}`);
  console.log(`  top 12:`);
  for (const l of baseline.labels.slice(0, 12)) {
    console.log(`    ${String(l.count).padStart(6)}  ${l.label}  [${l.category}]`);
  }
}

// Only run when executed directly, so tests can import loadDataset/parseArgs.
if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((err) => {
    console.error(err?.stack || String(err));
    process.exitCode = 1;
  });
}

export { parseArgs, main, DEFAULTS };
