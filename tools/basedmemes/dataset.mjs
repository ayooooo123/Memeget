// Local dataset loader for the basedmemes.lol + Know Your Meme archive.
//
// The app's memedepot harvester can't reach memedepot from the dev sandbox
// (egress-blocked), so this mines a LOCAL archive instead: a caption/tag dump of
// basedmemes.lol plus a KYM scrape. Two files, keyed by image:
//
//   • dataset.jsonl        — one JSON object per line:
//       { image: "<filename>", prefix: "...", suffix: "tag1, tag2, ..." }
//     `suffix` is a comma-separated tag list. ~11k distinct images.
//   • meme_dataset_kym.json — an array of:
//       { image: "<url>", tags: ["...", ...], file: "images/<filename>" }
//     `tags` are usually strings but occasionally objects, so they're coerced
//     with the harvester's `jsonTerm`. Keyed on basename(file).
//
// The two sources overlap (dataset.jsonl re-lists many KYM items under their
// descriptive filenames), so we MERGE BY IMAGE FILENAME and union the tag sets:
// each image ends up as ONE "page" whose terms are its de-duplicated tags. That
// mirrors the harvester's per-page model — `aggregatePages` then counts DISTINCT
// IMAGES per tag, and `buildBaseline`'s count>=2 floor means "appears on >=2
// memes", which is exactly the breadth signal we want.

import { readFile } from 'node:fs/promises';
import { basename } from 'node:path';
import { jsonTerm } from '../memedepot/harvest.mjs';

const DEFAULT_DATA_DIR = '/Users/jd/projects/basedmemes_archive/www.basedmemes.lol';

// Read a file's text, returning null (not throwing) when it's absent — the
// loader stays useful if only one of the two source files is present.
async function readIfExists(path) {
  try {
    return await readFile(path, 'utf8');
  } catch (err) {
    if (err && err.code === 'ENOENT') return null;
    throw err;
  }
}

// Add a raw tag to an image's tag set, keyed case-insensitively so "Wojak" and
// "wojak" collapse to one entry per image (union/dedupe). Empties are ignored.
function addTag(map, image, raw) {
  const term = typeof raw === 'string' ? raw.trim() : jsonTerm(raw).trim();
  if (!term) return;
  let bucket = map.get(image);
  if (!bucket) {
    bucket = new Map(); // lowercased key -> first-seen display form
    map.set(image, bucket);
  }
  const key = term.toLowerCase();
  if (!bucket.has(key)) bucket.set(key, term);
}

// Load the archive into per-image "pages": an array of tag arrays, one array per
// distinct image, tags de-duplicated within the image. Missing files are skipped.
export async function loadDataset(dataDir = DEFAULT_DATA_DIR) {
  const byImage = new Map(); // image filename -> Map<lowerKey, displayTerm>

  const jsonlText = await readIfExists(`${dataDir}/dataset.jsonl`);
  if (jsonlText) {
    for (const line of jsonlText.split('\n')) {
      const trimmed = line.trim();
      if (!trimmed) continue;
      let row;
      try {
        row = JSON.parse(trimmed);
      } catch {
        continue; // tolerate a malformed line rather than abort the whole load
      }
      const image = typeof row?.image === 'string' ? row.image.trim() : '';
      if (!image) continue;
      const suffix = typeof row?.suffix === 'string' ? row.suffix : '';
      for (const part of suffix.split(',')) addTag(byImage, image, part);
    }
  }

  const kymText = await readIfExists(`${dataDir}/meme_dataset_kym.json`);
  if (kymText) {
    let entries;
    try {
      entries = JSON.parse(kymText);
    } catch {
      entries = null;
    }
    if (Array.isArray(entries)) {
      for (const entry of entries) {
        const file = typeof entry?.file === 'string' ? entry.file : '';
        const image = basename(file).trim();
        if (!image) continue;
        const tags = Array.isArray(entry?.tags) ? entry.tags : [];
        for (const tag of tags) addTag(byImage, image, tag);
      }
    }
  }

  return [...byImage.values()].map((bucket) => [...bucket.values()]);
}

export { DEFAULT_DATA_DIR };
