package com.memeget.nativeapp.knowledge

import com.memeget.nativeapp.knowledge.LookalikeVote.Example
import com.memeget.nativeapp.knowledge.LookalikeVote.Index
import com.memeget.nativeapp.knowledge.LookalikeVote.LabeledMeme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt

class LookalikeVoteTest {
    /** Unit vector cos·e[base] + sin·e[side]; two of them sharing [base] have cosine cos². */
    private fun vec(base: Int, cos: Float, side: Int = 511): FloatArray =
        FloatArray(512).apply {
            this[base] = cos
            this[side] = sqrt(1f - cos * cos)
        }

    private fun axis(i: Int) = FloatArray(512).apply { this[i] = 1f }

    private fun example(v: FloatArray, label: String, meme: Long = LookalikeVote.NO_MEME) = Example(v, label, "character", meme)

    @Test fun aCloserNotThisExampleBlocksTheLabel() {
        val query = axis(0)
        val pepe = example(vec(0, 0.95f, side = 1), "pepe")
        assertEquals("pepe", LookalikeVote.winner(Index(listOf(pepe), emptyMap()), query)?.label)
        assertNull(LookalikeVote.winner(Index(listOf(pepe), mapOf("pepe" to listOf(axis(0)))), query))
    }

    @Test fun nothingBelowTheCutoffWinsAndAMemeNeverVotesForItself() {
        val query = axis(0)
        val faint = example(vec(0, 0.79f, side = 1), "wojak")
        assertNull(LookalikeVote.winner(Index(listOf(faint), emptyMap()), query))
        val own = example(axis(0), "doge", meme = 7)
        assertEquals("doge", LookalikeVote.winner(Index(listOf(own, faint), emptyMap()), query)?.label)
        assertNull(LookalikeVote.winner(Index(listOf(own, faint), emptyMap()), query, excludeMeme = 7))
    }

    @Test fun copiesOfALabeledPictureCarryItsLabels() {
        val index = Index(listOf(example(axis(0), "crt"), example(vec(0, 0.98f, side = 1), "schizo")), emptyMap())
        assertEquals(listOf("crt"), LookalikeVote.samePicture(index, axis(0)).map { it.label })
    }

    @Test fun aLabelAppliesOnItsOwnOnlyAfterEnoughHeldOutVotesWereRight() {
        // Within a cluster every pair has cosine 0.9; clusters are orthogonal.
        val a = sqrt(0.9f)
        val memes = listOf(
            Triple(1L, vec(0, a, side = 11), "pepe"),
            Triple(2L, vec(0, a, side = 12), "pepe"),
            Triple(3L, vec(0, a, side = 13), "pepe"),
            Triple(4L, vec(1, a, side = 21), "wojak"),
            Triple(5L, vec(1, a, side = 22), "wojak"),
            Triple(6L, vec(1, a, side = 23), "wojak"),
            // A different label inside the wojak cluster: its held-out vote says wojak, a miss.
            Triple(7L, vec(1, a, side = 24), "doge"),
            Triple(8L, vec(2, a, side = 31), "milady"),
            Triple(9L, vec(2, a, side = 32), "milady"),
        )
        val index = Index(memes.map { (id, v, label) -> example(v, label, id) }, emptyMap())
        val labeled = memes.map { (id, v, label) -> LabeledMeme(id, v, setOf(label), emptySet()) }

        // pepe 3/3 right; wojak 3/4 (below 90%); milady only 2 votes (below 3).
        assertEquals(setOf("pepe"), LookalikeVote.reliableLabels(index, labeled))
    }
}
