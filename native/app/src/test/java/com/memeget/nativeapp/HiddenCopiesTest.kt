package com.memeget.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class HiddenCopiesTest {
    private fun meme(id: Long) = Meme(id = id, uri = "file:///m$id.jpg", name = "m$id", kind = "image")

    @Test
    fun aCopyWhoseKeeperIsOnScreenIsLeftOutOfTheList() {
        // The list the grid shows and that All, Share, Tag and Delete act on: the hidden copy 2 must not be in it.
        val shown = withoutHiddenCopies(listOf(meme(1), meme(2), meme(3)), duplicateOf = mapOf(2L to 1L))
        assertEquals(listOf(1L, 3L), shown.map { it.id })
    }

    @Test
    fun aCopyWhoseKeeperIsNotInTheListStays() {
        // A search that found only the copy still shows it.
        val shown = withoutHiddenCopies(listOf(meme(2), meme(3)), duplicateOf = mapOf(2L to 1L))
        assertEquals(listOf(2L, 3L), shown.map { it.id })
    }
}
