export type MediaKind = 'image' | 'video';

export interface Tag {
  label: string;
  category: string;
  score: number;
  source?: 'prompt' | 'exemplar'; // how the label was matched
}

export interface MemeRecord {
  id: number;
  uri: string; // original SAF content:// uri (used for display)
  name: string;
  kind: MediaKind;
  ocrText: string;
  tags: Tag[];
  extraTerms: string; // association/world-knowledge terms, for search
  indexedAt: number;
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
