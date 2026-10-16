package com.memeget.nativeapp.knowledge

import com.memeget.nativeapp.Tag
import com.memeget.nativeapp.data.FloatBlob
import java.text.Normalizer
import java.util.Locale

/**
 * TV show and movie identification: the bundled titles of categories [TV] and [MOVIE]. A meme gets at most one title.
 *
 * People's labels teach titles. A label that gives a meme a title ([Names.givenBy]: the title's name, another of its
 * names, or a character only it has) makes that meme an example of the title. A "not" answer or a rejection counts
 * against a title only when it names the title itself ([Names.nameOf]): "not tony soprano" says nothing about the
 * show. A meme a person gave a title carries that title, and so does an exact copy of one unless a counter-example
 * lies within [VETO_COS].
 *
 * Otherwise the titles compete on their prompts: the best applies when its anchored margin is [MIN_MARGIN] or
 * more and [MIN_GAP] ahead of the next title. For a [learned] title, a meme close to its examples scores higher:
 * [EXAMPLE_WEIGHT] times how far its nearest example lies past cosine [EXAMPLE_FROM], when the prompt alone
 * already gives at least [EXAMPLE_FLOOR] and no counter-example lies nearer. A title is learned once its examples
 * held up on people's own labeled memes, as labels do for the look-alike vote: each labeled meme is judged with
 * its own examples and copies left out, and the title needs [LookalikeVote.RELIABLE_SUPPORT] picks the examples
 * made, [LookalikeVote.RELIABLE_PRECISION] of them right. A counter-example within [VETO_COS] rules a title out,
 * and a title rejected on a meme doesn't compete there.
 */
internal class Titles(
    defs: List<CuratedLabelDef>,
    private val anchors: List<FloatArray>,
    people: List<LookalikeVote.LabeledMeme>,
    teachings: List<Teaching>,
) {
    class Teaching(val vector: FloatArray, val label: String, val positive: Boolean)

    /**
     * [named]: a person gave this meme, or an exact copy of it, the title. [personsTag]: a person's own tag on this
     * meme names the title by its name or another of its names, so that tag stands for the title. [score]: the
     * anchored margin it won with.
     */
    class Hit(val title: CuratedLabelDef, val score: Float, val named: Boolean, val personsTag: Boolean = false) {
        val tag: Tag
            get() = Tag(
                label = title.label.lowercase(Locale.ROOT),
                category = title.category,
                score = if (named) 1f else Recognition.labelConfidence(score),
                source = SOURCE,
                scoreDomain = if (named) "title-named" else "calibrated",
                confirmation = "unreviewed",
                provenance = if (named) "named" else "bundled",
            )
    }

    /**
     * Which of [titles] a label names. [nameOf]: by the title's name or one of its other names ("sopranos",
     * "madmen", "dbz"). [givenBy]: by those or by a character only it has ("tony soprano"), as a person's label
     * gives a meme its title. Case, accents, apostrophes, punctuation and spaces don't matter, nor does a leading
     * "the" before two or more words; before one word it does, so "office" and "mask" stay everyday words.
     */
    class Names(private val titles: List<CuratedLabelDef>) {
        private val byName = HashMap<String, Int>()
        private val byAlias = HashMap<String, Int>()

        init {
            titles.forEachIndexed { j, t ->
                for (name in listOf(t.label) + t.names) for (k in keys(name)) byName.putIfAbsent(k, j)
                for (alias in t.associations) for (k in keys(alias)) byAlias.putIfAbsent(k, j)
            }
        }

        fun nameOf(label: String): Int? = keys(label).firstNotNullOfOrNull { byName[it] }

        fun givenBy(label: String): Int? = nameOf(label) ?: keys(label).firstNotNullOfOrNull { byAlias[it] }

        /** The title [label] names by the title's name or another of its names; a character's name names none. */
        fun titleNamed(label: String): CuratedLabelDef? = nameOf(label)?.let { titles[it] }
    }

    private class Example(val vector: FloatArray, val memeId: Long)
    private class Pick(val title: Int, val score: Float)

    val titles: List<CuratedLabelDef> = defs.filter { isTitleCategory(it.category) && it.vec.size == DIM }
    private val names = Names(titles)
    private val positives = Array(titles.size) { ArrayList<Example>() }
    private val negatives = Array(titles.size) { ArrayList<Example>() }
    private val named = HashMap<Long, MutableSet<Int>>()
    private val namedByName = HashMap<Long, MutableSet<Int>>()
    private val refused = HashMap<Long, MutableSet<Int>>()
    private val labeled = people.filter { it.image.size == DIM }
    private val withExamples: Set<Int>

    init {
        for (t in teachings) {
            if (t.vector.size != DIM) continue
            val j = (if (t.positive) names.givenBy(t.label) else names.nameOf(t.label)) ?: continue
            (if (t.positive) positives else negatives)[j] += Example(t.vector, LookalikeVote.NO_MEME)
        }
        for (m in labeled) {
            for (label in m.labels) {
                val j = names.givenBy(label) ?: continue
                if (names.nameOf(label) == j) namedByName.getOrPut(m.memeId) { HashSet() } += j
                if (named.getOrPut(m.memeId) { HashSet() }.add(j)) positives[j] += Example(m.image, m.memeId)
            }
            for (label in m.rejected) {
                val j = names.nameOf(label) ?: continue
                if (refused.getOrPut(m.memeId) { HashSet() }.add(j)) negatives[j] += Example(m.image, m.memeId)
            }
        }
        withExamples = titles.indices.filterTo(HashSet()) { positives[it].isNotEmpty() || negatives[it].isNotEmpty() }
    }

    /** The title a label names by the title's name or another of its names; a character's name names none. */
    fun titleNamed(label: String): CuratedLabelDef? = names.titleNamed(label)

    /** Per title, the picks only its examples made on people's labeled memes: right, wrong. */
    val heldOut: Map<String, Pair<Int, Int>> by lazy {
        val votes = HashMap<Int, IntArray>()
        for (m in labeled) {
            val withExamplesPick = pick(m.image, m.memeId, withExamples, emptySet(), leaveOutCopies = true, MIN_MARGIN, MIN_GAP) ?: continue
            val promptPick = pick(m.image, m.memeId, emptySet(), emptySet(), leaveOutCopies = true, MIN_MARGIN, MIN_GAP)
            if (promptPick?.title == withExamplesPick.title) continue
            val right = named[m.memeId]?.contains(withExamplesPick.title) == true
            votes.getOrPut(withExamplesPick.title) { IntArray(2) }[if (right) 0 else 1]++
        }
        votes.entries.associate { (j, v) -> titles[j].label to (v[0] to v[1]) }
    }

    /** Titles whose examples held up on people's labeled memes; only theirs raise a meme's score. */
    val learned: Set<Int> by lazy {
        val byLabel = titles.withIndex().associate { it.value.label to it.index }
        heldOut.filterValues { (right, wrong) ->
            right + wrong >= LookalikeVote.RELIABLE_SUPPORT && right >= LookalikeVote.RELIABLE_PRECISION * (right + wrong)
        }.keys.mapNotNullTo(HashSet()) { byLabel[it] }
    }

    /** This meme's title, or null. [memeId] keeps a meme's own examples out of the comparison. */
    fun identify(image: FloatArray, memeId: Long): Hit? {
        if (image.size != DIM || titles.isEmpty()) return null
        val ruledOut = refused[memeId].orEmpty()
        val given = HashSet<Int>(named[memeId].orEmpty())
        for (j in withExamples) {
            if (j in given) continue
            val copy = positives[j].any { !ownOf(it, memeId) && FloatBlob.dot(it.vector, image) >= LookalikeVote.SAME_IMAGE_COS }
            if (copy && negatives[j].none { FloatBlob.dot(it.vector, image) >= VETO_COS }) given += j
        }
        given -= ruledOut
        if (given.isNotEmpty()) {
            val j = given.maxBy { FloatBlob.dot(image, titles[it].vec) }
            val personsTag = namedByName[memeId]?.contains(j) == true
            return Hit(titles[j], FloatBlob.dot(image, titles[j].vec) - bias(image), named = true, personsTag = personsTag)
        }
        val pick = pick(image, memeId, learned, ruledOut, leaveOutCopies = false, MIN_MARGIN, MIN_GAP) ?: return null
        return Hit(titles[pick.title], pick.score, named = false)
    }

    /**
     * A title worth asking a person about for this meme, or null when a person already gave it one: the best
     * title, with every title's examples counting, at the lower bar [ASK_MARGIN] and [ASK_GAP]. Answers are how a
     * title gathers the examples and held-out picks it needs to become learned.
     */
    fun question(image: FloatArray, memeId: Long): CuratedLabelDef? {
        if (image.size != DIM || titles.isEmpty() || named[memeId] != null) return null
        val pick = pick(image, memeId, withExamples, refused[memeId].orEmpty(), leaveOutCopies = false, ASK_MARGIN, ASK_GAP)
        return pick?.let { titles[it.title] }
    }

    private fun bias(image: FloatArray): Float {
        if (anchors.isEmpty()) return 0f
        var best = Float.NEGATIVE_INFINITY
        for (a in anchors) best = maxOf(best, FloatBlob.dot(image, a))
        return Recognition.ANCHOR_BIAS * best
    }

    /** Whether [e] is one of [memeId]'s own examples (teachings belong to no meme). */
    private fun ownOf(e: Example, memeId: Long): Boolean = e.memeId == memeId && memeId != LookalikeVote.NO_MEME

    /** Nearest example cosine; [exclude]'s own examples, and with [leaveOutCopies] exact copies, are skipped. */
    private fun nearest(examples: List<Example>, image: FloatArray, exclude: Long, leaveOutCopies: Boolean): Float {
        var best = -1f
        for (e in examples) {
            if (ownOf(e, exclude)) continue
            val c = FloatBlob.dot(e.vector, image)
            if (leaveOutCopies && c >= LookalikeVote.SAME_IMAGE_COS) continue
            if (c > best) best = c
        }
        return best
    }

    /** The winning title; [ruledOut] titles (rejected on this meme) don't compete, so the next can win. */
    private fun pick(
        image: FloatArray,
        exclude: Long,
        boosted: Set<Int>,
        ruledOut: Set<Int>,
        leaveOutCopies: Boolean,
        minMargin: Float,
        minGap: Float,
    ): Pick? {
        val bias = bias(image)
        var first = -1
        var firstScore = Float.NEGATIVE_INFINITY
        var secondScore = Float.NEGATIVE_INFINITY
        for (j in titles.indices) {
            if (j in ruledOut) continue
            val margin = FloatBlob.dot(image, titles[j].vec) - bias
            var score = margin
            if (j in withExamples) {
                val against = nearest(negatives[j], image, exclude, leaveOutCopies)
                if (against >= VETO_COS) continue
                if (j in boosted && margin >= EXAMPLE_FLOOR) {
                    val near = nearest(positives[j], image, exclude, leaveOutCopies)
                    if (near > against) score = margin + EXAMPLE_WEIGHT * maxOf(0f, near - EXAMPLE_FROM)
                }
            }
            if (score > firstScore) {
                secondScore = firstScore
                firstScore = score
                first = j
            } else if (score > secondScore) {
                secondScore = score
            }
        }
        if (first < 0 || firstScore < minMargin || firstScore - secondScore < minGap) return null
        return Pick(first, firstScore)
    }

    companion object {
        const val TV = "tv"
        const val MOVIE = "movie"
        val CATEGORIES = setOf(TV, MOVIE)

        /** Tags this identification writes; re-classification recomputes them. */
        const val SOURCE = "title"

        const val MIN_MARGIN = 0.17f
        const val MIN_GAP = 0.02f
        const val EXAMPLE_WEIGHT = 0.3f
        const val EXAMPLE_FROM = 0.55f
        const val EXAMPLE_FLOOR = 0.10f
        const val VETO_COS = LearnCore.PU_EXCLUDE_COS
        const val ASK_MARGIN = 0.15f
        const val ASK_GAP = 0.01f
        private const val DIM = 512

        fun isTitleCategory(category: String): Boolean = category.trim().lowercase(Locale.ROOT) in CATEGORIES

        private val MARKS = Regex("\\p{Mn}+")
        private val APOSTROPHES = Regex("['’`]")
        private val NOT_WORDS = Regex("[^0-9a-z]+")

        /**
         * Match keys for a title name or a label: lowercase, no accents, apostrophes or punctuation, and no "the"
         * before two or more words; with and without spaces.
         */
        fun keys(text: String): Set<String> {
            val folded = MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFKD), "")
                .lowercase(Locale.ROOT).replace("&", " and ")
            var words = NOT_WORDS.replace(APOSTROPHES.replace(folded, ""), " ").trim()
            // "the walking dead" is "walking dead", but "the office" isn't "office": one word after "the" is often
            // an everyday word.
            if (words.startsWith("the ") && words.indexOf(' ', 4) > 0) words = words.substring(4)
            if (words.isEmpty()) return emptySet()
            return setOf(words, words.replace(" ", ""))
        }
    }
}
