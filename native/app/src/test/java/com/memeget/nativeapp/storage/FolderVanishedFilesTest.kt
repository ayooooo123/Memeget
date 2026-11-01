package com.memeget.nativeapp.storage

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import com.memeget.nativeapp.Folder
import com.memeget.nativeapp.LibraryFiles
import com.memeget.nativeapp.SqliteLibraryStore
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A scan forgets a linked folder's memes whose file was deleted or trashed elsewhere, and nothing else: a folder it
 * can't fully read, or one that is gone, keeps every meme with its captions and tags.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FolderVanishedFilesTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var storage: File
    private lateinit var provider: LocalDocuments
    private lateinit var store: SqliteLibraryStore
    private lateinit var files: LibraryFiles

    @Before
    fun setUp() {
        storage = File(context.cacheDir, "storage").apply { deleteRecursively(); mkdirs() }
        val info = ProviderInfo().apply {
            authority = AUTHORITY
            name = LocalDocuments::class.java.name
            packageName = context.packageName
            exported = true
            grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        }
        provider = Robolectric.buildContentProvider(LocalDocuments::class.java).create(info).get()
        provider.root = storage
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(DocumentsContract.PROVIDER_INTERFACE),
            ResolveInfo().apply { providerInfo = info },
        )
        store = SqliteLibraryStore(context)
        files = LibraryFiles(context, store)
    }

    private fun folder(name: String, vararg memes: String): Folder {
        File(storage, name).mkdirs()
        memes.forEach { File(storage, "$name/$it").writeBytes(byteArrayOf(1, 2, 3)) }
        return Folder(DocumentsContract.buildTreeDocumentUri(AUTHORITY, name).toString(), name, 1L)
    }

    private suspend fun captioned(ids: List<Long>): Map<String, Long> = ids.associateBy { id ->
        val meme = requireNotNull(store.meme(id))
        store.setCaption(id, "caption of ${meme.name}", emptyList(), null)
        meme.name
    }

    @Test
    fun trashedFileLeavesTheLibraryAndTheRestKeepTheirCaptions() = runBlocking {
        val meme = folder("Meme", "trashed.gif", "kept.jpg")
        val ids = captioned(files.scan(meme))
        File(storage, "Meme/trashed.gif").delete()

        files.scan(meme)

        assertNull(store.meme(ids.getValue("trashed.gif")))
        assertEquals("caption of kept.jpg", store.meme(ids.getValue("kept.jpg"))?.caption)
    }

    @Test
    fun incompleteListingKeepsMemesItDidNotList() = runBlocking {
        val meme = folder("Meme", "trashed.gif", "kept.jpg")
        val ids = captioned(files.scan(meme))
        File(storage, "Meme/trashed.gif").delete()
        provider.unnamedChild = true

        files.scan(meme)

        assertEquals("caption of trashed.gif", store.meme(ids.getValue("trashed.gif"))?.caption)
    }

    @Test
    fun revokedFolderAccessKeepsEveryMeme() = runBlocking {
        val meme = folder("Meme", "trashed.gif", "kept.jpg")
        val ids = captioned(files.scan(meme))
        File(storage, "Meme/trashed.gif").delete()
        provider.revoked = true

        files.scan(meme)

        assertEquals("caption of trashed.gif", store.meme(ids.getValue("trashed.gif"))?.caption)
        assertEquals("caption of kept.jpg", store.meme(ids.getValue("kept.jpg"))?.caption)
    }

    @Test
    fun deletedFolderKeepsItsMemes() = runBlocking {
        val meme = folder("Meme", "a.jpg", "b.jpg")
        val ids = captioned(files.scan(meme))
        File(storage, "Meme").deleteRecursively()

        files.scan(meme)

        assertEquals("caption of a.jpg", store.meme(ids.getValue("a.jpg"))?.caption)
        assertEquals("caption of b.jpg", store.meme(ids.getValue("b.jpg"))?.caption)
    }

    @Test
    fun scanningOneFolderLeavesAnotherFoldersMemesAlone() = runBlocking {
        val meme = folder("Meme", "kept.jpg")
        val other = folder("Memes", "trashed.gif")
        files.scan(meme)
        val otherIds = captioned(files.scan(other))
        File(storage, "Memes/trashed.gif").delete()

        files.scan(meme)

        assertEquals("caption of trashed.gif", store.meme(otherIds.getValue("trashed.gif"))?.caption)
    }

    @Test
    fun aFileOutsideTheAppsFoldersIsNeverTakenAsGone() {
        // Shared storage hides other apps' files from File, so not seeing one there proves nothing.
        val shared = File(context.cacheDir.parentFile!!.parentFile, "shared/Meme").apply { mkdirs() }
        val own = File(context.filesDir, "imports").apply { mkdirs() }

        assertFalse(MediaIoUtils.isGone(context, File(shared, "x.jpg").toURI().toString()))
        assertTrue(MediaIoUtils.isGone(context, File(own, "x.jpg").toURI().toString()))
    }

    /** Local storage as the phone's provider serves it: document ids are paths, missing files fail its tree check. */
    class LocalDocuments : DocumentsProvider() {
        lateinit var root: File
        var revoked = false
        var unnamedChild = false

        override fun onCreate() = true

        private fun existing(documentId: String): File {
            if (revoked) throw SecurityException("Permission Denial: no grant for $documentId")
            val file = File(root, documentId)
            if (!file.exists()) throw FileNotFoundException("Missing file for $documentId at ${file.path}")
            return file
        }

        override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
            existing(parentDocumentId)
            existing(documentId)
            documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")
        } catch (missing: FileNotFoundException) {
            throw IllegalArgumentException("Failed to determine if $documentId is child of $parentDocumentId: $missing")
        }

        private fun MatrixCursor.add(projection: Array<String>, documentId: String, file: File, name: String? = file.name) {
            newRow().apply {
                for (column in projection) {
                    add(column, when (column) {
                        Document.COLUMN_DOCUMENT_ID -> documentId
                        Document.COLUMN_DISPLAY_NAME -> name
                        Document.COLUMN_MIME_TYPE -> if (file.isDirectory) Document.MIME_TYPE_DIR else MediaIoUtils.mimeForName(file.name)
                        Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                        Document.COLUMN_SIZE -> file.length()
                        else -> null
                    })
                }
            }
        }

        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))

        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
            val columns = arrayOf(*(projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME)))
            return MatrixCursor(columns).apply { add(columns, documentId, existing(documentId)) }
        }

        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
            val columns = arrayOf(*(projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME)))
            val dir = existing(parentDocumentId)
            return MatrixCursor(columns).apply {
                dir.listFiles().orEmpty().sortedBy { it.name }.forEach { add(columns, "$parentDocumentId/${it.name}", it) }
                if (unnamedChild) add(columns, "$parentDocumentId/unnamed", dir, name = null)
            }
        }

        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
            ParcelFileDescriptor.open(existing(documentId), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private companion object {
        const val AUTHORITY = "test.local.documents"
    }
}
