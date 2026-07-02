export type MediaKind = 'image' | 'video';

export interface Tag {
  label: string;
  category: string;
  score: number;
  source?: 'prompt' | 'exemplar' | 'ocr' | 'vision'; // how the label was matched
}

// Lifecycle of the optional LFM2-VL enrichment pass for a meme.
//  pending -> not yet described · done -> described · failed -> describe errored
//  (won't auto-retry; a manual "Describe library" re-run can reset failures).
export type VisionState = 'pending' | 'done' | 'failed';

export interface MemeRecord {
  id: number;
  uri: string; // original SAF content:// uri (used for display)
  name: string;
  kind: MediaKind;
  ocrText: string;
  caption: string; // one-line scene/joke description from LFM2-VL ('' until enriched)
  tags: Tag[];
  extraTerms: string; // association/world-knowledge terms, for search
  visionState: VisionState;
  indexedAt: number;
  modifiedAt?: number; // file's last-modified time (ms); drives the recency sort
  pending?: boolean; // saved & visible, but not yet embedded/OCR'd/tagged
}

// A user-taught (or pack-provided) reference example: an image embedding that
// names a concept the base model can't, e.g. "Milady". Tagging compares a
// meme's image embedding directly against these (image-to-image).
export interface Exemplar {
  id: number;
  label: string;
  category: string;
  vector: number[]; // normalized image embedding
  associations: string[];
  sourceUri: string;
  positive: boolean; // false = a "this is NOT a <label>" negative example
  origin: 'self' | 'pack'; // taught here, or imported from a teaching pack
  pack: string; // source pack name when origin === 'pack', else ''
  createdAt: number;
}

export interface SearchHit extends MemeRecord {
  score: number;
}

export interface LinkedFolder {
  uri: string;
  name: string;
  addedAt: number;
}

export type EmbeddingFn = (uri: string) => Promise<number[]>;
export type TextEmbeddingFn = (text: string) => Promise<number[]>;
