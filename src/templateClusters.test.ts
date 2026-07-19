// Tests for emergent-template clustering, plus the runner: `npm run templates`
// clusters tools/eval/collection-manifest.json (the manifest.json out of a
// Settings → "Export collection (zip)" export) when present, else a synthetic
// sample — so the moment a real collection lands we can see its learned formats.

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { clusterTemplates, formatClusters, type ClusterItem } from './templateClusters';

// Tiny orthogonal-ish basis for readable fixtures (unit vectors).
const A = [1, 0, 0];
const B = [0, 1, 0];
const near = (v: number[], eps = 0.05) => {
  // Slightly rotate within the plane so cos ≈ 0.9987 — same-form territory.
  const out = [v[0] + eps, v[1] + eps, v[2]];
  const n = Math.hypot(...out);
  return out.map((x) => x / n);
};

describe('clusterTemplates', () => {
  it('links visually-similar memes and flags text-diverse clusters as templates', () => {
    const items: ClusterItem[] = [
      { id: 'a1', vector: A, text: 'me monday', tags: ['this is fine'] },
      { id: 'a2', vector: near(A), text: 'me during finals', tags: ['this is fine', 'dog'] },
      { id: 'b1', vector: B, text: 'unrelated', tags: ['wojak'] },
    ];
    const clusters = clusterTemplates(items);
    expect(clusters).toHaveLength(1); // b1 is a singleton → dropped by minSize
    expect(clusters[0].ids.sort()).toEqual(['a1', 'a2']);
    expect(clusters[0].isTemplate).toBe(true); // two DIFFERENT texts on one form
    expect(clusters[0].name).toBe('this is fine'); // dominant shared tag
  });

  it('does not call a dupe pile (same text) a template', () => {
    const items: ClusterItem[] = [
      { id: 'd1', vector: A, text: 'same joke' },
      { id: 'd2', vector: near(A), text: 'same  JOKE ' }, // same after normalization
    ];
    const [c] = clusterTemplates(items);
    expect(c.size).toBe(2);
    expect(c.distinctTexts).toBe(1);
    expect(c.isTemplate).toBe(false);
  });

  it('treats a 3+ pure-visual recurrence (no text at all) as a template', () => {
    const items: ClusterItem[] = [
      { id: 'v1', vector: A },
      { id: 'v2', vector: near(A) },
      { id: 'v3', vector: near(A, 0.03) },
    ];
    const [c] = clusterTemplates(items);
    expect(c.isTemplate).toBe(true);
  });

  it('is order-independent (single linkage, not leader clustering)', () => {
    const items: ClusterItem[] = [
      { id: 'x', vector: A, text: 't1' },
      { id: 'y', vector: near(A), text: 't2' },
      { id: 'z', vector: B, text: 't3' },
    ];
    const fwd = clusterTemplates(items).map((c) => c.ids.sort().join(','));
    const rev = clusterTemplates([...items].reverse()).map((c) => c.ids.sort().join(','));
    expect(fwd).toEqual(rev);
  });

  it('leaves a cluster unnamed rather than naming it off a single member tag', () => {
    const items: ClusterItem[] = [
      { id: 'n1', vector: A, text: 'one', tags: ['very specific tag'] },
      { id: 'n2', vector: near(A), text: 'two', tags: [] },
    ];
    expect(clusterTemplates(items)[0].name).toBe('');
  });
});

describe('template runner (npm run templates)', () => {
  it('clusters a real collection manifest when present', () => {
    const path = join(process.cwd(), 'tools/eval/collection-manifest.json');
    if (!existsSync(path)) {
      console.log(
        '\n[templates] no tools/eval/collection-manifest.json — export a collection ' +
          'zip from the app (Settings) and drop its manifest.json there to see the ' +
          "library's emergent templates.\n"
      );
      return;
    }
    const manifest = JSON.parse(readFileSync(path, 'utf8')) as {
      memes: { id: string; embedding: number[] | null; ocr: string; tags: { label: string }[] }[];
    };
    const items: ClusterItem[] = manifest.memes
      .filter((m) => m.embedding && m.embedding.length)
      .map((m) => ({
        id: m.id,
        vector: m.embedding!,
        text: m.ocr,
        tags: m.tags.map((t) => t.label),
      }));
    const clusters = clusterTemplates(items);
    console.log(
      `\n--- emergent templates (real collection, ${items.length} memes) ---\n${formatClusters(clusters)}\n`
    );
    expect(items.length).toBeGreaterThan(0);
  });
});
