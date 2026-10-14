package com.memeget.nativeapp.knowledge

import com.memeget.nativeapp.LocalKnowledge
import com.memeget.nativeapp.Meme
import org.junit.Assert.assertEquals
import org.junit.Test

class LabelQueueAdvanceTest {
    private fun meme(id: Long) = Meme(id = id, uri = "file:///m$id.jpg", name = "m$id.jpg", kind = "image")

    private fun ask(id: Long, label: String, needed: Int) =
        LocalKnowledge.QueueItem(meme(id), FloatArray(0), 0, LocalKnowledge.Ask(label, "topic", listOf("tired"), needed, 59))

    private fun regular(id: Long) = LocalKnowledge.QueueItem(meme(id), FloatArray(0), 3)

    @Test fun eachYesCountsDownAndTheLabelsOtherCardsGoOnceItSpreads() {
        val queue = listOf(ask(1, "pepe", 2), ask(2, "pepe", 2), ask(3, "pepe", 2), regular(9))

        val afterOne = LocalKnowledge.advance(queue, yes = true)
        assertEquals(listOf(2L, 3L, 9L), afterOne.map { it.meme.id })
        assertEquals(listOf(1, 1), afterOne.mapNotNull { it.ask?.needed })

        assertEquals(listOf(9L), LocalKnowledge.advance(afterOne, yes = true).map { it.meme.id })
    }

    @Test fun notThisAndSkipsKeepTheCountAndDropALabelThatCanNoLongerGetThere() {
        val queue = listOf(ask(1, "pepe", 2), ask(2, "pepe", 2), ask(3, "pepe", 2), ask(4, "doll", 1), regular(9))

        val afterNo = LocalKnowledge.advance(queue, yes = false)
        assertEquals(listOf(2L, 3L, 4L, 9L), afterNo.map { it.meme.id })
        assertEquals(2, afterNo.first().ask?.needed)

        // A skip leaves one pepe card for two needed answers, so pepe's cards go; doll's stays.
        assertEquals(listOf(4L, 9L), LocalKnowledge.advance(afterNo, yes = null).map { it.meme.id })
    }
}
