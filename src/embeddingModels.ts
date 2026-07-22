export type EmbeddingSpace = 'primary' | 'visual';

export type EmbeddingModelSource = string | number | Record<string, unknown>;

export interface EmbeddingModelSpec {
  id: string;
  label: string;
  dim: number;
  space: EmbeddingSpace;
  available: boolean;
  notes: string;
  imageModelSource?: EmbeddingModelSource;
  textModelSource?: EmbeddingModelSource;
  tokenizerSource?: EmbeddingModelSource;
}

type Env = Record<string, string | undefined>;

// MobileCLIP-S2 is the app's primary image/text embedding space. The fp32
// XNNPACK .pte pair + tokenizer are hosted on the models-v1 release and pulled
// once on first launch (same react-native-executorch runtime as the VLM and
// Moonshine). These baked defaults mean a build with no env overrides still
// ships S2 — the app must never silently fall back to a different vector space.
const MOBILECLIP_S2_BASE =
  'https://github.com/ayooooo123/Memeget/releases/download/models-v1';
const DEFAULT_S2_IMAGE = `${MOBILECLIP_S2_BASE}/mobileclip_s2_image_xnnpack_fp32.pte`;
const DEFAULT_S2_TEXT = `${MOBILECLIP_S2_BASE}/mobileclip_s2_text_xnnpack_fp32.pte`;
const DEFAULT_S2_TOKENIZER = `${MOBILECLIP_S2_BASE}/mobileclip_s2_tokenizer.json`;
const MOBILECLIP_S2_DIM = 512;

const MOBILECLIP_S2_ID = 'mobileclip-s2';
const DINO_ID = 'dinov2';

declare const process:
  | {
      env: Env;
    }
  | undefined;

function currentEnv(): Env {
  if (typeof process === 'undefined') return {};
  return {
    EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_IMAGE_MODEL_SOURCE:
      process.env.EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_IMAGE_MODEL_SOURCE,
    EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_TEXT_MODEL_SOURCE:
      process.env.EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_TEXT_MODEL_SOURCE,
    EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_TOKENIZER_SOURCE:
      process.env.EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_TOKENIZER_SOURCE,
    EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_DIM:
      process.env.EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_DIM,
    EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_MODEL_ID:
      process.env.EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_MODEL_ID,
    EXPO_PUBLIC_MEMEGET_DINOV2_IMAGE_MODEL_SOURCE:
      process.env.EXPO_PUBLIC_MEMEGET_DINOV2_IMAGE_MODEL_SOURCE,
    EXPO_PUBLIC_MEMEGET_DINOV2_DIM: process.env.EXPO_PUBLIC_MEMEGET_DINOV2_DIM,
    EXPO_PUBLIC_MEMEGET_DINOV2_MODEL_ID: process.env.EXPO_PUBLIC_MEMEGET_DINOV2_MODEL_ID,
  };
}

function envNumber(env: Env, key: string, fallback: number): number {
  const parsed = Number(env[key]);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

export function primaryEmbeddingModelFromEnv(env: Env = currentEnv()): EmbeddingModelSpec {
  const envImage = env.EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_IMAGE_MODEL_SOURCE;
  const envText = env.EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_TEXT_MODEL_SOURCE;
  const envTokenizer = env.EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_TOKENIZER_SOURCE;
  // All three env sources must be set to override the baked defaults; a partial
  // override is ignored entirely, so a custom image encoder can never be paired
  // with the default text encoder (that would be a different vector space).
  const custom = !!(envImage && envText && envTokenizer);
  return {
    id: (custom && env.EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_MODEL_ID) || MOBILECLIP_S2_ID,
    label: 'MobileCLIP-S2',
    dim: custom
      ? envNumber(env, 'EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_DIM', MOBILECLIP_S2_DIM)
      : MOBILECLIP_S2_DIM,
    space: 'primary',
    available: true,
    notes: custom
      ? 'Custom MobileCLIP-S2 image/text export supplied by environment.'
      : 'MobileCLIP-S2 image/text embedding pair (react-native-executorch).',
    imageModelSource: custom ? envImage : DEFAULT_S2_IMAGE,
    textModelSource: custom ? envText : DEFAULT_S2_TEXT,
    tokenizerSource: custom ? envTokenizer : DEFAULT_S2_TOKENIZER,
  };
}

export function visualEmbeddingModelFromEnv(env: Env = currentEnv()): EmbeddingModelSpec {
  const imageModelSource = env.EXPO_PUBLIC_MEMEGET_DINOV2_IMAGE_MODEL_SOURCE;
  if (!imageModelSource) {
    return {
      id: DINO_ID,
      label: 'DINOv2 visual',
      dim: 768,
      space: 'visual',
      available: false,
      notes: 'Reserved visual-similarity slot; set EXPO_PUBLIC_MEMEGET_DINOV2_IMAGE_MODEL_SOURCE to enable a custom export.',
    };
  }

  return {
    id: env.EXPO_PUBLIC_MEMEGET_DINOV2_MODEL_ID || DINO_ID,
    label: 'DINOv2 visual',
    dim: envNumber(env, 'EXPO_PUBLIC_MEMEGET_DINOV2_DIM', 768),
    space: 'visual',
    available: true,
    notes: 'Custom visual-similarity image model supplied by environment.',
    imageModelSource,
  };
}

export const PRIMARY_EMBEDDING_MODEL: EmbeddingModelSpec = primaryEmbeddingModelFromEnv();

export const VISUAL_EMBEDDING_MODEL: EmbeddingModelSpec = visualEmbeddingModelFromEnv();

const DINO_CANDIDATE: EmbeddingModelSpec = {
  id: 'dinov2',
  label: 'DINOv2 visual',
  dim: 768,
  space: 'visual',
  available: false,
  notes: 'Candidate visual-similarity space for near-duplicates and template variants.',
};

export const FUTURE_EMBEDDING_MODELS = {
  mobileClipS2: PRIMARY_EMBEDDING_MODEL,
  dinov2: VISUAL_EMBEDDING_MODEL.available ? VISUAL_EMBEDDING_MODEL : DINO_CANDIDATE,
} as const satisfies Record<string, EmbeddingModelSpec>;

export function modelStamp(spec: Pick<EmbeddingModelSpec, 'id' | 'dim'>): string {
  return `${spec.id}@${spec.dim}`;
}

export function isTeachingPackCompatible(model: string, dim: number): boolean {
  return model === PRIMARY_EMBEDDING_MODEL.id && dim === PRIMARY_EMBEDDING_MODEL.dim;
}
