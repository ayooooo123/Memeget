export interface TagSearchRequest {
  label: string;
  nonce: number;
}

export function createTagSearchRequest(label: string, previousNonce: number): TagSearchRequest | null {
  const normalized = label.trim();
  if (!normalized) return null;
  return { label: normalized, nonce: previousNonce + 1 };
}

export function shouldShowTaughtTags(count: number, expanded: boolean): boolean {
  return count > 0 && expanded;
}
