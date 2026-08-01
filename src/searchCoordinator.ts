export interface ProgressiveSearchOptions<Result, Vector> {
  lexicalSearch: () => Promise<Result | null>;
  embed?: () => Promise<Vector>;
  hybridSearch?: (vector: Vector) => Promise<Result | null>;
  publish: (results: Result) => void;
  isCurrent: () => boolean;
}

// Runs the cheap lexical and model-backed search paths concurrently. Lexical
// results can paint immediately; the dense result upgrades them when ready.
// A late lexical completion must never downgrade an already-published hybrid.
export async function runProgressiveSearch<Result, Vector>({
  lexicalSearch,
  embed,
  hybridSearch,
  publish,
  isCurrent,
}: ProgressiveSearchOptions<Result, Vector>): Promise<void> {
  let hybridPublished = false;

  const lexicalTask = lexicalSearch()
    .then((results) => {
      if (results !== null && isCurrent() && !hybridPublished) publish(results);
    })
    .catch(() => {});

  const hybridTask =
    embed && hybridSearch
      ? embed()
          .then((vector) => (isCurrent() ? hybridSearch(vector) : null))
          .then((results) => {
            if (results !== null && isCurrent()) {
              hybridPublished = true;
              publish(results);
            }
          })
          .catch(() => {})
      : Promise.resolve();

  await Promise.all([lexicalTask, hybridTask]);
}
