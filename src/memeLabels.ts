// Curated "knowledge" layer: the contextual information a generic multimodal
// model often can't name on its own (meme formats, recurring characters,
// internet-native archetypes). Each entry is phrased as a natural-language
// CLIP prompt; at runtime we embed these with the CLIP text encoder once and
// score every image against them (zero-shot classification). Edit freely —
// this list IS the curation surface of the app.

export interface LabelDef {
  label: string; // human-facing name shown as a tag
  prompt: string; // CLIP text prompt used for matching
  category: 'format' | 'character' | 'emotion' | 'topic';
}

export const MEME_LABELS: LabelDef[] = [
  // --- Recurring characters / archetypes ---
  { label: 'Pepe the Frog', prompt: 'a Pepe the Frog meme, a green cartoon frog', category: 'character' },
  { label: 'Sad Pepe', prompt: 'a sad crying Pepe the Frog feels bad man meme', category: 'character' },
  { label: 'Smug Pepe', prompt: 'a smug self-satisfied Pepe the Frog meme', category: 'character' },
  { label: 'Wojak', prompt: 'a Wojak feels guy meme, a simple bald MS Paint face', category: 'character' },
  { label: 'Doomer', prompt: 'a Doomer Wojak meme, a black beanie hooded depressed guy', category: 'character' },
  { label: 'Soyjak', prompt: 'a Soyjak meme, an open-mouthed soy face with glasses', category: 'character' },
  { label: 'NPC Wojak', prompt: 'a grey NPC Wojak meme face', category: 'character' },
  { label: 'Gigachad', prompt: 'a Gigachad meme, a jawline chiseled black and white man', category: 'character' },
  { label: 'Chad', prompt: 'a Chad yes meme, a confident strong man', category: 'character' },
  { label: 'Virgin vs Chad', prompt: 'the virgin vs the chad comparison meme', category: 'format' },
  { label: 'Brainlet', prompt: 'a brainlet wojak meme, a tiny pointed-head dumb face', category: 'character' },
  { label: 'Trollface', prompt: 'a Trollface problem meme, a grinning troll face', category: 'character' },
  { label: 'Doge', prompt: 'a Doge meme, a Shiba Inu dog with comic sans text', category: 'character' },
  { label: 'Cheems', prompt: 'a Cheems Shiba Inu dog meme', category: 'character' },
  { label: 'Crying Cat', prompt: 'a crying cat with a sad smiling face meme', category: 'character' },
  { label: 'Pop Cat', prompt: 'a Pop Cat open mouth meme', category: 'character' },

  // --- Classic image-macro formats ---
  { label: 'Drake format', prompt: 'the Drake hotline bling reaction meme, reject the top prefer the bottom', category: 'format' },
  { label: 'Distracted Boyfriend', prompt: 'the distracted boyfriend stock photo meme', category: 'format' },
  { label: 'Two Buttons', prompt: 'the two buttons sweating choice meme', category: 'format' },
  { label: 'Expanding Brain', prompt: 'the expanding glowing brain galaxy meme', category: 'format' },
  { label: 'Surprised Pikachu', prompt: 'the surprised Pikachu shocked face meme', category: 'format' },
  { label: 'This Is Fine', prompt: 'the this is fine dog sitting in a burning room meme', category: 'format' },
  { label: 'Mocking SpongeBob', prompt: 'the mocking spongebob alternating caps meme', category: 'format' },
  { label: 'Stonks', prompt: 'the stonks meme man pointing at a rising stock chart', category: 'format' },
  { label: 'Change My Mind', prompt: 'the change my mind sign at a table meme', category: 'format' },
  { label: 'Is This a Pigeon', prompt: 'the is this a pigeon butterfly anime meme', category: 'format' },
  { label: 'Galaxy Brain', prompt: 'a galaxy brain enlightenment meme', category: 'format' },
  { label: 'Bell Curve / IQ', prompt: 'the IQ bell curve midwit meme', category: 'format' },
  { label: 'Demotivational', prompt: 'a black bordered demotivational poster meme', category: 'format' },
  { label: 'Deep Fried', prompt: 'an over-saturated deep fried meme', category: 'format' },
  { label: 'Reaction Image', prompt: 'a reaction image meme of a facial expression', category: 'format' },
  { label: 'Greentext', prompt: 'a 4chan greentext story screenshot', category: 'format' },
  { label: 'Tweet Screenshot', prompt: 'a screenshot of a tweet or social media post', category: 'format' },
  { label: 'Rage Comic', prompt: 'an old rage comic with MS Paint faces', category: 'format' },

  // --- Emotions / vibes ---
  { label: 'Wholesome', prompt: 'a wholesome heartwarming positive meme', category: 'emotion' },
  { label: 'Cursed', prompt: 'a cursed unsettling disturbing image meme', category: 'emotion' },
  { label: 'Angry', prompt: 'an angry rage meme', category: 'emotion' },
  { label: 'Crying / Sad', prompt: 'a sad crying meme', category: 'emotion' },
  { label: 'Confused', prompt: 'a confused math lady calculating meme', category: 'emotion' },
  { label: 'Smug', prompt: 'a smug self-satisfied meme', category: 'emotion' },
  { label: 'Laughing', prompt: 'a laughing dying of laughter meme', category: 'emotion' },
  { label: 'Awkward', prompt: 'an awkward cringe meme', category: 'emotion' },

  // --- Topics ---
  { label: 'Programming', prompt: 'a programming or software developer meme with code', category: 'topic' },
  { label: 'Gaming', prompt: 'a video game gamer meme', category: 'topic' },
  { label: 'Crypto / Stocks', prompt: 'a cryptocurrency or stock market trading meme', category: 'topic' },
  { label: 'Anime', prompt: 'an anime style meme', category: 'topic' },
  { label: 'Cat', prompt: 'a funny cat meme', category: 'topic' },
  { label: 'Dog', prompt: 'a funny dog meme', category: 'topic' },
  { label: 'Politics', prompt: 'a political meme', category: 'topic' },
  { label: 'Relatable / Mood', prompt: 'a relatable everyday mood meme', category: 'topic' },
];

// Generic "not a recognizable format" anchors so weak matches don't get forced
// into a label. We discard any predicted label that scores below the top
// generic anchor (acts as a per-image dynamic threshold).
export const NEGATIVE_ANCHORS: string[] = [
  'a random photograph',
  'a plain screenshot',
  'an ordinary picture',
];
