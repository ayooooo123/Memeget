// Mine a machine-generated breadth tier of zero-shot labels from the LOCAL
// basedmemes.lol + Know Your Meme archive (dev/CI only — never bundled, never
// run on-device). This is the second baseline tier alongside the memedepot
// harvest: memedepot is egress-blocked from the sandbox and only reachable from
// CI, whereas this archive is a large local corpus the harvester could never
// reach. Both tiers fold under the hand-authored curated core in
// src/baselineLabels.ts (curated wins, cross-tier de-dupe, capped).
//
// Pipeline (all the quality machinery is REUSED from the memedepot harvester —
// there is deliberately only one normalization/category/prompt convention):
//   loadDataset(dataDir)  → per-image tag arrays ("pages", one per meme image)
//   aggregatePages(pages) → { term: distinctImageCount } frequency map
//   buildBaseline(freq)   → ranked, singleton-floored, plural-deduped,
//                           Title-Cased, category-guessed baseline file shape
//
// Usage:
//   node tools/basedmemes/mine_labels.mjs [--data-dir <path>] [--out <path>] [--max <n>]
//
// The output (src/data/basedmemesBaseline.json) is a breadth tier; cap/max are
// tuned against the search-quality eval harness (see tools/eval/README.md).

import { writeFile } from 'node:fs/promises';
import { aggregatePages, buildBaseline } from '../memedepot/harvest.mjs';
import { loadDataset, DEFAULT_DATA_DIR } from './dataset.mjs';

const DEFAULT_OUT = new URL('../../src/data/basedmemesBaseline.json', import.meta.url).pathname;
const DEFAULT_MAX = 300;
const SOURCE = 'basedmemes.lol + knowyourmeme.com';

const USAGE = `Mine baseline labels from the local basedmemes.lol + KYM archive.

Usage:
  node tools/basedmemes/mine_labels.mjs [options]

Options:
  --data-dir <path>  archive directory (default: ${DEFAULT_DATA_DIR})
  --out <path>       output JSON (default: src/data/basedmemesBaseline.json)
  --max <n>          cap on labels written (default: ${DEFAULT_MAX})
  -h, --help         show this help`;

function parseArgs(argv) {
  const args = { dataDir: DEFAULT_DATA_DIR, out: DEFAULT_OUT, max: DEFAULT_MAX, help: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--data-dir') args.dataDir = argv[++i];
    else if (a === '--out') args.out = argv[++i];
    else if (a === '--max') args.max = Number(argv[++i]);
    else if (a === '-h' || a === '--help') args.help = true;
  }
  return args;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    console.log(USAGE);
    return;
  }
  if (!Number.isFinite(args.max) || args.max <= 0) {
    console.error(`Invalid --max: ${args.max}`);
    process.exitCode = 1;
    return;
  }

  const pages = loadDataset(args.dataDir);
  const freq = aggregatePages(pages);
  const uniqueTags = Object.keys(freq).length;
  const baseline = buildBaseline(freq, {
    max: args.max,
    source: SOURCE,
    generatedAt: new Date().toISOString(),
  });

  console.log(`data-dir   : ${args.dataDir}`);
  console.log(`images     : ${pages.length}`);
  console.log(`unique tags: ${uniqueTags}`);
  console.log(`labels     : ${baseline.labels.length}`);

  if (baseline.labels.length === 0) {
    console.error('No labels produced — refusing to overwrite', args.out);
    console.error('(Is --data-dir correct and does it contain dataset.jsonl / meme_dataset_kym.json?)');
    process.exitCode = 1;
    return;
  }

  const top = baseline.labels
    .slice(0, 12)
    .map((l, i) => `  ${String(i + 1).padStart(2)}. ${l.label} (${l.count}) [${l.category}]`)
    .join('\n');
  console.log(`top 12:\n${top}`);

  await writeFile(args.out, `${JSON.stringify(baseline, null, 2)}\n`, 'utf8');
  console.log(`wrote ${baseline.labels.length} labels -> ${args.out}`);
}

// Only run when invoked directly, so tests can import helpers without side effects.
if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((err) => {
    console.error(err);
    process.exitCode = 1;
  });
}

export { parseArgs };
