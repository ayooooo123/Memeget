export type MediaKind = 'image' | 'video';

export interface Tag {
  label: string;
  category: string;
  score: number;
}

export interface MemeRecord {
  id: number;
  uri: string; // original SAF content:// uri (used for display)
  name: string;
  kind: MediaKind;
  ocrText: string;
  tags: Tag[];
  indexedAt: number;
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
