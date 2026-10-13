package com.memeget.nativeapp.editor

import android.app.Application
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DraftJournalStoreTest {

    @Test
    fun testProjectSerializationRoundtrip() {
        val source = MemeEditSource(
            uri = "content://media/external/images/media/42",
            name = "doge.png",
            kind = MediaEditKind.IMAGE,
            width = 800,
            height = 600
        )
        val textLayer = MemeEditLayer.Text(
            id = "t1",
            text = "MUCH WOW",
            width = 0.75f,
            fontSize = 0.1f,
            style = TextStyle(preset = "impact", color = "#D8FF4A")
        )
        val project = ProjectCore.createDefaultImageProject(source).copy(
            layers = listOf(textLayer),
            base = BaseTransform(rotation = QuarterRotation.R90, flipX = true)
        )

        val serialized = project.serializeJson()
        val restored = MemeEditProject.parseJson(serialized)

        assertEquals(project.version, restored.version)
        assertEquals(project.source.uri, restored.source.uri)
        assertEquals(project.source.width, restored.source.width)
        assertEquals(project.base.rotation, restored.base.rotation)
        assertEquals(project.base.flipX, restored.base.flipX)
        assertEquals(1, restored.layers.size)

        val restoredText = restored.layers[0] as MemeEditLayer.Text
        assertEquals("MUCH WOW", restoredText.text)
        assertEquals(0.75f, restoredText.width, 0.001f)
        assertEquals("#D8FF4A", restoredText.style.color)
    }

    @Test
    fun expiredDraftIsRemovedWithoutBlockingOtherJournalOperations() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val source = MemeEditSource("content://fixture/source.png", "source.png", MediaEditKind.IMAGE, 800, 600)
        val project = ProjectCore.createDefaultImageProject(source)
        val expiredSession = "expired-fixture"
        val otherSession = "retained-fixture"
        assertTrue(DraftJournalStore.saveDraft(context, expiredSession, project))
        assertTrue(DraftJournalStore.saveDraft(context, otherSession, project))
        val expiredDir = File(context.cacheDir, "meme_drafts/$expiredSession")
        val slot = expiredDir.listFiles()!!.single { it.name.startsWith("slot_") }
        val root = JSONObject(slot.readText())
        val payload = root.getJSONObject("payload")
        payload.put("savedAtMs", System.currentTimeMillis() - DraftJournalStore.DRAFT_EXPIRY_MS - 60_000L)
        root.put("checksum", MessageDigest.getInstance("SHA-256")
            .digest(payload.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) })
        slot.writeText(root.toString())

        withTimeout(2_000) {
            assertFalse(DraftJournalStore.restoreDraft(context, expiredSession, source.uri).restored)
            assertFalse(expiredDir.exists())
            assertEquals(project, DraftJournalStore.restoreDraft(context, otherSession, source.uri).project)
            assertTrue(DraftJournalStore.saveDraft(context, expiredSession, project))
            assertEquals(project, DraftJournalStore.restoreDraft(context, expiredSession, source.uri).project)
        }
    }
}
