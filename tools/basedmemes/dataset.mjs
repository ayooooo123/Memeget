// Loader for the LOCAL basedmemes.lol + Know Your Meme archive (dev/CI only —
// never bundled, never run on-device). The archive lives OUTSIDE the repo (it's
// large and machine-specific), so callers pass a `--data-dir`; nothing here is
// hard-coded to one checkout beyond the convenience default below.
//
// Two files make up the archive:
//   • dataset.jsonl        — one JSON record per line, {image, prefix, suffix}
//                            where `suffix` is a comma-separated tag list and
//                            `image` is the bare image filename.
//   • meme_dataset_kym.json — a JSON array of {image, tags, file} entries; `tags`
//                            is richer KYM per-meme vocabulary (occasionally an
//                            OBJECT rather than a string), and `file` is
//                            "images/<filename>".
//
// We treat EACH MEME IMAGE as one "page" and its tag list as that page's terms,
// merging the two sources by image filename (KYM keyed on the basename of
// `file`) and unioning tags per image. Feeding those pages to `aggregatePages`
// then counts DISTINCT IMAGES per tag — the frequency×distinctness signal the
// baseline ranker wants, and the meaning `buildBaseline`'s count>=2 floor
// assumes ("seen on >=2 memes").

import { readFileSync } from 'node:fs';
import { basename, join } from 'node:path';
import { jsonTerm } from '../memedepot/harvest.mjs';

// Convenience default so `npm run mine:basedmemes` works with no args on the
// machine that holds the archive. Override with --data-dir anywhere else.
export const DEFAULT_DATA_DIR = '/Users/jd/projects/basedmemes_archive/www.basedmemes.lol';

// Read a file, treating "not found" as "no data" (return null) so a partial
// archive still yields what exists. Any other error is real and rethrown.
function readIfExists(path) {
  try {
    return readFileSync(path, 'utf8');
  } catch (err) {
    if (err && err.code === 'ENOENT') return null;
    throw err;
  }
}

// Merge both archive files into per-image tag sets, keyed by image filename, and
// return the non-empty tag arrays (the "pages"). Robust to either file being
// absent or to individual malformed lines/entries — those are skipped, not fatal.
export function loadDataset(dataDir = DEFAULT_DATA_DIR) {
  const byImage = new Map(); // filename -> Set<raw tag string>

  const addTags = (key, tags) => {
    if (!key) return; // no filename to merge on → can't be a page
    let set = byImage.get(key);
    if (!set) {
      set = new Set();
      byImage.set(key, set);
    }
    for (const raw of tags) {
      if (typeof raw !== 'string') continue;
      const t = raw.trim();
      if (t) set.add(t); // union across sources; exact dupes collapse
    }
  };

  // dataset.jsonl — one record per line; `image` is already a bare filename.
  const jsonlText = readIfExists(join(dataDir, 'dataset.jsonl'));
  if (jsonlText) {
    for (const line of jsonlText.split('\n')) {
      const trimmed = line.trim();
      if (!trimmed) continue;
      let rec;
      try {
        rec = JSON.parse(trimmed);
      } catch {
        continue; // skip a corrupt line rather than abort the whole load
      }
      const image = typeof rec?.image === 'string' ? rec.image.trim() : '';
      const suffix = typeof rec?.suffix === 'string' ? rec.suffix : '';
      const tags = suffix.split(',').map((s) => s.trim());
      addTags(image, tags);
    }
  }

  // meme_dataset_kym.json — array; key on basename(file), tags coerced via
  // jsonTerm (some are {name|title|...} objects, not bare strings).
  const kymText = readIfExists(join(dataDir, 'meme_dataset_kym.json'));
  if (kymText) {
    let arr = null;
    try {
      arr = JSON.parse(kymText);
    } catch {
      arr = null; // unreadable KYM file → just contribute nothing
    }
    if (Array.isArray(arr)) {
      for (const entry of arr) {
        const fileField = typeof entry?.file === 'string' ? entry.file : '';
        const imageField = typeof entry?.image === 'string' ? entry.image : '';
        // Prefer basename(file); fall back to basename(image URL) so a
        // file-less entry still merges rather than being dropped.
        const key = fileField ? basename(fileField) : imageField ? basename(imageField) : '';
        const rawTags = Array.isArray(entry?.tags) ? entry.tags : [];
        const tags = rawTags.map((t) => jsonTerm(t)).filter(Boolean);
        addTags(key, tags);
      }
    }
  }

  const pages = [];
  for (const set of byImage.values()) {
    if (set.size === 0) continue; // skip images with no usable tags
    pages.push([...set]);
  }
  return pages;
}
