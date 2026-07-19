// Curated "knowledge" layer: the contextual information a generic multimodal
// model often can't name on its own (meme formats, recurring characters,
// internet-native archetypes). Each entry is phrased as a natural-language
// CLIP prompt; at runtime we embed these with the CLIP text encoder once and
// score every image against them (zero-shot classification).
//
// `associations` is the world-knowledge graph: when an image matches a label,
// those related terms are folded into its searchable text, so a Milady meme
// becomes findable by "remilia", "nft", "ethereum" even when neither the
// picture nor its on-image text says those words. Edit freely — this list +
// the user's taught exemplars ARE the curation surface of the app.

import type { Tag } from './types';
import { buildBaselineLabels } from './baselineLabels';

// Facets a meme is dissected into — so any aspect is findable by a plain-word
// description. The first five are the identity/topic core; the rest capture
// what's happening, what's on screen, where, the moment it fits, and the vibe.
export type LabelCategory =
  | 'format'
  | 'character'
  | 'emotion'
  | 'topic'
  | 'person'
  | 'action'
  | 'object'
  | 'setting'
  | 'situation'
  | 'tone';

export interface LabelDef {
  label: string; // human-facing name shown as a tag
  prompt: string; // CLIP text prompt used for matching
  category: LabelCategory;
  associations?: string[]; // related search terms (world knowledge)
}

// The hand-authored knowledge core. Each prompt is deliberately written by a
// person — that curation is the quality of the app. The harvested memedepot
// baseline (see ./baselineLabels) is appended below for breadth.
export const CURATED_MEME_LABELS: LabelDef[] = [
  // --- Recurring characters / archetypes ---
  { label: 'Pepe the Frog', prompt: 'a Pepe the Frog meme, a green cartoon frog', category: 'character', associations: ['pepe', 'frog', 'feelsgoodman', 'rare pepe', '4chan'] },
  { label: 'Sad Pepe', prompt: 'a sad crying Pepe the Frog feels bad man meme', category: 'character', associations: ['pepe', 'feelsbadman', 'sad', 'crying'] },
  { label: 'Smug Pepe', prompt: 'a smug self-satisfied Pepe the Frog meme', category: 'character', associations: ['pepe', 'smug', 'smug'] },
  { label: 'Wojak', prompt: 'a Wojak feels guy meme, a simple bald MS Paint face', category: 'character', associations: ['wojak', 'feels guy', 'feels'] },
  { label: 'Doomer', prompt: 'a Doomer Wojak meme, a black beanie hooded depressed guy', category: 'character', associations: ['wojak', 'doomer', 'black pill', 'nihilism'] },
  { label: 'Bloomer', prompt: 'a Bloomer optimistic Wojak meme', category: 'character', associations: ['wojak', 'bloomer', 'hopeful', 'optimism'] },
  { label: 'Soyjak', prompt: 'a Soyjak meme, an open-mouthed soy face with glasses', category: 'character', associations: ['soyjak', 'soy', 'soyboy', 'pointing'] },
  { label: 'NPC Wojak', prompt: 'a grey NPC Wojak meme face', category: 'character', associations: ['wojak', 'npc', 'grey', 'normie'] },
  { label: 'Gigachad', prompt: 'a Gigachad meme, a jawline chiseled black and white man', category: 'character', associations: ['chad', 'gigachad', 'sigma', 'based'] },
  { label: 'Chad', prompt: 'a Chad yes meme, a confident strong man', category: 'character', associations: ['chad', 'yes', 'based', 'alpha'] },
  { label: 'Virgin vs Chad', prompt: 'the virgin vs the chad comparison meme', category: 'format', associations: ['virgin', 'chad', 'comparison'] },
  { label: 'Brainlet', prompt: 'a brainlet wojak meme, a tiny pointed-head dumb face', category: 'character', associations: ['wojak', 'brainlet', 'dumb', 'midwit'] },
  { label: 'Trollface', prompt: 'a Trollface problem meme, a grinning troll face', category: 'character', associations: ['trollface', 'troll', 'problem', 'rage comic'] },
  { label: 'Doge', prompt: 'a Doge meme, a Shiba Inu dog with comic sans text', category: 'character', associations: ['doge', 'shiba', 'dogecoin', 'such wow', 'much'] },
  { label: 'Cheems', prompt: 'a Cheems Shiba Inu dog meme', category: 'character', associations: ['cheems', 'doge', 'shiba', 'swole doge'] },
  { label: 'Crying Cat', prompt: 'a crying cat with a sad smiling face meme', category: 'character', associations: ['crying cat', 'sad cat', 'cat'] },
  { label: 'Pop Cat', prompt: 'a Pop Cat open mouth meme', category: 'character', associations: ['popcat', 'cat', 'pop'] },
  { label: 'Milady', prompt: 'a Milady NFT meme, a cute glitchy neochibi anime girl avatar', category: 'character', associations: ['milady', 'remilia', 'neochibi', 'nft', 'ethereum', 'charlotte fang', 'pfp', 'network spirituality'] },
  { label: 'Bored Ape', prompt: 'a Bored Ape Yacht Club NFT cartoon ape avatar', category: 'character', associations: ['bored ape', 'bayc', 'nft', 'ethereum', 'pfp', 'yuga'] },
  { label: 'Nyan Cat', prompt: 'a Nyan Cat pop-tart rainbow cat meme', category: 'character', associations: ['nyan', 'cat', 'rainbow', 'pop tart'] },
  { label: 'Apu Apustaja', prompt: 'an Apu Apustaja meme, a simple childlike Pepe-style frog with big round innocent eyes', category: 'character', associations: ['apu', 'apustaja', 'helper', 'fren', 'pepe', 'sproke'] },
  { label: 'Bobo the Bear', prompt: 'a Bobo the Bear meme, a small blue cartoon bear', category: 'character', associations: ['bobo', 'bear', 'bear market'] },
  { label: 'Groyper', prompt: 'a Groyper meme, a fat smug frog-toad resting its chin on interlocked hands', category: 'character', associations: ['groyper', 'frog', 'toad'] },

  // --- Real people / characters (best-effort zero-shot; teach exemplars for
  //     reliable recognition of specific faces) ---
  { label: 'Donald Trump', prompt: 'a photo of Donald Trump', category: 'person', associations: ['trump', 'maga', 'president', 'politics'] },
  { label: 'Barack Obama', prompt: 'a photo of Barack Obama', category: 'person', associations: ['obama', 'president', 'politics'] },
  { label: 'Joe Biden', prompt: 'a photo of Joe Biden', category: 'person', associations: ['biden', 'president', 'politics'] },
  { label: 'Elon Musk', prompt: 'a photo of Elon Musk', category: 'person', associations: ['elon', 'musk', 'tesla', 'spacex', 'twitter', 'x'] },
  { label: 'Nigel Farage', prompt: 'a photo of Nigel Farage', category: 'person', associations: ['farage', 'brexit', 'uk politics'] },
  { label: 'Tony Soprano', prompt: 'a photo of Tony Soprano from The Sopranos, James Gandolfini', category: 'person', associations: ['sopranos', 'tony soprano', 'gandolfini', 'mafia'] },
  { label: 'Walter White', prompt: 'a photo of Walter White Heisenberg from Breaking Bad', category: 'person', associations: ['breaking bad', 'heisenberg', 'walter white'] },
  { label: 'Patrick Bateman', prompt: 'a photo of Patrick Bateman from American Psycho', category: 'person', associations: ['american psycho', 'patrick bateman', 'sigma'] },
  { label: 'The Joker', prompt: 'a photo of the Joker from a Batman movie', category: 'person', associations: ['joker', 'batman', 'we live in a society'] },

  // --- Classic image-macro formats ---
  { label: 'Drake format', prompt: 'the Drake hotline bling reaction meme, reject the top prefer the bottom', category: 'format', associations: ['drake', 'reject', 'prefer', 'hotline bling'] },
  { label: 'Distracted Boyfriend', prompt: 'the distracted boyfriend stock photo meme', category: 'format', associations: ['distracted boyfriend', 'stock photo', 'jealous girlfriend'] },
  { label: 'Two Buttons', prompt: 'the two buttons sweating choice meme', category: 'format', associations: ['two buttons', 'sweating', 'decision', 'choice'] },
  { label: 'Expanding Brain', prompt: 'the expanding glowing brain galaxy meme', category: 'format', associations: ['expanding brain', 'galaxy brain', 'big brain'] },
  { label: 'Surprised Pikachu', prompt: 'the surprised Pikachu shocked face meme', category: 'format', associations: ['surprised pikachu', 'pokemon', 'shocked', 'pikachu'] },
  { label: 'This Is Fine', prompt: 'the this is fine dog sitting in a burning room meme', category: 'format', associations: ['this is fine', 'dog', 'fire', 'burning room'] },
  { label: 'Mocking SpongeBob', prompt: 'the mocking spongebob alternating caps meme', category: 'format', associations: ['spongebob', 'mocking', 'spongemock'] },
  { label: 'Stonks', prompt: 'the stonks meme man pointing at a rising stock chart', category: 'format', associations: ['stonks', 'stocks', 'meme man', 'profit'] },
  { label: 'Change My Mind', prompt: 'the change my mind sign at a table meme', category: 'format', associations: ['change my mind', 'crowder', 'sign'] },
  { label: 'Is This a Pigeon', prompt: 'the is this a pigeon butterfly anime meme', category: 'format', associations: ['is this a pigeon', 'anime', 'butterfly'] },
  { label: 'Bell Curve / IQ', prompt: 'the IQ bell curve midwit meme', category: 'format', associations: ['midwit', 'bell curve', 'iq', 'normal distribution'] },
  { label: 'Demotivational', prompt: 'a black bordered demotivational poster meme', category: 'format', associations: ['demotivational', 'poster'] },
  { label: 'Deep Fried', prompt: 'an over-saturated deep fried meme', category: 'format', associations: ['deep fried', 'fried', 'crispy'] },
  { label: 'Reaction Image', prompt: 'a reaction image meme of a facial expression', category: 'format', associations: ['reaction', 'reaction image'] },
  { label: 'Greentext', prompt: 'a 4chan greentext story screenshot', category: 'format', associations: ['greentext', '4chan', 'be me', 'anon'] },
  { label: 'Tweet Screenshot', prompt: 'a screenshot of a tweet or social media post', category: 'format', associations: ['tweet', 'twitter', 'x', 'screenshot'] },
  { label: 'Rage Comic', prompt: 'an old rage comic with MS Paint faces', category: 'format', associations: ['rage comic', 'rage face', 'fffuuu'] },

  // --- Emotions / vibes ---
  { label: 'Wholesome', prompt: 'a wholesome heartwarming positive meme', category: 'emotion', associations: ['wholesome', 'heartwarming'] },
  { label: 'Cursed', prompt: 'a cursed unsettling disturbing image meme', category: 'emotion', associations: ['cursed', 'cursed image', 'unsettling'] },
  { label: 'Angry', prompt: 'an angry rage meme', category: 'emotion', associations: ['angry', 'rage', 'mad'] },
  { label: 'Crying / Sad', prompt: 'a sad crying meme', category: 'emotion', associations: ['sad', 'crying', 'depressed'] },
  { label: 'Confused', prompt: 'a confused math lady calculating meme', category: 'emotion', associations: ['confused', 'math lady', 'calculating'] },
  { label: 'Smug', prompt: 'a smug self-satisfied meme', category: 'emotion', associations: ['smug'] },
  { label: 'Laughing', prompt: 'a laughing dying of laughter meme', category: 'emotion', associations: ['laughing', 'lol', 'lmao'] },

  // --- Topics ---
  { label: 'Programming', prompt: 'a programming or software developer meme with code', category: 'topic', associations: ['programming', 'code', 'developer', 'bug', 'stack overflow'] },
  { label: 'Gaming', prompt: 'a video game gamer meme', category: 'topic', associations: ['gaming', 'gamer', 'video game'] },
  { label: 'Crypto / NFT', prompt: 'a cryptocurrency NFT or stock market trading meme', category: 'topic', associations: ['crypto', 'nft', 'bitcoin', 'ethereum', 'wagmi', 'diamond hands', 'hodl', 'web3', 'degen'] },
  { label: 'Anime', prompt: 'an anime style meme', category: 'topic', associations: ['anime', 'manga', 'weeb'] },
  { label: 'Cat', prompt: 'a funny cat meme', category: 'topic', associations: ['cat', 'kitty'] },
  { label: 'Dog', prompt: 'a funny dog meme', category: 'topic', associations: ['dog', 'doggo', 'puppy'] },
  { label: 'Politics', prompt: 'a political meme', category: 'topic', associations: ['politics', 'political'] },
  { label: 'Relatable / Mood', prompt: 'a relatable everyday mood meme', category: 'topic', associations: ['relatable', 'mood', 'me when'] },

  // --- Actions (what is physically happening — a verb you'd search) ---
  { label: 'Pointing', prompt: 'a meme of a person pointing at something', category: 'action', associations: ['pointing', 'points at', 'soyjak pointing', 'look at this'] },
  { label: 'Facepalm', prompt: 'a facepalm meme, hand covering the face in disbelief', category: 'action', associations: ['facepalm', 'disbelief', 'picard', 'smh'] },
  { label: 'Flexing', prompt: 'a meme of someone flexing muscles or showing off', category: 'action', associations: ['flexing', 'flex', 'muscles', 'showing off', 'gigachad'] },
  { label: 'Walking Away', prompt: 'a meme of a person walking or running away', category: 'action', associations: ['walking away', 'running away', 'leaving', 'noped out'] },
  { label: 'Sipping Tea', prompt: 'a meme of someone sipping a drink smugly, kermit sipping tea', category: 'action', associations: ['sipping', 'but thats none of my business', 'kermit', 'tea'] },
  { label: 'Staring', prompt: 'a meme of an intense blank stare at the camera', category: 'action', associations: ['staring', 'blank stare', 'side eye', 'unsettled'] },
  { label: 'Screaming', prompt: 'a meme of a person screaming or yelling', category: 'action', associations: ['screaming', 'yelling', 'shouting', 'panik'] },
  { label: 'Crying', prompt: 'a meme of someone crying with tears', category: 'action', associations: ['crying', 'sobbing', 'tears', 'wojak crying'] },
  { label: 'Pressing a Button', prompt: 'a meme of a hand pressing or choosing a button', category: 'action', associations: ['button', 'pressing', 'two buttons', 'red button'] },
  { label: 'Explosion', prompt: 'a meme with a big explosion or something blowing up', category: 'action', associations: ['explosion', 'exploding', 'boom', 'mind blown'] },

  // --- Objects / props (a thing on screen you'd search for) ---
  { label: 'Fire / Burning', prompt: 'a meme with fire or a room on fire', category: 'object', associations: ['fire', 'burning', 'flames', 'this is fine'] },
  { label: 'Sign / Poster', prompt: 'a meme of a person holding a sign with text', category: 'object', associations: ['sign', 'poster', 'holding a sign', 'change my mind'] },
  { label: 'Brain', prompt: 'a meme showing a brain, glowing or expanding', category: 'object', associations: ['brain', 'expanding brain', 'galaxy brain', 'big brain'] },
  { label: 'Coffee / Tea', prompt: 'a meme featuring a coffee or tea cup', category: 'object', associations: ['coffee', 'tea', 'mug', 'cup'] },
  { label: 'Graph / Chart', prompt: 'a meme with a chart, graph, or arrow going up or down', category: 'object', associations: ['graph', 'chart', 'stonks', 'line goes up', 'bell curve'] },
  { label: 'Gun / Weapon', prompt: 'a meme featuring a gun or weapon pointed', category: 'object', associations: ['gun', 'weapon', 'pointing a gun', 'always has been'] },

  // --- Settings (where the scene takes place) ---
  { label: 'Office / Work', prompt: 'a meme set in an office or workplace', category: 'setting', associations: ['office', 'work', 'meeting', 'cubicle', 'corporate'] },
  { label: 'Gym', prompt: 'a meme set in a gym with weights', category: 'setting', associations: ['gym', 'workout', 'lifting', 'fitness'] },
  { label: 'Classroom / School', prompt: 'a meme set in a school or classroom', category: 'setting', associations: ['school', 'classroom', 'teacher', 'exam', 'homework'] },
  { label: 'Courtroom', prompt: 'a meme set in a courtroom', category: 'setting', associations: ['courtroom', 'court', 'objection', 'lawyer', 'trial'] },
  { label: 'Outer Space', prompt: 'a meme set in outer space with astronauts or planets', category: 'setting', associations: ['space', 'astronaut', 'always has been', 'planet', 'earth'] },

  // --- Situations (the real-life moment you would send it in) ---
  { label: 'It Finally Worked', prompt: 'a triumphant meme about something finally succeeding', category: 'situation', associations: ['it works', 'finally', 'success', 'we did it', 'victory'] },
  { label: 'Avoiding Responsibility', prompt: 'a meme about dodging blame or responsibility', category: 'situation', associations: ['not my problem', 'avoiding', 'dodging', 'none of my business'] },
  { label: 'Procrastinating', prompt: 'a meme about procrastination and putting things off', category: 'situation', associations: ['procrastination', 'later', 'putting off', 'deadline'] },
  { label: 'False Confidence', prompt: 'a meme about pretending everything is fine while it is not', category: 'situation', associations: ['this is fine', 'pretending', 'false confidence', 'coping'] },
  { label: 'Awkward Moment', prompt: 'a meme about an awkward or uncomfortable situation', category: 'situation', associations: ['awkward', 'cringe', 'uncomfortable', 'tension'] },
  { label: 'Regret / Bad Decision', prompt: 'a meme about regret or a bad decision', category: 'situation', associations: ['regret', 'bad decision', 'mistake', 'i made a mistake'] },
  { label: 'Arguing Online', prompt: 'a meme about arguing or debating on the internet', category: 'situation', associations: ['arguing', 'debate', 'internet argument', 'reply guy', 'ratio'] },

  // --- Tone (the humor style / vibe — how it is funny) ---
  { label: 'Ironic', prompt: 'an ironic or sarcastic meme', category: 'tone', associations: ['ironic', 'irony', 'sarcastic'] },
  { label: 'Absurdist / Surreal', prompt: 'a surreal absurdist nonsensical meme', category: 'tone', associations: ['surreal', 'absurd', 'weird', 'nonsense', 'shitpost'] },
  { label: 'Deep Fried', prompt: 'a deep fried meme, oversaturated and distorted', category: 'tone', associations: ['deep fried', 'fried', 'crispy', 'oversaturated'] },
  { label: 'Dark Humor', prompt: 'a dark humor meme', category: 'tone', associations: ['dark humor', 'dark', 'morbid', 'edgy'] },
  { label: 'Wholesome Tone', prompt: 'a wholesome heartwarming positive meme', category: 'tone', associations: ['wholesome', 'heartwarming', 'sweet', 'positive'] },
];

// The active label vocabulary: curated core + the harvested memedepot baseline
// (breadth). The baseline ships empty and is filled by CI, so today this equals
// `CURATED_MEME_LABELS`; a merged harvest just makes it longer. `ASSOCIATIONS`
// below is derived from this, so baseline association terms flow into search too.
export const MEME_LABELS: LabelDef[] = [
  ...CURATED_MEME_LABELS,
  ...buildBaselineLabels(CURATED_MEME_LABELS),
];

// Generic "not a recognizable format" anchors so weak matches don't get forced
// into a label. We discard any predicted label that scores below the top
// generic anchor (acts as a per-image dynamic threshold).
export const NEGATIVE_ANCHORS: string[] = [
  'a random photograph',
  'a plain screenshot',
  'an ordinary picture',
];

// label -> associations lookup, built once.
export const ASSOCIATIONS: Record<string, string[]> = Object.fromEntries(
  MEME_LABELS.filter((l) => l.associations?.length).map((l) => [l.label, l.associations!])
);

// OCR-keyword rules: high-precision tags from text/watermarks the model can't
// read meaning into. A "maker.remilia.org" watermark names a Milady far more
// reliably than any visual classifier. Keep patterns unambiguous to avoid
// false positives (e.g. require "apustaja", not bare "apu").
export interface OcrRule {
  pattern: RegExp;
  label: string;
  category: LabelDef['category'];
}

export const OCR_RULES: OcrRule[] = [
  { pattern: /remilia|milady/i, label: 'Milady', category: 'character' },
  { pattern: /apustaja/i, label: 'Apu Apustaja', category: 'character' },
  { pattern: /pepe(\s|the|$)/i, label: 'Pepe the Frog', category: 'character' },
  { pattern: /\bgroyper\b/i, label: 'Groyper', category: 'character' },
  { pattern: /\bwagmi\b|\bngmi\b|\bhodl\b|diamond hands/i, label: 'Crypto / NFT', category: 'topic' },
];

// Tags inferred from a meme's OCR text. Marked source 'ocr' so they outrank
// shaky visual guesses during merge.
export function ocrTags(text: string): Tag[] {
  if (!text) return [];
  const out: Tag[] = [];
  for (const r of OCR_RULES) {
    if (r.pattern.test(text)) out.push({ label: r.label, category: r.category, score: 1, source: 'ocr' });
  }
  return out;
}
