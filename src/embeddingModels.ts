export type EmbeddingSpace = 'primary' | 'visual';

export interface EmbeddingModelSpec {
  id: string;
  label: string;
  dim: number;
  space: EmbeddingSpace;
  available: boolean;
  notes: string;
}

export const PRIMARY_EMBEDDING_MODEL: EmbeddingModelSpec = {
  id: 'clip-vit-base-patch32',
  label: 'CLIP ViT-B/32',
  dim: 512,
  space: 'primary',
  available: true,
  notes: 'Current react-native-executorch image/text embedding pair.',
};

export const VISUAL_EMBEDDING_MODEL: EmbeddingModelSpec = {
  id: 'dinov2',
  label: 'DINOv2 visual',
  dim: 768,
  space: 'visual',
  available: false,
  notes: 'Reserved visual-similarity slot; no react-native-executorch image export is wired yet.',
};

export const FUTURE_EMBEDDING_MODELS = {
  mobileClipS2: {
    id: 'mobileclip-s2',
    label: 'MobileCLIP-S2',
    dim: 512,
    space: 'primary',
    available: false,
    notes: 'Candidate replacement primary text/image space once a compatible export exists.',
  },
  dinov2: VISUAL_EMBEDDING_MODEL,
} as const satisfies Record<string, EmbeddingModelSpec>;

export function modelStamp(spec: Pick<EmbeddingModelSpec, 'id' | 'dim'>): string {
  return `${spec.id}@${spec.dim}`;
}

export function isTeachingPackCompatible(model: string, dim: number): boolean {
  return model === PRIMARY_EMBEDDING_MODEL.id && dim === PRIMARY_EMBEDDING_MODEL.dim;
}
