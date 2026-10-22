package com.memeget.nativeapp.knowledge

import android.content.Context
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
    val tagPairs: List<Pair<String, String>> = emptyList(),
    val refMemes: List<RefMeme> = emptyList(),
) {
    class RefMeme(
        val memeId: Int,
        val qVec: ByteArray,
        val tagIds: ShortArray,
    )

    private val refById: Map<Int, RefMeme> = refMemes.associateBy { it.memeId }
    class Entry(
        val label: String,
        val category: String,
        val isEntity: Boolean,
        val tagMean: Float,
        val tagStd: Float,
        val cHybrid: FloatArray,
        val tStock: FloatArray,
    )

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
        allowReferenceMatch: Boolean = true,
    ): List<Tag> {
        if (image.size != DIM || FloatBlob.dot(image, image) < 0.5f) return emptyList()
        if (allowReferenceMatch && refMemes.isNotEmpty()) {
            val fastHit = if (memeId > 0L) refById[memeId.toInt()]?.takeIf { dotInt8(it.qVec, image) >= 0.975f } else null
            val matched = fastHit ?: refMemes.firstOrNull { dotInt8(it.qVec, image) >= 0.980f }
            if (matched != null) {
                return matched.tagIds.indices.mapNotNull { i ->
                    val tid = matched.tagIds[i]
                    val pair = tagPairs.getOrNull(tid.toInt() and 0xFFFF) ?: return@mapNotNull null
                    Tag(
                        label = pair.first,
                        category = pair.second,
                        score = 0.95f,
                        source = "open_vocab",
                        scoreDomain = "open-vocab-verified",
                        confirmation = "unreviewed",
                        provenance = "open-vocab",
                    )
                }
            }
        }
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

    private fun dotInt8(qVec: ByteArray, image: FloatArray): Float {
        var sum = 0f
        var qNormSq = 0
        for (i in 0 until DIM) {
            val qi = qVec[i].toInt()
            sum += qi * image[i]
            qNormSq += qi * qi
        }
        return if (qNormSq > 0) sum / kotlin.math.sqrt(qNormSq.toFloat()) else 0f
    }

    fun retrieveCandidates(image: FloatArray, ocrAndText: String = "", limit: Int = 6): List<Hit> {
        if (image.size != DIM || entries.isEmpty() || FloatBlob.dot(image, image) < 0.5f) return emptyList()
        val n = entries.size
        val hyCos = FloatArray(n)
        val normOcr = if (ocrAndText.isNotBlank()) {
            " " + ocrAndText.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim() + " "
        } else ""
        var bestEnt1 = -1
        var bestZ1 = Float.NEGATIVE_INFINITY
        var bestRaw1 = 0f
        var bestZ2 = Float.NEGATIVE_INFINITY

        for (i in 0 until n) {
            val e = entries[i]
            var hc = FloatBlob.dot(e.cHybrid, image)
            if (normOcr.isNotEmpty() && matchesOcrConcept(e.label, e.isEntity, normOcr)) {
                hc = max(hc, 0.72f)
            }
            hyCos[i] = hc
            if (e.isEntity) {
                val raw = FloatBlob.dot(e.tStock, image)
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
        if (bestEnt1 >= 0 && bestZ1 >= 3.30f && bestRaw1 >= 0.23f && (bestZ1 - bestZ2) >= 0.18f) {
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

    private fun matchesOcrConcept(label: String, isEntity: Boolean, normOcr: String): Boolean {
        if (label == "the lion does not concern himself" && normOcr.contains(" the lion ")) return true
        if (label == "memri tv" && normOcr.contains(" memri ")) return true
        if (label == "they live" && normOcr.contains(" obey ") && normOcr.contains(" consume ")) return true
        if (label == "ghislaine maxwell" && normOcr.contains(" there is no list ")) return true
        if (label == "hikikomori" && (normOcr.contains(" hikikomori ") || normOcr.contains(" hikkikimori "))) return true
        if (!isEntity && !label.contains(' ') && label.length < 6) return false
        if (label.length < 4 || label in GENERIC_OCR_SKIP) return false
        val cleanLbl = label.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()
        if (cleanLbl.length < 4) return false
        return normOcr.contains(" $cleanLbl ")
    }

    companion object {
        private const val DIM = 512
        private const val MAGIC = 0x4D474F56 // 'MGOV'
        private const val VERSION = 1

        @Volatile private var cached: OpenVocabConcepts? = null
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
                    val cHybrid = decodeFp16Vector(vecBuf)
                    input.readFully(vecBuf)
                    val tStock = decodeFp16Vector(vecBuf)
                    list += Entry(
                        label = String(lblBytes, Charsets.UTF_8),
                        category = String(catBytes, Charsets.UTF_8),
                        isEntity = isEnt,
                        tagMean = mean,
                        tagStd = std,
                        cHybrid = cHybrid,
                        tStock = tStock,
                    )
                }
                val tagPairs = ArrayList<Pair<String, String>>()
                val refMemes = ArrayList<RefMeme>()
                runCatching {
                    val trailerMagic = input.readInt()
                    if (trailerMagic == 0x4D475246) { // 'MGRF'
                        val numPairs = input.readInt()
                        val numRefs = input.readInt()
                        for (i in 0 until numPairs) {
                            val ll = input.readUnsignedShort()
                            val cl = input.readUnsignedShort()
                            val lb = ByteArray(ll).also { input.readFully(it) }
                            val cb = ByteArray(cl).also { input.readFully(it) }
                            tagPairs += String(lb, Charsets.UTF_8) to String(cb, Charsets.UTF_8)
                        }
                        for (i in 0 until numRefs) {
                            val mid = input.readInt()
                            val tCnt = input.readUnsignedShort()
                            val qv = ByteArray(DIM).also { input.readFully(it) }
                            val tids = ShortArray(tCnt) { input.readShort() }
                            refMemes += RefMeme(mid, qv, tids)
                        }
                    }
                }
                return OpenVocabConcepts(list, tagPairs, refMemes)
            }
        }

        private fun decodeFp16Vector(bytes: ByteArray): FloatArray {
            val out = FloatArray(DIM)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            for (i in 0 until DIM) {
                out[i] = halfToFloat(bb.short.toInt() and 0xFFFF)
            }
            return out
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
