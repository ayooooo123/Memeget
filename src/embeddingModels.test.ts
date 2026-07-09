import {
  FUTURE_EMBEDDING_MODELS,
  PRIMARY_EMBEDDING_MODEL,
  isTeachingPackCompatible,
  modelStamp,
  primaryEmbeddingModelFromEnv,
  visualEmbeddingModelFromEnv,
} from './embeddingModels';
import { PACK_DIM, PACK_MODEL } from './teachingPack';

describe('embedding model registry', () => {
  it('stamps the current primary CLIP vector space', () => {
    expect(PRIMARY_EMBEDDING_MODEL.id).toBe('clip-vit-base-patch32');
    expect(PRIMARY_EMBEDDING_MODEL.dim).toBe(512);
    expect(modelStamp(PRIMARY_EMBEDDING_MODEL)).toBe('clip-vit-base-patch32@512');
  });

  it('tracks MobileCLIP-S2 and DINOv2 as unavailable future candidates', () => {
    expect(FUTURE_EMBEDDING_MODELS.mobileClipS2.available).toBe(false);
    expect(FUTURE_EMBEDDING_MODELS.mobileClipS2.space).toBe('primary');
    expect(FUTURE_EMBEDDING_MODELS.dinov2.available).toBe(false);
    expect(FUTURE_EMBEDDING_MODELS.dinov2.space).toBe('visual');
  });

  it('accepts teaching packs only from the active primary image space', () => {
    expect(isTeachingPackCompatible('clip-vit-base-patch32', 512)).toBe(true);
    expect(isTeachingPackCompatible('mobileclip-s2', 512)).toBe(false);
    expect(isTeachingPackCompatible('clip-vit-base-patch32', 768)).toBe(false);
  });

  it('uses the shared primary model stamp for teaching packs', () => {
    expect(PACK_MODEL).toBe(PRIMARY_EMBEDDING_MODEL.id);
    expect(PACK_DIM).toBe(PRIMARY_EMBEDDING_MODEL.dim);
  });

  it('enables MobileCLIP-S2 only when image, text, and tokenizer sources are supplied', () => {
    expect(
      primaryEmbeddingModelFromEnv({
        EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_IMAGE_MODEL_SOURCE: 's2-image.pte',
        EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_TEXT_MODEL_SOURCE: 's2-text.pte',
      }).id
    ).toBe('clip-vit-base-patch32');

    const model = primaryEmbeddingModelFromEnv({
      EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_IMAGE_MODEL_SOURCE: 's2-image.pte',
      EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_TEXT_MODEL_SOURCE: 's2-text.pte',
      EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_TOKENIZER_SOURCE: 'tokenizer.bin',
      EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_DIM: '768',
    });

    expect(model.id).toBe('mobileclip-s2');
    expect(model.available).toBe(true);
    expect(model.dim).toBe(768);
    expect(model.imageModelSource).toBe('s2-image.pte');
    expect(model.textModelSource).toBe('s2-text.pte');
    expect(model.tokenizerSource).toBe('tokenizer.bin');
  });

  it('enables DINOv2 visual vectors when a custom image source is supplied', () => {
    expect(visualEmbeddingModelFromEnv({}).available).toBe(false);

    const model = visualEmbeddingModelFromEnv({
      EXPO_PUBLIC_MEMEGET_DINOV2_IMAGE_MODEL_SOURCE: 'dinov2-image.pte',
      EXPO_PUBLIC_MEMEGET_DINOV2_MODEL_ID: 'dinov2-small-custom',
    });

    expect(model.id).toBe('dinov2-small-custom');
    expect(model.available).toBe(true);
    expect(model.dim).toBe(768);
    expect(model.imageModelSource).toBe('dinov2-image.pte');
  });
});
