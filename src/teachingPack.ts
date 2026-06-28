// A "teaching pack" is the shareable, on-disk form of taught knowledge: the set
// of exemplars (label + CLIP image embedding + associations) a meme archiver has
// built up by example. Exporting one writes a small JSON file; importing one
// folds those examples into the local DB so a novice instantly inherits an
// expert's meme knowledge without re-teaching anything.
//
// The vectors are CLIP image embeddings, so a pack is only meaningful to a
// library built with the same image encoder — hence the model/dim stamp, which
// import checks before trusting the contents.
import type { Exemplar } from './types';

export const PACK_FORMAT = 'memeget-teaching-pack';
export const PACK_VERSION = 1;
// Matches the CLIP image encoder in embeddings.tsx. Bump if that model changes
// in a way that moves the vector space, so stale packs are rejected on import.
export const PACK_MODEL = 'clip-vit-base-patch32';
export const PACK_DIM = 512;

export interface PackExemplar {
  label: string;
  category: string;
  vector: number[];
  associations: string[];
  positive: boolean;
}

export interface TeachingPack {
  format: typeof PACK_FORMAT;
  version: number;
  model: string;
  dim: number;
  createdAt: number;
  count: number;
  exemplars: PackExemplar[];
}

// Build a pack from the library's exemplars. `sourceUri` is deliberately dropped
// — it's a device-local content:// URI that means nothing on another phone and
// would leak the author's folder layout.
export function buildPack(exemplars: Exemplar[], createdAt: number): TeachingPack {
  const packed: PackExemplar[] = exemplars.map((e) => ({
    label: e.label,
    category: e.category,
    vector: e.vector,
    associations: e.associations,
    positive: e.positive,
  }));
  return {
    format: PACK_FORMAT,
    version: PACK_VERSION,
    model: PACK_MODEL,
    dim: PACK_DIM,
    createdAt,
    count: packed.length,
    exemplars: packed,
  };
}

export function serializePack(pack: TeachingPack): string {
  return JSON.stringify(pack);
}

// Parse + validate untrusted JSON into a pack. Throws an Error with a
// user-readable message on anything malformed or incompatible, so the caller can
// surface it directly in a toast.
export function parsePack(text: string): TeachingPack {
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    throw new Error('Not a valid teaching pack (could not read the file as JSON)');
  }
  if (!raw || typeof raw !== 'object') throw new Error('Not a teaching pack');
  const obj = raw as Record<string, unknown>;
  if (obj.format !== PACK_FORMAT) throw new Error('This file isn’t a Memeget teaching pack');
  if (obj.model !== PACK_MODEL || obj.dim !== PACK_DIM) {
    throw new Error('Pack was made with a different model and can’t be used here');
  }
  if (!Array.isArray(obj.exemplars)) throw new Error('Teaching pack has no examples');

  const exemplars: PackExemplar[] = [];
  for (const item of obj.exemplars) {
    if (!item || typeof item !== 'object') continue;
    const e = item as Record<string, unknown>;
    const label = typeof e.label === 'string' ? e.label.trim() : '';
    if (!label) continue;
    if (!Array.isArray(e.vector) || e.vector.length !== PACK_DIM) continue;
    const vector = (e.vector as unknown[]).map(Number);
    if (vector.some((v) => !Number.isFinite(v))) continue;
    const associations = Array.isArray(e.associations)
      ? (e.associations as unknown[]).map((a) => String(a)).filter(Boolean)
      : [];
    exemplars.push({
      label,
      category: typeof e.category === 'string' && e.category ? e.category : 'character',
      vector,
      associations,
      positive: e.positive !== false, // default to a positive example
    });
  }
  if (exemplars.length === 0) throw new Error('Teaching pack had no usable examples');

  return {
    format: PACK_FORMAT,
    version: typeof obj.version === 'number' ? obj.version : PACK_VERSION,
    model: PACK_MODEL,
    dim: PACK_DIM,
    createdAt: typeof obj.createdAt === 'number' ? obj.createdAt : 0,
    count: exemplars.length,
    exemplars,
  };
}
