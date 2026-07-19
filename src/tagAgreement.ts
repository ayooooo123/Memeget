// Tag-agreement eval — grades the model against the user's OWN assertions.
//
// Every manual tag and taught exemplar is a labeled example: the user asserted
// "this meme IS X." So on any meme carrying a user-truth tag (source 'manual'
// or 'exemplar'), check whether the MODEL's own description — its vision tags
// plus its caption — would make that meme findable by X (lexical `.includes`,
// mirroring real search). Agreement = the model sees what the user sees;
// a miss is a concrete, named recognition gap. Zero labeling effort: the user
// already did the work by using the app.
//
// Honest scope: user truth skews toward IDENTITY labels (characters, people,
// formats — what teaching is for), so this grades the model's *recognition*.
// The hand-labeled tagging-cases grade *recall-by-meaning* (situations,
// reactions). Complements, not substitutes.

export interface AgreementTag {
  label: string;
  source?: string; // 'manual' | 'exemplar' | 'vision' | 'prompt' | 'ocr' | undefined
}

export interface AgreementMeme {
  id: string;
  caption?: string;
  tags: AgreementTag[];
}

const USER_SOURCES = new Set(['manual', 'exemplar']);

export interface LabelAgreement {
  label: string;
  asserted: number; // memes the user put this label on
  agreed: number; // of those, memes where the model's description surfaces it
  missedIds: string[];
}

export interface AgreementScore {
  memesWithTruth: number; // memes carrying at least one user-truth label
  undescribed: number; // of those, memes with NO model output at all (skipped)
  assertions: number; // (meme, user label) pairs actually graded
  agreed: number;
  agreementRate: number;
  labels: LabelAgreement[]; // per-label breakdown, worst agreement first
}

const norm = (s: string) => s.toLowerCase().replace(/\s+/g, ' ').trim();

export function scoreAgreement(memes: AgreementMeme[]): AgreementScore {
  const perLabel = new Map<string, LabelAgreement>();
  let memesWithTruth = 0;
  let undescribed = 0;
  let assertions = 0;
  let agreed = 0;

  for (const m of memes) {
    const truth = [...new Set(m.tags.filter((t) => USER_SOURCES.has(t.source ?? '')).map((t) => norm(t.label)))]
      .filter(Boolean);
    if (truth.length === 0) continue;
    memesWithTruth++;

    // The model's own text: what the VLM tagged + captioned. User tags, CLIP
    // zero-shot guesses, and OCR are excluded — we're grading the description.
    const visionTags = m.tags.filter((t) => t.source === 'vision').map((t) => t.label);
    const hay = norm([...visionTags, m.caption ?? ''].join(' '));
    if (!hay) {
      // The model never described this meme — a coverage gap, not a wrong answer.
      undescribed++;
      continue;
    }

    for (const label of truth) {
      const rec = perLabel.get(label) ?? { label, asserted: 0, agreed: 0, missedIds: [] };
      rec.asserted++;
      assertions++;
      if (hay.includes(label)) {
        rec.agreed++;
        agreed++;
      } else {
        rec.missedIds.push(m.id);
      }
      perLabel.set(label, rec);
    }
  }

  const labels = [...perLabel.values()].sort(
    (a, b) => a.agreed / a.asserted - b.agreed / b.asserted || b.asserted - a.asserted
  );
  return {
    memesWithTruth,
    undescribed,
    assertions,
    agreed,
    agreementRate: assertions ? agreed / assertions : 0,
    labels,
  };
}

export function formatAgreement(s: AgreementScore): string {
  const pct = (x: number) => `${(x * 100).toFixed(0)}%`;
  const lines = [
    `memes with your labels: ${s.memesWithTruth}   (${s.undescribed} undescribed → skipped)`,
    `assertions graded:      ${s.assertions}`,
    `model agrees:           ${pct(s.agreementRate)}   (your label appears in its own description)`,
  ];
  const misses = s.labels.filter((l) => l.agreed < l.asserted);
  if (misses.length) {
    lines.push('', 'model misses (your label → how often its description lacked it):');
    for (const l of misses.slice(0, 20)) {
      lines.push(
        `  ${l.label.padEnd(24)} ${l.agreed}/${l.asserted}   missed on ids ${l.missedIds.slice(0, 5).join(', ')}`
      );
    }
    if (misses.length > 20) lines.push(`  … and ${misses.length - 20} more labels`);
  }
  return lines.join('\n');
}
