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
  it('stamps MobileCLIP-S2 as the primary vector space', () => {
    expect(PRIMARY_EMBEDDING_MODEL.id).toBe('mobileclip-s2');
    expect(PRIMARY_EMBEDDING_MODEL.dim).toBe(512);
    expect(PRIMARY_EMBEDDING_MODEL.available).toBe(true);
    expect(modelStamp(PRIMARY_EMBEDDING_MODEL)).toBe('mobileclip-s2@512');
  });

  it('defaults the primary to MobileCLIP-S2 with baked sources when no env is set', () => {
    const s = primaryEmbeddingModelFromEnv({});
    expect(s.id).toBe('mobileclip-s2');
    expect(s.dim).toBe(512);
    expect(s.available).toBe(true);
    expect(String(s.imageModelSource)).toContain('mobileclip_s2_image_xnnpack');
    expect(String(s.textModelSource)).toContain('mobileclip_s2_text_xnnpack');
    expect(String(s.tokenizerSource)).toContain('mobileclip_s2_tokenizer');
  });

  it('exposes MobileCLIP-S2 as active and DINOv2 as a visual candidate', () => {
    expect(FUTURE_EMBEDDING_MODELS.mobileClipS2.id).toBe('mobileclip-s2');
    expect(FUTURE_EMBEDDING_MODELS.mobileClipS2.available).toBe(true);
    expect(FUTURE_EMBEDDING_MODELS.dinov2.space).toBe('visual');
    expect(FUTURE_EMBEDDING_MODELS.dinov2.available).toBe(false);
  });

  it('accepts teaching packs only from the active primary image space', () => {
    expect(isTeachingPackCompatible('mobileclip-s2', 512)).toBe(true);
    expect(isTeachingPackCompatible('clip-vit-base-patch32', 512)).toBe(false);
    expect(isTeachingPackCompatible('mobileclip-s2', 768)).toBe(false);
  });

  it('uses the shared primary model stamp for teaching packs', () => {
    expect(PACK_MODEL).toBe(PRIMARY_EMBEDDING_MODEL.id);
    expect(PACK_DIM).toBe(PRIMARY_EMBEDDING_MODEL.dim);
  });

  it('overrides the primary sources only when a full custom export is supplied', () => {
    // A partial override is ignored — falls back to the baked S2 defaults so an
    // image encoder is never paired with the default text encoder.
    const partial = primaryEmbeddingModelFromEnv({
      EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_IMAGE_MODEL_SOURCE: 's2-image.pte',
      EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_TEXT_MODEL_SOURCE: 's2-text.pte',
    });
    expect(String(partial.imageModelSource)).toContain('mobileclip_s2_image_xnnpack');

    const model = primaryEmbeddingModelFromEnv({
      EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_IMAGE_MODEL_SOURCE: 's2-image.pte',
      EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_TEXT_MODEL_SOURCE: 's2-text.pte',
      EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_TOKENIZER_SOURCE: 'tokenizer.bin',
      EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_DIM: '768',
      EXPO_PUBLIC_MEMEGET_MOBILECLIP_S2_MODEL_ID: 'mobileclip-s2-int8',
    });
    expect(model.id).toBe('mobileclip-s2-int8');
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
