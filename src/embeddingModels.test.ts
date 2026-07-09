import {
  FUTURE_EMBEDDING_MODELS,
  PRIMARY_EMBEDDING_MODEL,
  isTeachingPackCompatible,
  modelStamp,
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
});
