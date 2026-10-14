package com.memeget.nativeapp

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import com.memeget.nativeapp.inference.ModelRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FolderBackupHoldTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var store: SqliteLibraryStore
    private lateinit var files: LibraryFiles
    private val folder = Folder("content://com.android.externalstorage.documents/tree/primary%3AMemes", "Memes", 0)

    @Before fun setup() {
        val base = RuntimeEnvironment.getApplication()
        val filesRoot = temporary.newFolder("files")
        val cacheRoot = temporary.newFolder("cache")
        context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = filesRoot
            override fun getCacheDir(): File = cacheRoot
        }
        context.databaseList().forEach { context.deleteDatabase(it) }
        store = SqliteLibraryStore(context)
        files = LibraryFiles(context, store)
    }

    @Test fun afterClearIndexTheAutomaticBackupLeavesTheFolderAloneUntilItIsRestored() = runBlocking {
        store.addFolder(folder.uri, folder.name)
        store.clearIndex()

        // Writing now would replace the folder's backup, which still holds the tags, with an empty library.
        assertEquals(1, files.heldBackups())
        assertEquals(BackupRun(0, emptyMap()), files.backupAll())

        files.restore(folder)
        assertEquals(0, files.heldBackups())
    }

    @Test fun replacingTeachingsWithTheSamePackKeepsThePack() = runBlocking {
        val pack = listOf(true, false).mapIndexed { i, positive ->
            Exemplar(0, "pepe", "character", FloatArray(512).apply { this[i] = 1f }, positive, emptyList(), "", ModelRegistry.PRIMARY_MODEL_FINGERPRINT)
        }
        store.importExemplars(pack, pack = "frogs")

        // The examples just removed must not count as already present.
        assertEquals(2 to 2, store.replaceExemplars(pack, pack = "frogs"))
        assertEquals(2, store.exemplars().size)
    }
}
