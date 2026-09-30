// Typo-tolerant and prefix term expansion for local search — the piece that
// makes the keyword channel behave like Typesense (fuzzy matching,
// search-as-you-type) with no server and no native extension.
//
// It is deliberately PURE: no imports, no I/O. Like memeSql.ts / memeFtsSql.ts
// it can be exercised in a unit test without pulling in expo-sqlite or the
// native model runtime. The consumer (search path) builds a SearchVocab from
// the already-resident search index and asks it to expand the query's terms;
// the expansions ride the existing low-weight `expandedTerms` channel, so an
// exact match can never be outranked by a fuzzy one — typo/partial recall is
// only ever ADDED.

// Adaptive edit-distance budget by term length, mirroring Typesense's default
// typo tolerance: short words tolerate no typos (too many false corrections),
// longer words tolerate more. A 3-char query correcting to another 3-char word
// is almost always wrong; an 8-char one with a single slip almost always right.
export function typoBudget(len: number): number {
  if (len <= 3) return 0;
  if (len <= 6) return 1;
  return 2;
}

// Levenshtein edit distance, bounded: returns the true distance when it is
// <= maxDist, otherwise maxDist + 1 as soon as the whole current row provably
// exceeds the budget. The early exit is what keeps BK-tree traversal cheap —
// most candidates are rejected without finishing the DP.
export function boundedEditDistance(a: string, b: string, maxDist: number): number {
  const la = a.length;
  const lb = b.length;
  if (Math.abs(la - lb) > maxDist) return maxDist + 1;
  if (la === 0) return lb <= maxDist ? lb : maxDist + 1;
  if (lb === 0) return la <= maxDist ? la : maxDist + 1;

  let prev = new Array<number>(lb + 1);
  let curr = new Array<number>(lb + 1);
  for (let j = 0; j <= lb; j++) prev[j] = j;

  for (let i = 1; i <= la; i++) {
    curr[0] = i;
    let rowMin = i;
    const ca = a.charCodeAt(i - 1);
    for (let j = 1; j <= lb; j++) {
      const cost = ca === b.charCodeAt(j - 1) ? 0 : 1;
      const d = Math.min(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost);
      curr[j] = d;
      if (d < rowMin) rowMin = d;
    }
    if (rowMin > maxDist) return maxDist + 1;
    const tmp = prev;
    prev = curr;
    curr = tmp;
  }
  return prev[lb] <= maxDist ? prev[lb] : maxDist + 1;
}

// A vocab token is any run of >=2 alphanumerics, lowercased. Matches the shape
// the lexical channel searches (unicode61 word tokens); 1-char tokens are pure
// noise for correction and prefixing.
const VOCAB_TOKEN_RE = /[a-z0-9]+/g;
function tokenizeVocab(text: string, out: Map<string, number>): void {
  VOCAB_TOKEN_RE.lastIndex = 0;
  let m: RegExpExecArray | null;
  const lower = text.toLowerCase();
  while ((m = VOCAB_TOKEN_RE.exec(lower))) {
    const tok = m[0];
    if (tok.length >= 2) out.set(tok, (out.get(tok) ?? 0) + 1);
  }
}

interface BkNode {
  term: string;
  children: Map<number, BkNode>;
}

export const VOCAB_BUILD_CHUNK = 400;

// A corpus vocabulary that answers two questions cheaply:
//   - "what real word did the user probably mean?" (BK-tree, edit distance)
//   - "what words start with what they've typed so far?" (sorted-array prefix)
// Frequency is carried so ties resolve toward the word that actually appears
// most in the library — the likelier intent.
export class SearchVocab {
  private root?: BkNode;
  private sorted: string[] = [];
  private freq = new Map<string, number>();

  static build(texts: Iterable<string>): SearchVocab {
    const vocab = new SearchVocab();
    for (const text of texts) tokenizeVocab(text, vocab.freq);
    // Insert in frequency-descending order so the BK-tree root and upper nodes
    // are common words: correction walks reach the likely answers first, and it
    // keeps the tree from degenerating on adversarial insertion order.
    vocab.sorted = [...vocab.freq.keys()].sort();
    const byFreq = [...vocab.freq.entries()].sort(
      (a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1)
    );
    for (const [term] of byFreq) vocab.insert(term);
    return vocab;
  }

  // Documents tokenized (and terms inserted) between yields. A cold build over
  // a 2272-meme library measured 802ms of unbroken synchronous work on device —
  // an 800ms dead search bar. Slicing it hands the event loop back so the
  // keystroke, the grid and the timers keep running while it finishes.
  //
  // `yieldTo` is injected rather than reaching for setTimeout so this module
  // stays pure and a test can drive the slicing without real time passing.
  static async buildAsync(
    texts: readonly string[],
    yieldTo: () => Promise<void>,
    chunk = VOCAB_BUILD_CHUNK
  ): Promise<SearchVocab> {
    const vocab = new SearchVocab();
    for (let i = 0; i < texts.length; i += chunk) {
      const end = Math.min(i + chunk, texts.length);
      for (let j = i; j < end; j++) tokenizeVocab(texts[j], vocab.freq);
      if (end < texts.length) await yieldTo();
    }
    vocab.sorted = [...vocab.freq.keys()].sort();
    const byFreq = [...vocab.freq.entries()].sort(
      (a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1)
    );
    for (let i = 0; i < byFreq.length; i += chunk) {
      const end = Math.min(i + chunk, byFreq.length);
      for (let j = i; j < end; j++) vocab.insert(byFreq[j][0]);
      if (end < byFreq.length) await yieldTo();
    }
    return vocab;
  }

  get size(): number {
    return this.freq.size;
  }

  has(term: string): boolean {
    return this.freq.has(term);
  }

  // Fold more documents into an existing vocabulary. This is what keeps the
  // corpus vocabulary off the keystroke path while the library is being
  // enriched: a describe changes ONE meme, and re-tokenizing the whole library
  // plus rebuilding a 12k-term BK-tree to learn its handful of new words is a
  // visible freeze on the next search. Growing costs the changed documents.
  //
  // Words are only ever ADDED, never retired — a word whose last occurrence was
  // just edited away lingers in the vocabulary. That is deliberate and safe:
  // expansions ride the low-weight `expandedTerms` channel and are matched
  // against the live index, so a dead word simply matches nothing. Retiring
  // words would mean reference counting every document, which is exactly the
  // O(library) work this avoids.
  addTexts(texts: Iterable<string>): void {
    const counts = new Map<string, number>();
    for (const text of texts) tokenizeVocab(text, counts);
    for (const [term, freq] of counts) {
      const prev = this.freq.get(term);
      this.freq.set(term, (prev ?? 0) + freq);
      if (prev !== undefined) continue;
      // New word: keep `sorted` ordered for the prefix binary search, and give
      // the BK-tree a node to correct toward.
      let lo = 0;
      let hi = this.sorted.length;
      while (lo < hi) {
        const mid = (lo + hi) >> 1;
        if (this.sorted[mid] < term) lo = mid + 1;
        else hi = mid;
      }
      this.sorted.splice(lo, 0, term);
      this.insert(term);
    }
  }

  private insert(term: string): void {
    if (!this.root) {
      this.root = { term, children: new Map() };
      return;
    }
    let node = this.root;
    for (;;) {
      const d = boundedEditDistance(term, node.term, term.length + node.term.length);
      if (d === 0) return; // already present
      const child = node.children.get(d);
      if (!child) {
        node.children.set(d, { term, children: new Map() });
        return;
      }
      node = child;
    }
  }

  // All vocab terms within `maxDist` edits of `term`, via BK-tree pruning: a
  // child edge labelled `d` can only hold matches in [d - maxDist, d + maxDist]
  // by the triangle inequality, so whole subtrees are skipped.
  search(term: string, maxDist: number): { term: string; dist: number; freq: number }[] {
    const out: { term: string; dist: number; freq: number }[] = [];
    if (!this.root) return out;
    const stack: BkNode[] = [this.root];
    while (stack.length) {
      const node = stack.pop()!;
      const d = boundedEditDistance(term, node.term, term.length + node.term.length);
      if (d <= maxDist) out.push({ term: node.term, dist: d, freq: this.freq.get(node.term) ?? 0 });
      const lo = d - maxDist;
      const hi = d + maxDist;
      for (const [dist, child] of node.children) {
        if (dist >= lo && dist <= hi) stack.push(child);
      }
    }
    return out;
  }

  // Best corrections for a term the user likely mistyped, closest-then-commonest.
  // Returns [] for a term that already exists in the vocab (a real word is never
  // "corrected") or is too short to correct safely.
  corrections(term: string, limit = 2): string[] {
    if (this.freq.has(term)) return [];
    const budget = typoBudget(term.length);
    if (budget === 0) return [];
    return this.search(term, budget)
      .filter((h) => h.term !== term)
      .sort((a, b) => a.dist - b.dist || b.freq - a.freq || (a.term < b.term ? -1 : 1))
      .slice(0, limit)
      .map((h) => h.term);
  }

  // Vocab words that begin with `prefix` (excluding an exact hit), commonest
  // first — the search-as-you-type completions for the token being typed.
  prefixMatches(prefix: string, limit = 5): string[] {
    if (prefix.length < 2) return [];
    let lo = 0;
    let hi = this.sorted.length;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (this.sorted[mid] < prefix) lo = mid + 1;
      else hi = mid;
    }
    const matches: string[] = [];
    for (let i = lo; i < this.sorted.length; i++) {
      const t = this.sorted[i];
      if (!t.startsWith(prefix)) break;
      if (t !== prefix) matches.push(t);
    }
    return matches
      .sort((a, b) => (this.freq.get(b) ?? 0) - (this.freq.get(a) ?? 0) || (a < b ? -1 : 1))
      .slice(0, limit);
  }

  // Turn the query's terms into extra low-weight terms: corrections for anything
  // that isn't a real word, plus prefix completions for the final token when the
  // user is mid-word (search-as-you-type). Never returns a term already in the
  // query, and never the input terms themselves.
  expand(terms: readonly string[], opts: { lastIsPrefix?: boolean } = {}): string[] {
    const seen = new Set(terms);
    const out: string[] = [];
    const add = (t: string): void => {
      if (!seen.has(t)) {
        seen.add(t);
        out.push(t);
      }
    };
    for (let i = 0; i < terms.length; i++) {
      const term = terms[i];
      for (const c of this.corrections(term)) add(c);
      if (opts.lastIsPrefix && i === terms.length - 1) {
        for (const p of this.prefixMatches(term)) add(p);
      }
    }
    return out;
  }
}
