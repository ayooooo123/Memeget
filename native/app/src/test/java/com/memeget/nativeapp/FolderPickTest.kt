package com.memeget.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderPickTest {
    @Test
    fun aTagThatOnlyRepeatsAnEarlierFolderLosesToOneThatReachesNewMemes() {
        val sets = listOf(
            setOf("pepe", "sad pepe"),
            setOf("pepe", "sad pepe"),
            setOf("pepe", "sad pepe"),
            setOf("pepe"),
            setOf("doge"),
            setOf("doge"),
        )
        // By count alone "sad pepe" (3) beats "doge" (2), yet every sad pepe is already in the pepe folder.
        val picked = pickFolderKeys(sets, maxFolders = 2, minNew = 2)
        assertEquals(listOf("pepe" to 4, "doge" to 2), picked)
    }

    @Test
    fun picksStopWhenTheNextFolderWouldAddTooFewMemes() {
        val sets = listOf(setOf("cat"), setOf("cat"), setOf("cat", "orange"), setOf("dog"))
        assertEquals(listOf("cat" to 3), pickFolderKeys(sets, maxFolders = 5, minNew = 2))
    }
}
