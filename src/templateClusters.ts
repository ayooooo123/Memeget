// Emergent-template discovery — "anything can become a template," computed.
//
// There is NO enumerable list of meme templates (docs/composite-meme-
// understanding.md): a template is simply media-form that RECURS in the user's
// own collection. So we discover them: cluster the library by embedding
// similarity, and a cluster of visually-similar memes carrying DIFFERENT
// overlay text is a learned template — the same base media reused to convey
// different ideas. No registry, no model call; just the vectors already stored.
//
// Pure and deterministic: takes precomputed, L2-normalized vectors (the app's
// primary space — every meme has one, and the collection-zip manifest carries
// the same vectors, so this runs identically on-device and against an export).
// Single-linkage via a similarity graph keeps results order-independent, unlike
// leader clustering, and stays fine at personal-library scale (a few thousand).

export interface ClusterItem {
  id: string;
  vector: number[] | Float32Array;
  text?: string; // overlay/OCR text — differing text across members = remix signal
  tags?: string[]; // existing tag labels — used to name the cluster
}

export interface TemplateCluster {
  ids: string[];
  size: number;
  name: string; // best-effort human name (dominant tag) or '' if unnamed
  distinctTexts: number; // distinct normalized overlay texts across members
  isTemplate: boolean; // recurring form + text diversity → a learned template
}

export interface ClusterOptions {
  threshold?: number; // min cosine to link two memes (same-form). Default 0.86:
  // above near-duplicate noise floors, below the 0.99 twin-dedup bar, so true
  // duplicates AND same-template-different-text both link.
  minSize?: number; // members needed before a cluster counts. Default 2 — the
  // second variation you save is the moment a template is born.
  minDistinctTexts?: number; // distinct overlay texts required for isTemplate.
  // Default 2: same image reposted 5× is a dupe pile, not a template.
}

const norm = (s: string | undefined) => (s ?? '').toLowerCase().replace(/\s+/g, ' ').trim();

function dot(a: number[] | Float32Array, b: number[] | Float32Array): number {
  let s = 0;
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) s += a[i] * b[i];
  return s;
}

// Tags too generic to serve as a cluster's name — they'd label half the library.
const GENERIC_NAMES = new Set([
  'meme', 'funny', 'humor', 'reaction', 'reaction image', 'relatable', 'template',
  'meme format', 'image', 'photo', 'screenshot',
]);

function nameCluster(members: ClusterItem[]): string {
  const counts = new Map<string, number>();
  for (const m of members) {
    for (const t of new Set((m.tags ?? []).map(norm))) {
      if (!t || GENERIC_NAMES.has(t)) continue;
      counts.set(t, (counts.get(t) ?? 0) + 1);
    }
  }
  let best = '';
  let bestCount = 1; // require a tag shared by >1 member — a one-off tag isn't the form's name
  for (const [t, c] of counts) {
    if (c > bestCount || (c === bestCount && best && t.length < best.length)) {
      best = t;
      bestCount = c;
    }
  }
  return bestCount > 1 ? best : '';
}

export function clusterTemplates(items: ClusterItem[], opts: ClusterOptions = {}): TemplateCluster[] {
  const threshold = opts.threshold ?? 0.86;
  const minSize = opts.minSize ?? 2;
  const minDistinctTexts = opts.minDistinctTexts ?? 2;

  // Union-find over the ≥threshold similarity graph (single linkage).
  const parent = items.map((_, i) => i);
  const find = (x: number): number => {
    while (parent[x] !== x) {
      parent[x] = parent[parent[x]];
      x = parent[x];
    }
    return x;
  };
  const union = (a: number, b: number) => {
    const ra = find(a);
    const rb = find(b);
    if (ra !== rb) parent[ra] = rb;
  };
  for (let i = 0; i < items.length; i++) {
    for (let j = i + 1; j < items.length; j++) {
      if (dot(items[i].vector, items[j].vector) >= threshold) union(i, j);
    }
  }

  const groups = new Map<number, ClusterItem[]>();
  items.forEach((it, i) => {
    const r = find(i);
    const g = groups.get(r) ?? [];
    g.push(it);
    groups.set(r, g);
  });

  const clusters: TemplateCluster[] = [];
  for (const members of groups.values()) {
    if (members.length < minSize) continue;
    const texts = new Set(members.map((m) => norm(m.text)).filter(Boolean));
    // Blank-text members (no OCR) don't count toward diversity, but a cluster of
    // ALL blanks with ≥minSize members still recurs — treat pure-visual recurrence
    // of 3+ as a template too (image macros with no text exist).
    const distinctTexts = texts.size;
    const isTemplate =
      distinctTexts >= minDistinctTexts || (distinctTexts === 0 && members.length >= minSize + 1);
    clusters.push({
      ids: members.map((m) => m.id),
      size: members.length,
      name: nameCluster(members),
      distinctTexts,
      isTemplate,
    });
  }
  // Big recurring forms first; deterministic tie-break by first id.
  return clusters.sort((a, b) => b.size - a.size || (a.ids[0] < b.ids[0] ? -1 : 1));
}

export function formatClusters(clusters: TemplateCluster[]): string {
  const templates = clusters.filter((c) => c.isTemplate);
  const lines = [
    `clusters:  ${clusters.length}   (recurring groups of visually-linked memes)`,
    `templates: ${templates.length}   (recur with DIFFERENT text — learned formats)`,
  ];
  for (const c of templates.slice(0, 25)) {
    lines.push(
      `  ${(c.name || '(unnamed form)').padEnd(28)} ×${String(c.size).padEnd(4)} ${c.distinctTexts} text variants   e.g. ids ${c.ids.slice(0, 4).join(', ')}`
    );
  }
  if (templates.length > 25) lines.push(`  … and ${templates.length - 25} more`);
  return lines.join('\n');
}
