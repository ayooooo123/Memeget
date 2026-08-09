export type VisionSkipMode = 'never' | 'on-uncertain' | 'always';

export const DEFAULT_VISION_SKIP_MODE: VisionSkipMode = 'on-uncertain';

/** Min entity hit score that can justify skipping auto VLM under on-uncertain. */
export const ENTITY_VLM_SKIP_CONF = 0.56;

/**
 * Whether library auto-describe may skip the VLM generate pass.
 * Forced single-describe / enrich paths should bypass this (force: true at call site).
 */
export function shouldSkipAutoVision(p: {
  mode: VisionSkipMode;
  recognitionTier: 'recognized' | 'weak' | 'unknown';
  entityHits: { score: number }[];
  entitySkipConfidence?: number;
}): boolean {
  if (p.mode === 'never') return false;
  if (p.mode === 'always') return true;
  // on-uncertain: skip when zero-shot already recognized, or a strong entity hit.
  if (p.recognitionTier === 'recognized') return true;
  const thr = p.entitySkipConfidence ?? ENTITY_VLM_SKIP_CONF;
  return p.entityHits.some((h) => h.score >= thr);
}
