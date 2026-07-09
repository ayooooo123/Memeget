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

const CLIP_PRIMARY: EmbeddingModelSpec = {
  id: 'clip-vit-base-patch32',
  label: 'CLIP ViT-B/32',
  dim: 512,
  space: 'primary',
  available: true,
  notes: 'Current react-native-executorch image/text embedding pair.',
};

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
  const imageModelSource = env.EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_IMAGE_MODEL_SOURCE;
  const textModelSource = env.EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_TEXT_MODEL_SOURCE;
  const tokenizerSource = env.EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_TOKENIZER_SOURCE;
  if (!imageModelSource || !textModelSource || !tokenizerSource) return CLIP_PRIMARY;

  return {
    id: env.EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_MODEL_ID || MOBILECLIP_S2_ID,
    label: 'MobileCLIP-S2',
    dim: envNumber(env, 'EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_DIM', 512),
    space: 'primary',
    available: true,
    notes: 'Custom react-native-executorch image/text embedding pair supplied by environment.',
    imageModelSource,
    textModelSource,
    tokenizerSource,
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

const MOBILECLIP_S2_CANDIDATE: EmbeddingModelSpec = {
  id: MOBILECLIP_S2_ID,
  label: 'MobileCLIP-S2',
  dim: 512,
  space: 'primary',
  available: false,
  notes: 'Candidate replacement primary text/image space once a compatible export exists.',
};

const DINO_CANDIDATE: EmbeddingModelSpec = {
  id: 'dinov2',
  label: 'DINOv2 visual',
  dim: 768,
  space: 'visual',
  available: false,
  notes: 'Candidate visual-similarity space for near-duplicates and template variants.',
};

export const FUTURE_EMBEDDING_MODELS = {
  mobileClipS2:
    PRIMARY_EMBEDDING_MODEL.id === MOBILECLIP_S2_ID
      ? PRIMARY_EMBEDDING_MODEL
      : MOBILECLIP_S2_CANDIDATE,
  dinov2: VISUAL_EMBEDDING_MODEL.available ? VISUAL_EMBEDDING_MODEL : DINO_CANDIDATE,
} as const satisfies Record<string, EmbeddingModelSpec>;

export function modelStamp(spec: Pick<EmbeddingModelSpec, 'id' | 'dim'>): string {
  return `${spec.id}@${spec.dim}`;
}

export function isTeachingPackCompatible(model: string, dim: number): boolean {
  return model === PRIMARY_EMBEDDING_MODEL.id && dim === PRIMARY_EMBEDDING_MODEL.dim;
}
