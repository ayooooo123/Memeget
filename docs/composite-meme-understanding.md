# Understanding composite reference memes

The hardest thing Memeget has to catalog, stated plainly — and a staged plan for
attacking it. This is the spec for the "AI-gen remix" class of meme; other docs
(`on-device-vlm.md`, `memedepot-finetune.md`, `../tools/eval/README.md`) cover
the pieces it leans on.

## The phenomenon

A **composite reference meme** takes a culturally-loaded **base template** and
substitutes a **new domain** onto it — increasingly via AI generation/editing —
where **the joke is the analogy between the two**.

> Example: the 9/11 "second tower" shot with George Bush, edited so it's about AI
> corporate competition (Anthropic, LLMs, incumbents vs. upstarts).

The image is **a pointer to a relationship**, not a scene. It says: *"the
structure of situation A (imminent unseen catastrophe, obliviousness) maps onto
situation B (AI companies)."* Nothing in the pixels states that mapping — it's
inferred from recognizing both halves.

## Why this is a different beast

A plain reaction meme (a man shushing) is **depictive**: describe what's shown,
tag the feeling, done. A composite reference meme breaks every assumption that
makes depictive tagging work:

1. **Two meaning-layers, both searchable.** It will be recalled as "the 9/11 bush
   one" *or* "the anthropic AI-competition one." The index must carry **both
   domains** — base (9/11, Bush, disaster) *and* target (Anthropic, LLMs,
   rivalry) — or half of all future searches miss it.
2. **The base is disguised.** AI alteration means it is *not* pixel-identical to
   the template (face-swaps, logo swaps, style transfer). Perceptual-hash
   template matching fails; recognition must be **semantic** ("a remix *of* the
   9/11 shot," even when altered).
3. **It needs world knowledge the on-device model lacks.** "That's the 9/11
   composition," "that's the Anthropic logo," "those are LLM names" require
   cultural + current-events knowledge a small quantized VLM does not carry.
4. **The meaning is relational and inferred.** "An incumbent about to be
   blindsided by an upstart it isn't watching" is a **conclusion drawn over the
   recognized parts**, not a description of pixels.

**The crux:** the hard part is not perception — it is **recognition +
world-knowledge + analogical reasoning**, the three things a ~2B on-device model
is worst at. Naming that boundary honestly is half the point of this doc.

## The pipeline: five stages, each a defined instruction

Stop asking one VLM pass to do everything. Split into stages, each with a precise
instruction and a clear capability requirement. Stages can run as on-device
passes, a cloud call, or a dev subagent — the *instruction* is the same either
way.

### Stage 1 — Perceive (on-device, buildable now)
> "List every readable text overlay verbatim, every logo/wordmark, every face,
> and every object. Then describe the literal composition in one sentence. Do not
> interpret meaning yet."

Yields the raw material: OCR text (company names, captions), detected
logos/faces/objects, and a plain composition description. This is the app's
current strength (VLM + OCR).

### Stage 2 — Recognize the base template (world knowledge / retrieval)
> "What well-known image, historical event, or meme format is this a remix,
> edit, or parody *of*? Name it even if it has been altered, face-swapped, or
> restyled. If none, say 'original'."

Requires template knowledge robust to alteration. **On-device grounding
(CLIP→VLM) is a weak version of this and fails on disguised templates.** The real
answer is a **template knowledge base** (see Levers) or a cloud model.

### Stage 3 — Resolve references (entity knowledge base)
> "For each name, wordmark, logo, or notable face from Stage 1, identify the
> real-world entity and its domain — e.g. 'Anthropic' → an AI company; 'Claude /
> GPT / Gemini' → large language models; 'George Bush' → US president, 9/11 era."

Turns surface strings into entities + domains. Needs an entity KB; memedepot's
`ai_cultural_context` and the harvested corpus are seed material.

### Stage 4 — Synthesize the analogy (reasoning; cloud tier near-term)
> "In one line, fill: the base situation is ___; it is mapped onto ___ to say
> ___. Then give the emotional beat a viewer feels (e.g. dramatic irony,
> schadenfreude, 'they have no idea what's coming')."

The inference that produces the actual joke. Beyond a small on-device model;
realistically a larger/cloud model, or a fine-tune trained on
(image → cultural_context) pairs.

### Stage 5 — Emit two-layer tags (on-device, buildable now)
> "Tag this meme so it is findable from BOTH domains. Include: the base template
> name; the base-domain entities and event; the target-domain entities and topic;
> the analogy/point in plain words; the emotional beat; and the everyday phrases
> someone would search. Do NOT tag generic appearance."

The searchable output. This is the existing tagging path — it just needs the
richer inputs from Stages 2–4 fed in as grounding.

## Which levers power which stage (and the honest gaps)

| Stage | Capability | Lever we have | Gap |
|---|---|---|---|
| 1 Perceive | describe + OCR | on-device VLM + OCR | — (works) |
| 2 Base template | recognize disguised template | CLIP→VLM grounding | can't handle disguise → **needs template KB / fine-tune** |
| 3 References | entity + domain | harvested corpus, `ai_cultural_context` | no live entity KB → **build one, or cloud** |
| 4 Analogy | relational reasoning | — | **on-device can't; cloud tier or fine-tune** |
| 5 Two-layer tags | searchable output | tagging path + facet coverage eval | works *if* 2–4 feed it |

**The boundary, stated for real:** no prompt tweak makes a ~2B on-device model
reliably do Stages 2–4 on a novel AI-gen remix. Prompt work maximizes Stages 1
and 5 (perceive + tag what it's told). Closing 2–4 is the knowledge-base /
fine-tune / cloud-tier work. Pretending otherwise wastes effort.

## What's buildable now vs. the frontier

- **Now (on-device, testable in the tagging harness):** Stage 1 and Stage 5 as
  concrete VLM prompts. Even without Stages 2–4, splitting perceive-vs-tag and
  demanding two-layer output should lift recall on the *reference* layer the
  model *can* read (OCR'd company names, visible logos).
- **Next (knowledge base):** mine memedepot's `ai_template_match` +
  `ai_cultural_context` into a **template + entity KB**, retrieved at describe
  time to ground Stages 2–3 — the same grounding wire we already ship, fed better
  data.
- **Frontier (reasoning):** a **cloud/large-model tier** for Stage 4, and/or a
  **fine-tune** on (image → template + cultural_context) pairs to bake Stages 2–4
  into the encoder. Gated by the eval so we can prove it helps.

## How we'll know it works

The tagging harness (`tools/eval/README.md`) already gates this. A composite
reference meme becomes a labeled case with **mustFind terms from both domains**:

```jsonc
{ "id": "bush-911-ai",
  "mustFind": ["9/11", "george bush", "second tower",
               "ai competition", "anthropic", "llm", "about to get blindsided"],
  "expectFacets": ["format", "person", "topic", "situation"] }
```

`npm run tagtest` then reports whether the pipeline makes it findable from *both*
sides. That single case is the acceptance test for this whole class — and the
number it produces is how we tell grounding/KB/fine-tune apart from wishful
thinking.
