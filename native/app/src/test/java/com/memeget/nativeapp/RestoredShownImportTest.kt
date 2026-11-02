package com.memeget.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class RestoredShownImportTest {
    /** A recreated process starts import ids at 0 again; a window restored from before must still show what comes next. */
    @Test fun aRestoredWindowAfterTheProcessDiedShowsTheNextImport() {
        assertEquals(0L, restoredShownImport(saved = 5, status = ImportStatus()))
        assertEquals(0L, restoredShownImport(saved = 5, status = ImportStatus(id = 1, running = true)))
    }

    @Test fun aRotatedWindowKeepsWhatItShowedAndStillShowsWhatItMissed() {
        assertEquals(4L, restoredShownImport(saved = 4, status = ImportStatus(id = 4, outcome = "Imported 1 items")))
        assertEquals(4L, restoredShownImport(saved = 4, status = ImportStatus(id = 5, outcome = "Imported 2 items")))
        assertEquals(4L, restoredShownImport(saved = 4, status = ImportStatus(id = 5, running = true)))
    }

    @Test fun aNewWindowShowsARunningImportButNotOneThatEnded() {
        assertEquals(2L, restoredShownImport(saved = null, status = ImportStatus(id = 3, running = true)))
        assertEquals(3L, restoredShownImport(saved = null, status = ImportStatus(id = 3, outcome = "Imported 1 items")))
    }
}
