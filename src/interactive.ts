// Interactive-priority coordination between a live search and the on-device
// model loops that identify memes (VLM describe, Moonshine transcribe, DINO/
// caption backfills). They all share one accelerator, so a text-query embed —
// the one native call a search is waiting on — can end up stuck behind a
// back-to-back train of enrichment generations. That's what made search feel
// dead "while the AI description stuff ran".
//
// A search (or any interactive gesture: opening a meme, "more like this")
// stamps a short window here. An in-flight generation can't be preempted, but
// the loops check this window BETWEEN items and stand down, so the queued embed
// slots into the gap and the results upgrade from lexical-only to full hybrid
// ranking promptly — instead of only after the whole pass drains.
//
// Deliberately dependency-free so every consumer (indexer, audio, UI) can share
// one window without pulling in the heavy native-backed indexer module.

// Covers a search's debounce + embed + result scan, plus a little slack so a
// burst doesn't jump back in the instant a scan finishes and re-starve the next
// keystroke.
export const INTERACTIVE_WINDOW_MS = 8_000;

let lastInteractive = 0;

// Stamp the window: called by a search kicking off and by interactive gestures
// that need the accelerator responsive.
export function noteInteractive(): void {
  lastInteractive = Date.now();
}

// Is the user interacting right now (within the window of the last stamp)?
export function interactiveActive(): boolean {
  return Date.now() - lastInteractive < INTERACTIVE_WINDOW_MS;
}

// A SEPARATE, shorter window stamped only by codec-touching gestures — opening
// the video viewer, copying a clip. The poster backfill grabs the hardware video
// decoder to extract frames; while the user is watching or copying a video that
// same decoder is needed right now. This lets the poster loop briefly stand down
// so the viewer/copy gets the codec, WITHOUT benching poster tiles for the full
// 8s interactive window (posters are exactly what the user watches the grid for).
// Deliberately NOT stamped by scroll or search — only by actions that contend
// for the decoder.
export const CODEC_INTERACTIVE_WINDOW_MS = 2_500;

let lastCodecInteractive = 0;

export function noteCodecInteractive(): void {
  lastCodecInteractive = Date.now();
}

export function codecInteractiveActive(): boolean {
  return Date.now() - lastCodecInteractive < CODEC_INTERACTIVE_WINDOW_MS;
}

// How long each yield step naps before re-checking the window.
const YIELD_STEP_MS = 250;

// Pause a background loop between two units of model work while a search is
// active, freeing the accelerator so the search's text embed can run. Bounded
// by INTERACTIVE_WINDOW_MS so a single stale stamp can never wedge a burst
// forever — continuous typing legitimately re-stamps the window and extends the
// pause (search stays prioritized) until the user stops, after which work
// resumes on the next step. Returns immediately if a cancel is requested.
export async function yieldToSearch(shouldCancel?: () => boolean): Promise<void> {
  const start = Date.now();
  while (interactiveActive() && Date.now() - start < INTERACTIVE_WINDOW_MS) {
    if (shouldCancel?.()) return;
    await new Promise<void>((resolve) => setTimeout(resolve, YIELD_STEP_MS));
  }
}
