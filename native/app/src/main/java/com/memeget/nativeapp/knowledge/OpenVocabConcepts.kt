package com.memeget.nativeapp.knowledge

import android.content.Context
import com.memeget.nativeapp.inference.NativeBridge
import com.memeget.nativeapp.Tag
import com.memeget.nativeapp.data.FloatBlob
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Open-vocabulary pop-culture & meme concept index (`assets/knowledge/open_vocab_concepts.bin`),
 * combining:
 *   1. `cHybrid`: 512-d fused visual prototype + modality-bridged MobileCLIP-S2 text embedding
 *   2. `tStock`: 512-d stock MobileCLIP-S2 multi-prompt text embedding + corpus anti-hubness `(tagMean, tagStd)`
 *
 * Proposes both zero-shot entity hits (`z_top1`: `zTxt >= 3.30`, `rawTxt >= 0.23`, margin `>= 0.20` over #2)
 * and hybrid concept hits (`hyCos >= 0.50`) in `< 10 ms` per meme without touching human `VoteState.index`.
 */
internal class OpenVocabConcepts private constructor(
    val entries: List<Entry>,
    /** Every entry's `cHybrid` / `tStock`, row-major ([DIM] floats per entry, in [entries] order): full scans read these. */
    private val hybridFlat: FloatArray,
    private val stockFlat: FloatArray,
) {
    private val entryByNormLabel: Map<String, Entry> = HashMap<String, Entry>(entries.size * 2).also { map ->
        for (e in entries) {
            val norm = normalizeConceptKey(e.label)
            if (norm.isNotEmpty()) map.putIfAbsent(norm, e)
        }
    }

    fun entryFor(label: String): Entry? = entryByNormLabel[normalizeConceptKey(label)]

    /** One concept. Its vectors live in the shared matrices; one entry's are copied out on first use. */
    class Entry internal constructor(
        val label: String,
        val category: String,
        val isEntity: Boolean,
        val tagMean: Float,
        val tagStd: Float,
        private val hybridFlat: FloatArray,
        private val stockFlat: FloatArray,
        private val row: Int,
    ) {
        val cHybrid: FloatArray by lazy(LazyThreadSafetyMode.PUBLICATION) { hybridFlat.copyOfRange(row * DIM, (row + 1) * DIM) }
        val tStock: FloatArray by lazy(LazyThreadSafetyMode.PUBLICATION) { stockFlat.copyOfRange(row * DIM, (row + 1) * DIM) }
    }

    /** One image's dot product with every entry's `cHybrid` ([hybrid]) and `tStock` ([stock]), in [entries] order. */
    class ImageDots internal constructor(val hybrid: FloatArray, val stock: FloatArray)

    /** [ImageDots] for [image]: two native matrix scans. Computed once per image and handed to every lookup on it. */
    fun imageDots(image: FloatArray): ImageDots {
        val n = entries.size
        val hybrid = FloatArray(n)
        val stock = FloatArray(n)
        if (image.size == DIM && n > 0) {
            matVec(hybridFlat, n, image, hybrid)
            matVec(stockFlat, n, image, stock)
        }
        return ImageDots(hybrid, stock)
    }

    data class Hit(
        val label: String,
        val category: String,
        val posCos: Float,
        val voteCount: Int,
        val support70: Int,
        val source: String,
    )

    fun classifyLibraryMeme(
        image: FloatArray,
        ocrAndText: String = "",
        memeId: Long = -1L,
        allowReferenceMatch: Boolean = false,
    ): List<Tag> {
        if (image.size != DIM || FloatBlob.dot(image, image) < 0.5f) return emptyList()
        return retrieveCandidates(image, ocrAndText = ocrAndText, limit = 6)
            .filter { it.source == "z_top1" || it.posCos >= 0.78f }
            .map {
                Tag(
                    label = it.label,
                    category = it.category,
                    score = it.posCos,
                    source = "open_vocab",
                    scoreDomain = "open-vocab",
                    confirmation = "unreviewed",
                    provenance = "open-vocab",
                )
            }
    }

    /** Hands back the wet 92k-meme `cHybrid` visual prototype when Master's 1-3 word query names a known concept. */
    fun conceptPrototype(queryText: String): FloatArray? {
        val norm = normalizeConceptKey(queryText)
        if (norm.isEmpty()) return null
        val tokenCount = norm.count { it == ' ' } + 1
        if (tokenCount > 3) return null
        val direct = entryByNormLabel[norm]
        if (direct != null) return direct.cHybrid
        val stripped = norm.removePrefix("the ").trim()
        return if (stripped != norm && stripped.contains(' ')) entryByNormLabel[stripped]?.cHybrid else null
    }

    /** Spreads a query vector across `tStock` and swallows the closest concept + parent alias words for semantic recall. */
    fun semanticQueryTerms(queryVec: FloatArray, minCos: Float = 0.30f, limit: Int = 4): Set<String> {
        if (queryVec.size != DIM || entries.isEmpty()) return emptySet()
        val topIdx = IntArray(limit) { -1 }
        val topCos = FloatArray(limit) { minCos }
        val stockDots = FloatArray(entries.size).also { matVec(stockFlat, entries.size, queryVec, it) }
        for (i in entries.indices) {
            val c = stockDots[i]
            if (c <= topCos[limit - 1]) continue
            var k = limit - 1
            while (k > 0 && topCos[k - 1] < c) {
                topCos[k] = topCos[k - 1]
                topIdx[k] = topIdx[k - 1]
                k--
            }
            topCos[k] = c
            topIdx[k] = i
        }
        val out = LinkedHashSet<String>()
        val wordRegex = Regex("[a-z0-9]{2,}")
        for (idx in topIdx) {
            if (idx < 0) break
            val lbl = entries[idx].label.lowercase(java.util.Locale.ROOT)
            for (m in wordRegex.findAll(lbl)) out.add(m.value)
            for (alias in parentAliasesFor(lbl)) {
                for (m in wordRegex.findAll(alias)) out.add(m.value)
            }
        }
        return out
    }

    /** Builds a deterministic 256-concept Dual-Bank (alternating tStock and cHybrid) for CSLS candidate hubness calibration. */
    fun buildCslsQueryBank(bankSize: Int = 256): FloatArray {
        if (entries.isEmpty() || bankSize <= 0) return FloatArray(0)
        val actual = minOf(bankSize, entries.size)
        val out = FloatArray(actual * DIM)
        val step = entries.size.toDouble() / actual.toDouble()
        for (b in 0 until actual) {
            val idx = minOf(entries.lastIndex, (b * step).toInt())
            System.arraycopy(if ((b and 1) == 0) stockFlat else hybridFlat, idx * DIM, out, b * DIM, DIM)
        }
        return out
    }

    /** [dots]: this image's [imageDots] when the caller already has them; computed here otherwise. */
    fun retrieveCandidates(image: FloatArray, ocrAndText: String = "", limit: Int = 6, dots: ImageDots? = null): List<Hit> {
        if (image.size != DIM || entries.isEmpty() || FloatBlob.dot(image, image) < 0.5f) return emptyList()
        val n = entries.size
        val d = dots ?: imageDots(image)
        val hyCos = FloatArray(n)
        val normOcr = if (ocrAndText.isNotBlank()) {
            " " + ocrAndText.lowercase(java.util.Locale.ROOT).replace(NON_ALNUM, " ").trim() + " "
        } else ""
        // The text's words, split once: a concept name can only be in the text if its first word is.
        val ocrWords = if (normOcr.isNotEmpty()) normOcr.split(' ').filterTo(HashSet()) { it.isNotEmpty() } else null
        var bestEnt1 = -1
        var bestZ1 = Float.NEGATIVE_INFINITY
        var bestRaw1 = 0f
        var bestZ2 = Float.NEGATIVE_INFINITY

        for (i in 0 until n) {
            val e = entries[i]
            var hc = d.hybrid[i]
            if (normOcr.isNotEmpty() && matchesOcrConcept(e.label, e.isEntity, normOcr, ocrWords)) {
                hc = max(hc, 0.72f)
            }
            hyCos[i] = hc
            if (e.isEntity) {
                val raw = d.stock[i]
                val z = (raw - e.tagMean) / max(e.tagStd, 0.025f)
                if (z > bestZ1) {
                    bestZ2 = bestZ1
                    bestZ1 = z
                    bestRaw1 = raw
                    bestEnt1 = i
                } else if (z > bestZ2) {
                    bestZ2 = z
                }
            }
        }

        val merged = LinkedHashMap<String, Hit>()
        val zCorroborated = bestEnt1 >= 0 &&
            ((bestZ1 >= 3.80f && bestRaw1 >= 0.25f && (bestZ1 - bestZ2) >= 0.25f && hyCos[bestEnt1] >= 0.46f) ||
                (bestZ1 >= 4.60f && bestRaw1 >= 0.29f && (bestZ1 - bestZ2) >= 0.45f))
        if (zCorroborated) {
            val e = entries[bestEnt1]
            val eqCos = (0.62f + 0.08f * bestZ1).coerceIn(0.85f, 0.96f)
            val bestCos = max(eqCos, hyCos[bestEnt1])
            merged[e.label] = Hit(
                label = e.label,
                category = e.category,
                posCos = bestCos,
                voteCount = 3,
                support70 = 3,
                source = "z_top1",
            )
        }

        // Find top 10 by hyCos >= 0.50f
        val topIdx = IntArray(10) { -1 }
        val topVal = FloatArray(10) { 0.50f }
        for (i in 0 until n) {
            val c = hyCos[i]
            if (c <= topVal[9]) continue
            var k = 9
            while (k > 0 && topVal[k - 1] < c) {
                topVal[k] = topVal[k - 1]
                topIdx[k] = topIdx[k - 1]
                k--
            }
            topVal[k] = c
            topIdx[k] = i
        }

        for (k in 0 until 10) {
            val idx = topIdx[k]
            if (idx < 0) break
            val e = entries[idx]
            val cVal = topVal[k]
            val calCos = (0.20f + 0.95f * cVal).coerceIn(0f, 0.95f)
            val prev = merged[e.label]
            if (prev != null) {
                if (calCos > prev.posCos) {
                    merged[e.label] = prev.copy(posCos = calCos)
                }
            } else {
                merged[e.label] = Hit(
                    label = e.label,
                    category = e.category,
                    posCos = calCos,
                    voteCount = if (cVal >= 0.70f) 2 else if (cVal >= 0.58f) 1 else 0,
                    support70 = if (cVal >= 0.70f) 2 else if (cVal >= 0.60f) 1 else 0,
                    source = "hybrid",
                )
            }
        }
        val impliedToAdd = ArrayList<Hit>()
        for (h in merged.values) {
            if (h.posCos < 0.90f) continue
            val parents = PARENT_IMPLICATIONS[h.label] ?: continue
            for ((pLbl, pCat) in parents) {
                val impCos = (h.posCos - 0.02f).coerceAtLeast(0.76f)
                val prev = merged[pLbl]
                if (prev == null || impCos > prev.posCos) {
                    impliedToAdd += Hit(pLbl, pCat, impCos, max(1, h.voteCount), max(1, h.support70), "implied")
                }
            }
        }
        for (imp in impliedToAdd) {
            val prev = merged[imp.label]
            if (prev == null || imp.posCos > prev.posCos) merged[imp.label] = imp
        }

        val sorted = merged.values.sortedWith(
            compareByDescending<Hit> { it.posCos }
                .thenByDescending { it.support70 }
                .thenByDescending { it.voteCount }
        )
        val out = ArrayList<Hit>(limit)
        val familyCounts = HashMap<String, Int>()
        for (h in sorted) {
            if (out.size >= limit) break
            val fam = rootFamily(h.label)
            val cnt = familyCounts[fam] ?: 0
            if (cnt >= 2) continue
            familyCounts[fam] = cnt + 1
            out += h
        }
        return out
    }

    internal fun matchesOcr(label: String, isEntity: Boolean, ocrAndText: String): Boolean {
        if (ocrAndText.isBlank()) return false
        val normOcr = " " + ocrAndText.lowercase(java.util.Locale.ROOT).replace(NON_ALNUM, " ").trim() + " "
        return matchesOcrConcept(label, isEntity, normOcr)
    }

    /** [ocrWords]: the words of [normOcr], to skip the phrase search for names whose first word isn't one of them. */
    private fun matchesOcrConcept(label: String, isEntity: Boolean, normOcr: String, ocrWords: Set<String>? = null): Boolean {
        if (label == "the lion does not concern himself" && normOcr.contains(" the lion ")) return true
        if (label == "memri tv" && normOcr.contains(" memri ")) return true
        if (label == "they live" && normOcr.contains(" obey ") && normOcr.contains(" consume ")) return true
        if (label == "ghislaine maxwell" && normOcr.contains(" there is no list ")) return true
        if (label == "hikikomori" && (normOcr.contains(" hikikomori ") || normOcr.contains(" hikkikimori "))) return true
        if (!isEntity && !label.contains(' ') && label.length < 6) return false
        if (label.length < 4 || label in GENERIC_OCR_SKIP) return false
        val needle = ocrNeedle(label)
        if (needle.phrase.isEmpty()) return false
        if (ocrWords != null && needle.firstWord !in ocrWords) return false
        return normOcr.contains(needle.phrase)
    }

    /** A concept name cleaned for an OCR phrase match: " cleaned label ", and its first word. */
    private class OcrNeedle(val phrase: String, val firstWord: String)

    /**
     * The [OcrNeedle] for [label]; an empty phrase when the cleaned label is too short to match. Cached per label:
     * [retrieveCandidates] checks all ~6,000 concepts against each meme's text, and re-cleaning every label (with a
     * Regex compiled per label) on every call took its two calls per re-tagged meme from ~17 ms to ~141 ms on a Pixel 9 Pro.
     */
    private fun ocrNeedle(label: String): OcrNeedle = OCR_NEEDLES.getOrPut(label) {
        val clean = label.lowercase(java.util.Locale.ROOT).replace(NON_ALNUM, " ").trim()
        if (clean.length < 4) OcrNeedle("", "") else OcrNeedle(" $clean ", clean.substringBefore(' '))
    }

    companion object {
        private const val DIM = 512
        private const val MAGIC = 0x4D474F56 // 'MGOV'
        private const val VERSION = 1

        @Volatile private var cached: OpenVocabConcepts? = null
        private val NON_ALNUM = Regex("[^a-z0-9]+")
        private val OCR_NEEDLES = java.util.concurrent.ConcurrentHashMap<String, OcrNeedle>()
        private val GENERIC_OCR_SKIP = setOf(
            "meme", "text", "like", "just", "with", "that", "this", "from", "your", "when", "what", "have",
            "will", "good", "time", "look", "make", "know", "people", "think", "going", "want", "really",
            "white", "black", "green", "water", "first", "other", "there", "where", "after", "about", "under",
            "world", "woman", "women", "young", "house", "state", "right", "night", "money", "point", "power",
        )
        internal fun rootFamily(label: String): String {
            val l = label.lowercase(java.util.Locale.ROOT)
            return when {
                "pepe" in l || l == "apu" || "apustaja" in l || "groyper" in l -> "pepe"
                "wojak" in l || "soyjak" in l || "soyjack" in l || l == "doomer" || l == "brainlet" -> "wojak"
                "bogdanoff" in l -> "bogdanoff"
                "spongebob" in l -> "spongebob"
                "soprano" in l -> "sopranos"
                "trump" in l -> "trump"
                "hasbulla" in l -> "hasbulla"
                "partyhat" in l || l == "party hat" || l == "phat" -> "partyhat"
                else -> l
            }
        }

        internal fun normalizeConceptKey(raw: String): String =
            raw.lowercase(java.util.Locale.ROOT).replace(NON_ALNUM, " ").trim()

        internal fun parentAliasesFor(label: String): List<String> {
            val norm = label.trim().lowercase(java.util.Locale.ROOT)
            val out = LinkedHashSet<String>()
            PARENT_IMPLICATIONS[norm]?.forEach { (pLbl, _) -> out.add(pLbl) }
            when {
                norm == "apu apustaja" || norm == "apustaja" -> { out.add("apu"); out.add("pepe") }
                norm == "bobo the bear" -> { out.add("bobo"); out.add("bear") }
                "pepe" in norm && norm != "pepe" -> out.add("pepe")
                ("wojak" in norm || "soyjak" in norm || norm == "doomer" || norm == "brainlet") && norm != "wojak" -> out.add("wojak")
                norm == "spongebob squarepants" -> out.add("spongebob")
            }
            return out.toList()
        }

        internal val PARENT_IMPLICATIONS: Map<String, List<Pair<String, String>>> = mapOf(
            "tony soprano" to listOf("sopranos" to "tv", "james gandolfini" to "person"),
            "james gandolfini" to listOf("sopranos" to "tv", "tony soprano" to "character"),
            "livia soprano" to listOf("sopranos" to "tv", "nancy marchand" to "person"),
            "nancy marchand" to listOf("sopranos" to "tv", "livia soprano" to "character"),
            "uncle junior" to listOf("sopranos" to "tv", "dominic chianese" to "person"),
            "dominic chianese" to listOf("sopranos" to "tv", "uncle junior" to "character"),
            "corrado soprano" to listOf("sopranos" to "tv", "uncle junior" to "character"),
            "christopher moltisanti" to listOf("sopranos" to "tv", "michael imperioli" to "person"),
            "michael imperioli" to listOf("sopranos" to "tv", "christopher moltisanti" to "character"),
            "paulie walnuts" to listOf("sopranos" to "tv"),
            "artie bucco" to listOf("sopranos" to "tv"),
            "john ventimiglia" to listOf("sopranos" to "tv", "artie bucco" to "character"),
            "adriana la cerva" to listOf("sopranos" to "tv"),
            "drea de matteo" to listOf("sopranos" to "tv", "adriana la cerva" to "character"),
            "the sopranos" to listOf("sopranos" to "tv"),
            "patrick bateman" to listOf("american psycho" to "movie", "christian bale" to "person"),
            "christian bale" to listOf("american psycho" to "movie", "patrick bateman" to "person"),
            "david van patten" to listOf("american psycho" to "movie", "patrick bateman" to "person"),
            "bill sage" to listOf("american psycho" to "movie"),
            "paul allen scene" to listOf("american psycho" to "movie", "patrick bateman" to "person", "christian bale" to "person"),
            "business card scene" to listOf("american psycho" to "movie", "patrick bateman" to "person"),
            "peter griffin" to listOf("family guy" to "tv"),
            "meg griffin" to listOf("family guy" to "tv"),
            "lois griffin" to listOf("family guy" to "tv"),
            "stewie griffin" to listOf("family guy" to "tv"),
            "homer simpson" to listOf("the simpsons" to "tv", "simpsons" to "tv"),
            "bart simpson" to listOf("the simpsons" to "tv", "simpsons" to "tv"),
            "eric cartman" to listOf("south park" to "tv"),
            "randy marsh" to listOf("south park" to "tv"),
            "butters stotch" to listOf("south park" to "tv"),
            "kenny mccormick" to listOf("south park" to "tv"),
            "stan marsh" to listOf("south park" to "tv"),
            "kyle broflovski" to listOf("south park" to "tv"),
            "walter white" to listOf("breaking bad" to "tv", "bryan cranston" to "person"),
            "bryan cranston" to listOf("breaking bad" to "tv", "walter white" to "character"),
            "jesse pinkman" to listOf("breaking bad" to "tv", "aaron paul" to "person"),
            "aaron paul" to listOf("breaking bad" to "tv", "jesse pinkman" to "character"),
            "saul goodman" to listOf("better call saul" to "tv", "breaking bad" to "tv", "bob odenkirk" to "person"),
            "bob odenkirk" to listOf("better call saul" to "tv", "breaking bad" to "tv", "saul goodman" to "character"),
            "gustavo fring" to listOf("breaking bad" to "tv", "giancarlo esposito" to "person"),
            "giancarlo esposito" to listOf("breaking bad" to "tv", "gustavo fring" to "character"),
            "ed tom bell" to listOf("no country for old men" to "movie", "tommy lee jones" to "person"),
            "tommy lee jones" to listOf("no country for old men" to "movie", "ed tom bell" to "character"),
            "anton chigurh" to listOf("no country for old men" to "movie", "javier bardem" to "person"),
            "javier bardem" to listOf("no country for old men" to "movie", "anton chigurh" to "character"),
            "michael scott" to listOf("the office" to "tv", "steve carell" to "person"),
            "steve carell" to listOf("the office" to "tv", "michael scott" to "character"),
            "hans landa" to listOf("inglourious basterds" to "movie", "christoph waltz" to "person"),
            "christoph waltz" to listOf("inglourious basterds" to "movie", "hans landa" to "character"),
            "marla singer" to listOf("fight club" to "movie", "helena bonham carter" to "person"),
            "helena bonham carter" to listOf("fight club" to "movie", "marla singer" to "character"),
            "edward norton" to listOf("fight club" to "movie", "the narrator" to "character"),
            "the narrator" to listOf("fight club" to "movie", "edward norton" to "person"),
            "where is my mind" to listOf("fight club" to "movie"),
            "lain iwakura" to listOf("serial experiments lain" to "tv"),
            "jc denton" to listOf("deus ex" to "format"),
            "blue partyhat" to listOf("runescape" to "format"),
            "phat" to listOf("runescape" to "format", "blue partyhat" to "format"),
            "party hat" to listOf("runescape" to "format"),
            "osrs" to listOf("runescape" to "format"),
            "old school runescape" to listOf("runescape" to "format"),
            "groyper" to listOf("pepe" to "character", "nick fuentes" to "person"),
            "groyper war" to listOf("groyper" to "character", "pepe" to "character", "nick fuentes" to "person"),
            "pepe the frog" to listOf("pepe" to "character"),
            "apu apustaja" to listOf("pepe" to "character"),
            "apustaja" to listOf("pepe" to "character", "apu apustaja" to "character"),
            "sad pepe" to listOf("pepe" to "character"),
            "smug pepe" to listOf("pepe" to "character"),
            "crying wojak" to listOf("wojak" to "character"),
            "female wojak" to listOf("wojak" to "character"),
            "npc wojak" to listOf("wojak" to "character"),
            "soyjak" to listOf("wojak" to "character"),
            "doomer" to listOf("wojak" to "character"),
            "milady maker" to listOf("milady" to "character"),
            "remilia" to listOf("milady" to "character"),
            "ernest khalimov" to listOf("gigachad" to "character"),
            "giorgio tsoukalos" to listOf("ancient aliens" to "tv"),
            "hasbullah magomedov" to listOf("hasbulla" to "person"),
            "donald trump" to listOf("trump" to "person"),
            "trump" to listOf("donald trump" to "person"),
            "spongebob squarepants" to listOf("spongebob" to "tv"),
            "squidward" to listOf("spongebob" to "tv"),
            "squidward tentacles" to listOf("spongebob" to "tv"),
            "terry davis" to listOf("templeos" to "format"),
            "templeos" to listOf("terry davis" to "person"),
            "jack karlson" to listOf("democracy manifest" to "format", "succulent chinese meal" to "format"),
            "whats the charge?" to listOf("jack karlson" to "person", "democracy manifest" to "format"),
            "bloatlord" to listOf("kyriakos grizzly" to "person"),
            "kyriakos kapakoulakidis" to listOf("kyriakos grizzly" to "person", "bloatlord" to "character"),
        )

        fun load(context: Context?): OpenVocabConcepts? {
            cached?.let { return it }
            val app = context ?: return null
            return synchronized(this) {
                cached?.let { return@synchronized it }
                runCatching {
                    app.assets.open("knowledge/open_vocab_concepts.bin").use { raw ->
                        loadFromStream(raw).also { cached = it }
                    }
                }.getOrNull()
            }
        }

        fun loadFromStream(raw: java.io.InputStream): OpenVocabConcepts {
            DataInputStream(raw.buffered()).use { input ->
                require(input.readInt() == MAGIC) { "Invalid open_vocab_concepts magic" }
                require(input.readInt() == VERSION) { "Unsupported open_vocab_concepts version" }
                val count = input.readInt()
                require(count in 1..10_000) { "Invalid open_vocab_concepts count: $count" }
                val list = ArrayList<Entry>(count)
                val hybridFlat = FloatArray(count * DIM)
                val stockFlat = FloatArray(count * DIM)
                val vecBuf = ByteArray(DIM * 2)
                for (i in 0 until count) {
                    val lblLen = input.readUnsignedShort()
                    val catLen = input.readUnsignedShort()
                    val isEnt = input.readUnsignedByte() != 0
                    val mean = input.readFloat()
                    val std = input.readFloat()
                    val lblBytes = ByteArray(lblLen).also { input.readFully(it) }
                    val catBytes = ByteArray(catLen).also { input.readFully(it) }
                    input.readFully(vecBuf)
                    decodeFp16Into(vecBuf, hybridFlat, i * DIM)
                    input.readFully(vecBuf)
                    decodeFp16Into(vecBuf, stockFlat, i * DIM)
                    list += Entry(
                        label = String(lblBytes, Charsets.UTF_8),
                        category = String(catBytes, Charsets.UTF_8),
                        isEntity = isEnt,
                        tagMean = mean,
                        tagStd = std,
                        hybridFlat = hybridFlat,
                        stockFlat = stockFlat,
                        row = i,
                    )
                }
                return OpenVocabConcepts(list, hybridFlat, stockFlat)
            }
        }

        private fun decodeFp16Into(bytes: ByteArray, out: FloatArray, offset: Int) {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            for (i in 0 until DIM) {
                out[offset + i] = halfToFloat(bb.short.toInt() and 0xFFFF)
            }
        }

        @Volatile private var nativeMatVecAvailable = true

        /** [out] row r = dot(row r of [matrix], [vec]) for [rows] rows of [DIM]; plain Kotlin without the native library (JVM tests). */
        private fun matVec(matrix: FloatArray, rows: Int, vec: FloatArray, out: FloatArray) {
            if (nativeMatVecAvailable) {
                try {
                    if (NativeBridge.nativeMatVec(matrix, rows, DIM, vec, out)) return
                } catch (_: LinkageError) {
                    nativeMatVecAvailable = false
                }
            }
            for (r in 0 until rows) {
                val off = r * DIM
                var s = 0f
                for (k in 0 until DIM) s += matrix[off + k] * vec[k]
                out[r] = s
            }
        }

        private fun halfToFloat(hbits: Int): Float {
            var mant = hbits and 0x03ff
            var exp = hbits and 0x7c00
            if (exp == 0x7c00) {
                exp = 0x3fc00
            } else if (exp != 0) {
                exp += 0x1c000
                if (mant == 0 && exp > 0x1c400) {
                    return Float.fromBits((hbits and 0x8000) shl 16 or (exp shl 13) or 0x3ff)
                }
            } else if (mant != 0) {
                exp = 0x1c400
                do {
                    mant = mant shl 1
                    exp -= 0x400
                } while ((mant and 0x400) == 0)
                mant = mant and 0x3ff
            }
            return Float.fromBits((hbits and 0x8000) shl 16 or ((exp or mant) shl 13))
        }
    }
}
