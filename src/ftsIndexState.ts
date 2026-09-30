// Dirt bookkeeping for the FTS5 lexical index.
//
// The index is derived data: every searchable-content write (a describe, a
// transcript, a tag edit) makes it stale, and something has to put it back.
// Doing that as a whole-table DELETE + one INSERT per meme is what turned the
// VLM enrichment pass into a UI freeze — one described meme every ~20s left the
// index permanently stale, so every keystroke scheduled another 2000-row
// rebuild that the next describe invalidated before it could be used. The work
// was O(library) per row written, on the JS thread, forever.
//
// This tracks WHICH rows went stale so the repair can be O(rows changed). It is
// deliberately pure — no SQL, no db module — so the invariant below is unit
// testable without expo-sqlite.
//
// The invariant: `isCurrent()` is true ONLY when the index reflects the content
// exactly. A repair that raced a write is never trusted; it leaves its dirt in
// place and the next repair redoes it. Serving a query from a "clean" index
// that is actually stale is the one failure mode that must be impossible —
// falling back to the in-memory scan is merely slower.

// A repair job: rebuild everything, or re-index a known set of rows.
export type FtsRepair =
  | { kind: 'full'; version: number }
  | { kind: 'rows'; ids: number[]; version: number };

export class FtsIndexState {
  // Bumped on every searchable-content change. The index is usable only when
  // `builtVersion` still equals it.
  private version = 0;
  private builtVersion = -1;
  // Unknown-scope invalidation (library import, clear, "shouldn't happen"
  // fallbacks): only a full rebuild can clear it.
  private fullDirty = true;
  private dirtyIds = new Set<number>();

  // Record a searchable-content write. Pass the affected row ids when they are
  // known; omit them (or pass nothing) when the change is library-wide or of
  // unknown scope, which forces the next repair to be a full rebuild.
  //
  // An EMPTY id array means "nothing changed" and is a no-op: bumping the
  // version without recording dirt would leave the index permanently
  // not-current with no repair able to fix it, silently dropping BM25 ranking
  // for the rest of the session. Callers that map a possibly-empty batch of
  // rows to ids get that for free.
  noteContentChange(ids?: readonly number[]): void {
    if (ids && ids.length === 0) return;
    this.version++;
    if (!ids) {
      this.fullDirty = true;
      this.dirtyIds.clear();
      return;
    }
    // Already facing a full rebuild — individual ids add nothing.
    if (this.fullDirty) return;
    for (const id of ids) this.dirtyIds.add(id);
  }

  // Is the index safe to rank with right now?
  isCurrent(): boolean {
    return this.builtVersion === this.version;
  }

  // The repair the index needs, or null when it is already current. The version
  // is captured here so `completeRepair` can tell whether a write raced it.
  planRepair(): FtsRepair | null {
    if (this.fullDirty || this.builtVersion < 0) return { kind: 'full', version: this.version };
    if (this.dirtyIds.size > 0) return { kind: 'rows', ids: [...this.dirtyIds], version: this.version };
    return null;
  }

  // Report a finished repair. Dirt is cleared — and the index declared current
  // — only when no write landed while the repair ran. Otherwise everything
  // stays dirty: redoing a handful of rows is cheap next to the risk of
  // trusting an index that missed a write.
  completeRepair(repair: FtsRepair): void {
    if (this.version !== repair.version) return;
    this.builtVersion = this.version;
    this.fullDirty = false;
    this.dirtyIds.clear();
  }

  // Test hook: back to the cold state a fresh app launch starts in.
  resetForTest(): void {
    this.version = 0;
    this.builtVersion = -1;
    this.fullDirty = true;
    this.dirtyIds.clear();
  }
}
