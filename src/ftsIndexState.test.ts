// The FTS index is the one derived structure a query TRUSTS instead of
// recomputing: when `isCurrent()` says yes, search ranks off BM25 and never
// checks the rows. So these tests are about two things a careless edit breaks
// silently — that a repair which raced a write is never trusted (wrong results,
// invisible), and that a single-row write never plans a whole-library rebuild
// (the freeze this state machine exists to stop).
import { FtsIndexState } from './ftsIndexState';

describe('FtsIndexState', () => {
  it('needs a full build before anything has been indexed', () => {
    const state = new FtsIndexState();
    expect(state.isCurrent()).toBe(false);
    expect(state.planRepair()).toEqual({ kind: 'full', version: 0 });
  });

  it('is current after a full build that no write raced', () => {
    const state = new FtsIndexState();
    const repair = state.planRepair()!;
    state.completeRepair(repair);
    expect(state.isCurrent()).toBe(true);
    expect(state.planRepair()).toBeNull();
  });

  it('repairs only the rows a known write touched', () => {
    const state = new FtsIndexState();
    state.completeRepair(state.planRepair()!);

    state.noteContentChange([7]);
    state.noteContentChange([9]);

    expect(state.isCurrent()).toBe(false);
    expect(state.planRepair()).toEqual({ kind: 'rows', ids: [7, 9], version: 2 });
  });

  it('goes back to current once those rows are re-indexed', () => {
    const state = new FtsIndexState();
    state.completeRepair(state.planRepair()!);
    state.noteContentChange([7]);

    state.completeRepair(state.planRepair()!);

    expect(state.isCurrent()).toBe(true);
    expect(state.planRepair()).toBeNull();
  });

  it('refuses to trust a repair that a write landed during', () => {
    const state = new FtsIndexState();
    state.completeRepair(state.planRepair()!);
    state.noteContentChange([7]);
    const repair = state.planRepair()!;

    // The write-out is in flight when another describe commits.
    state.noteContentChange([8]);
    state.completeRepair(repair);

    expect(state.isCurrent()).toBe(false);
    // Row 7 stays dirty too: its entry may have been read before that write.
    expect(state.planRepair()).toEqual({ kind: 'rows', ids: [7, 8], version: 2 });
  });

  it('escalates to a full build for an unknown-scope change', () => {
    const state = new FtsIndexState();
    state.completeRepair(state.planRepair()!);
    state.noteContentChange([7]);

    state.noteContentChange(); // library import / clear / restore

    expect(state.planRepair()).toEqual({ kind: 'full', version: 2 });
  });

  it('keeps demanding a full build until one completes', () => {
    const state = new FtsIndexState();
    state.noteContentChange();
    state.noteContentChange([7]); // a known row cannot downgrade the full rebuild
    expect(state.planRepair()).toEqual({ kind: 'full', version: 2 });
  });

  it('treats an empty id list as no change at all', () => {
    // A caller mapping an empty batch of rows to ids must not be able to leave
    // the index permanently not-current with no repair that can fix it.
    const state = new FtsIndexState();
    state.completeRepair(state.planRepair()!);

    state.noteContentChange([]);

    expect(state.isCurrent()).toBe(true);
    expect(state.planRepair()).toBeNull();
  });
});
