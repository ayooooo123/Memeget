export type MediaKind = 'image' | 'video';

export interface Tag {
  label: string;
  category: string;
  score: number;
  source?: 'prompt' | 'exemplar' | 'ocr' | 'vision'; // how the label was matched
}

// Lifecycle of the optional VLM enrichment pass for a meme.
//  pending -> not yet described · done -> described · failed -> describe errored
//  (won't auto-retry; a manual "Describe library" re-run can reset failures).
export type VisionState = 'pending' | 'done' | 'failed';

// Lifecycle of the optional audio-transcription pass.
//  none    -> nothing to analyze (images; never queued)
//  pending -> a video awaiting transcription
//  done    -> analyzed; transcript may be '' when the video has no audio/speech
//  failed  -> extraction/transcription errored (won't auto-retry)
export type AudioState = 'none' | 'pending' | 'done' | 'failed';

export interface MemeRecord {
  id: number;
  uri: string; // original SAF content:// uri (used for display)
  name: string;
  kind: MediaKind;
  ocrText: string;
  caption: string; // one-line scene/joke description from the VLM ('' until enriched)
  transcript: string; // speech heard in a video, via on-device Whisper ('' until analyzed)
  tags: Tag[];
  extraTerms: string; // association/world-knowledge terms, for search
  visionState: VisionState;
  audioState: AudioState;
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
