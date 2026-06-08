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

export interface LabelDef {
  label: string; // human-facing name shown as a tag
  prompt: string; // CLIP text prompt used for matching
  category: 'format' | 'character' | 'emotion' | 'topic';
  associations?: string[]; // related search terms (world knowledge)
}

export const MEME_LABELS: LabelDef[] = [
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
