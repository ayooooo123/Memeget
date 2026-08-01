export interface ResponsiveTagApplyOptions {
  persist: () => Promise<unknown>;
  onPersisted: () => void;
  propagate?: () => Promise<number>;
  onPropagated?: (count: number) => void;
  onPropagationError?: (error: unknown) => void;
}

// The durable write is the user-facing operation. Expensive whole-library
// propagation starts only after that state is visible and never holds the sheet
// open or turns a successful tag into a failed one.
export async function applyTagResponsively({
  persist,
  onPersisted,
  propagate,
  onPropagated,
  onPropagationError,
}: ResponsiveTagApplyOptions): Promise<void> {
  await persist();
  onPersisted();
  if (!propagate) return;
  void propagate().then(
    (count) => onPropagated?.(count),
    (error) => onPropagationError?.(error)
  );
}
