package com.memeget.nativeapp.storage

import com.memeget.nativeapp.Exemplar
import com.memeget.nativeapp.Meme
import com.memeget.nativeapp.Tag
import com.memeget.nativeapp.VectorRecord
import com.memeget.nativeapp.inference.ModelRegistry
import com.memeget.nativeapp.data.TagMerge
import com.memeget.nativeapp.knowledge.Recognition
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

class StorageInvariantsTest {

    @Test
    fun testChunkForDeterministicAndBounded() {
        val chunk1 = SidecarFormat.chunkFor("funny_cat.jpg")
        val chunk2 = SidecarFormat.chunkFor("funny_cat.jpg")
        assertEquals(chunk1, chunk2)
        assertEquals(2, chunk1.length)

        val intVal = chunk1.toInt(16)
        assertTrue("Chunk index must be in [0, 63]", intVal in 0..63)
    }

    @Test
    fun backupIntegrityAcceptsBothVersionOneDigestsAndRejectsChangedUtf8Content() {
        val payload = "{\"caption\":\"caf\u00e9\u6708\"}"
        val changed = "{\"caption\":\"cafe\u6708\"}"
        val digests = listOf(
            "de0688630ef4dd3909203475f5b657f45ec491aba238423f398e8a051db99f82",
            "j.z5xjwu",
        )
        for (digest in digests) {
            assertEquals(payload, SidecarFormat.readVerified(payload.byteInputStream(), digest))
            assertNull(SidecarFormat.readVerified(changed.byteInputStream(), digest))
            assertNull(SidecarFormat.readVerified(payload.dropLast(1).byteInputStream(), digest))
        }
    }

    @Test
    fun testVectorEncodingDecodingRoundTrip() {
        val original = FloatArray(512) { i -> (i * 0.123f) - 10f }
        val encoded = SidecarFormat.encodeVector(original)
        assertTrue(encoded.isNotBlank())

        val decoded = SidecarFormat.decodeVector(encoded)
        assertNotNull(decoded)
        assertEquals(512, decoded!!.size)
        assertArrayEquals(original, decoded, 0.00001f)
    }

    @Test
    fun testVectorDecodingMalformedReturnsNull() {
        assertNull(SidecarFormat.decodeVector(""))
        assertNull(SidecarFormat.decodeVector(null))
        // 3 bytes is not a multiple of 4 (float32)
        val invalidBase64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        assertNull(SidecarFormat.decodeVector(invalidBase64))
    }

    @Test
    fun testNativeSidecarChunkRoundTripPreservesProvenance() {
        val tag = Tag(
            label = "doge",
            category = "character",
            score = 0.98f,
            source = "user",
            scoreDomain = "calibrated",
            confirmation = "confirmed",
            provenance = "manual_correction"
        )
        val meme = Meme(
            id = 1L,
            uri = "content://media/1",
            name = "doge.jpg",
            kind = "image",
            ocr = "much wow",
            caption = "A doge meme",
            transcript = "",
            tags = listOf(tag),
            extraTerms = "shiba inu",
            modifiedAt = 1234567890L,
            pending = false,
            visionState = "done",
            audioState = "none",
            contentHash = "hash123",
            cloudCaption = "cloud caption",
            cloudTerms = "cloud terms",
            legacyImported = false
        )
        val vec = VectorRecord(
            id = 1L,
            image = FloatArray(512) { 0.5f },
            caption = FloatArray(512) { 0.2f },
            visual = null,
            model = ModelRegistry.PRIMARY_MODEL_FINGERPRINT
        )

        val json = SidecarFormat.serializeNativeChunk(
            generation = 9999L,
            chunkKey = "05",
            memes = listOf(Pair(meme, vec))
        )

        val parsed = SidecarFormat.parseNativeChunk(json) { name, hash ->
            if (name == "doge.jpg") Pair("content://restored/doge.jpg", "image") else null
        }

        assertEquals(1, parsed.size)
        val restored = parsed[0]
        assertEquals("content://restored/doge.jpg", restored.meme.uri)
        assertEquals("much wow", restored.meme.ocr)
        assertEquals("cloud caption", restored.meme.cloudCaption)
        assertFalse(restored.meme.legacyImported)

        val restoredTag = restored.meme.tags[0]
        assertEquals("doge", restoredTag.label)
        assertEquals("confirmed", restoredTag.confirmation)
        assertEquals("manual_correction", restoredTag.provenance)
        assertEquals("calibrated", restoredTag.scoreDomain)

        assertNotNull(restored.vectorRecord)
        assertEquals(512, restored.vectorRecord!!.image.size)
        assertNotNull(restored.vectorRecord!!.caption)
        assertEquals(512, restored.vectorRecord!!.caption!!.size)
    }

    @Test
    fun nativeSidecarWithoutFingerprintPreservesTextAndRequiresReindex() {
        val meme = Meme(1L, "content://media/probe", "probe.png", "image",
            ocr = "searchable words", caption = "saved caption", pending = false, visionState = "done",
            contentHash = "a".repeat(64))
        val vector = VectorRecord(1L, FloatArray(512).apply { this[0] = 1f }, model = "")
        val backup = JSONObject(SidecarFormat.serializeNativeChunk(1L, "00", listOf(meme to vector)))
        val item = backup.getJSONArray("memes").getJSONObject(0)
        assertEquals("Unknown vectors must not acquire the current fingerprint during export", "", item.getString("model"))
        item.remove("model")

        val restored = SidecarFormat.parseNativeChunk(backup.toString()) { _, _ ->
            "content://restored/probe" to "image"
        }.single()
        assertEquals("searchable words", restored.meme.ocr)
        assertEquals("saved caption", restored.meme.caption)
        assertTrue("Unknown vectors must not skip local indexing", restored.meme.pending)
        assertEquals("pending", restored.meme.visionState)
        assertEquals("", restored.vectorRecord!!.model)
    }

    @Test
    fun filenameOnlyNativeRestoreCannotTransferVectorsOrReviewedIdentity() {
        val original = Meme(
            1L, "content://original/reused-name", "reused-name.png", "image",
            ocr = "imported words", caption = "saved description", transcript = "saved speech",
            tags = listOf(
                Tag("old identity", "character", 1f, "manual", "user", "confirmed", "user"),
                Tag("old cloud identity", "character", .99f, "cloud",
                    "provider-choice-probability", "confirmed", "provider-proof"),
            ),
            pending = false, visionState = "done", rejectedLabels = listOf("replacement identity"),
        )
        val vector = VectorRecord(1L, FloatArray(512).apply { this[0] = 1f },
            model = ModelRegistry.PRIMARY_MODEL_FINGERPRINT)
        val json = SidecarFormat.serializeNativeChunk(1L, "00", listOf(original to vector))
        val restored = SidecarFormat.parseNativeChunk(json) { _, _ ->
            "content://replacement/reused-name" to "image"
        }.single()

        assertNull("A matching recipe does not prove a filename match has the same bytes", restored.vectorRecord)
        assertTrue(restored.meme.pending)
        assertEquals("pending", restored.meme.visionState)
        assertTrue(restored.meme.legacyImported)
        assertEquals("imported words", restored.meme.ocr)
        assertEquals("saved description", restored.meme.caption)
        assertEquals("saved speech", restored.meme.transcript)
        assertEquals("unknown", Recognition.recognitionTier(restored.meme.tags))
        assertTrue(restored.meme.tags.none { it.confirmation == "confirmed" || TagMerge.isDurableTag(it) })
        assertTrue(restored.meme.rejectedLabels.isEmpty())
    }

    @Test
    fun testLegacySidecarPreservesTextWithoutTransferringReviewedIdentityOrVectors() {
        val legacyChunkJson = """
            {
              "format": "memeget-sidecar",
              "version": 2,
              "chunk": "00",
              "count": 1,
              "memes": [
                {
                  "name": "pepe.png",
                  "kind": "image",
                  "tags": [{"label": "pepe", "category": "character", "score": 1.0, "source": "prompt"}],
                  "extraTerms": "",
                  "ocr": "feels good man",
                  "caption": "rare pepe",
                  "transcript": "",
                  "visionState": "done",
                  "audioState": "done",
                  "modifiedAt": 1000,
                  "embedding": "${SidecarFormat.encodeVector(FloatArray(512) { 0.1f })}",
                  "captionEmbedding": "${SidecarFormat.encodeVector(FloatArray(512) { 0.2f })}",
                  "visualEmbedding": "",
                  "visualModel": ""
                }
              ]
            }
        """.trimIndent()

        val parsed = SidecarFormat.parseLegacyChunk(
            jsonText = legacyChunkJson,
            uriByName = { name -> if (name == "pepe.png") Pair("content://files/pepe.png", "image") else null },
        )

        assertEquals(1, parsed.size)
        val r = parsed[0]
        assertTrue("Legacy restored meme must have legacyImported=true", r.meme.legacyImported)
        assertEquals("done", r.meme.audioState) // Preserves completed silent audio

        val tag = r.meme.tags[0]
        assertEquals("pepe", tag.label)
        assertEquals("legacy-sidecar", tag.provenance)
        assertEquals("unreviewed", tag.confirmation)
        assertEquals("uncalibrated", tag.scoreDomain)

        assertEquals("feels good man", r.meme.ocr)
        assertEquals("rare pepe", r.meme.caption)
        assertTrue(r.meme.pending)
        assertEquals("pending", r.meme.visionState)
        assertEquals("unknown", Recognition.recognitionTier(r.meme.tags))
        assertTrue(r.meme.tags.none(TagMerge::isDurableTag))
        assertNull(r.vectorRecord)
    }

    @Test
    fun legacyTeachingImportRetainsModelAndProvenance() {
        val vector = FloatArray(512).apply { this[0] = 1f }
        val json = """
            {"format":"memeget-teaching-pack","version":2,"model":"mobileclip-s2","dim":512,
             "name":"Example pack","exemplars":[{"label":"doge","category":"character",
             "positive":false,"associations":["shiba","dog"],"vector":${vector.toList()}}]}
        """.trimIndent()
        val parsed = TeachingPackIo.parseNativeTeachings(json)

        assertEquals(1, parsed.size)
        val example = parsed.single()
        assertEquals("doge", example.label)
        assertEquals("character", example.category)
        assertFalse(example.positive)
        assertEquals(listOf("shiba", "dog"), example.associations)
        assertArrayEquals(vector, example.vector, 0f)
        assertEquals("mobileclip-s2", example.model)
        assertEquals("pack", example.origin)
        assertEquals("Example pack", example.pack)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testTeachingPackForeignModelRejected() {
        val foreignJson = """
            {
              "format": "memeget-teaching-pack",
              "version": 2,
              "model": "vit-b-32",
              "dim": 512,
              "name": "Foreign",
              "exemplars": [
                {
                  "label": "test",
                  "vector": ${FloatArray(512) { 0.1f }.toList()},
                  "positive": true
                }
              ]
            }
        """.trimIndent()

        TeachingPackIo.parseNativeTeachings(foreignJson)
    }


    @Test
    fun testLinkResolverExtractUrl() {
        val text = "Check out this hilarious meme https://x.com/user/status/1844000000000000000?s=20 it is peak"
        val extracted = LinkResolver.extractUrl(text)
        assertEquals("https://x.com/user/status/1844000000000000000?s=20", extracted)

        // Trailing sentence punctuation must be trimmed
        val punctuated = "Check out this link: https://x.com/user/status/1844000000000000000."
        assertEquals("https://x.com/user/status/1844000000000000000", LinkResolver.extractUrl(punctuated))

        val inParens = "(https://x.com/user/status/1844000000000000000)"
        assertEquals("https://x.com/user/status/1844000000000000000", LinkResolver.extractUrl(inParens))

        assertNull(LinkResolver.extractUrl("javascript:alert(1)", "ftp://example.com"))
    }
    @Test
    fun testLinkResolverEntityDecoding() {
        val input = "foo &amp; bar &quot;baz&quot; &#039;qux&#039; &#x2F;path"
        val decoded = LinkResolver.decodeEntities(input)
        assertEquals("foo & bar \"baz\" 'qux' /path", decoded)
    }

    @Test
    fun testLinkResolverSyndicationTokenDeterministic() {
        val id1 = "1234567890123456789"
        assertEquals("2zqic77uqyk", LinkResolver.syndicationToken(id1))

        val id2 = "2078262789844668506"
        assertEquals("51d1zfcckht", LinkResolver.syndicationToken(id2))

        val id3 = "1789012345678901234"
        assertEquals("4c4cj29iz3i", LinkResolver.syndicationToken(id3))

        val id4 = "1844000000000000000"
        val token = LinkResolver.syndicationToken(id4)
        assertTrue(token.isNotBlank())
        assertFalse(token.contains("."))
        assertFalse(token.contains("0"))
    }

    @Test
    fun testLinkResolverMetaContentTolerantOfAttributeOrder() {
        val html1 = """<meta property="og:video" content="https://example.com/video.mp4">"""
        val html2 = """<meta content="https://example.com/video.mp4" property="og:video">"""
        val html3 = """<meta name="twitter:image" content="https://example.com/image.jpg">"""

        assertEquals("https://example.com/video.mp4", LinkResolver.metaContent(html1, "og:video"))
        assertEquals("https://example.com/video.mp4", LinkResolver.metaContent(html2, "og:video"))
        assertEquals("https://example.com/image.jpg", LinkResolver.metaContent(html3, "twitter:image"))
    }

    @Test
    fun testMediaIoUtilsSha256() {
        val stream = ByteArrayInputStream("hello world".toByteArray(StandardCharsets.UTF_8))
        val sha256 = MediaIoUtils.computeSha256(stream)
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", sha256)
    }

    @Test
    fun testMediaIoUtilsExtensionAndMime() {
        assertEquals("jpg", MediaIoUtils.extOf("foo.JPG"))
        assertEquals("mp4", MediaIoUtils.extOf("bar.mp4"))
        assertEquals("", MediaIoUtils.extOf("noext"))

        assertEquals("image", MediaIoUtils.kindOf("test.png"))
        assertEquals("video", MediaIoUtils.kindOf("test.webm"))
        assertNull(MediaIoUtils.kindOf("document.pdf"))

        assertEquals("image/jpeg", MediaIoUtils.mimeForName("photo.jpg"))
        assertEquals("video/mp4", MediaIoUtils.mimeForName("clip.mp4"))
    }
    @Test
    fun testNativeTeachingPackLosslessRoundTrip() {
        val exemplars = listOf(
            Exemplar(
                id = 42L,
                label = "gigachad",
                category = "character",
                vector = FloatArray(512) { 0.33f },
                positive = true,
                associations = listOf("chad", "sigma"),
                sourceUri = "content://media/42",
                model = "mobileclip-s2@512-native-et0.9.2",
                origin = "pack",
                pack = "Community Pack 1"
            )
        )

        val json = TeachingPackIo.serializeNativeTeachings(exemplars, name = "Native Pack")
        val parsed = TeachingPackIo.parseNativeTeachings(json)

        assertEquals(1, parsed.size)
        val p = parsed[0]
        assertEquals(42L, p.id)
        assertEquals("gigachad", p.label)
        assertEquals("character", p.category)
        assertTrue(p.positive)
        assertEquals(listOf("chad", "sigma"), p.associations)
        assertEquals("mobileclip-s2@512-native-et0.9.2", p.model)
        assertEquals("pack", p.origin)
        assertEquals("Community Pack 1", p.pack)
        assertEquals("content://media/42", p.sourceUri)
    }

    private fun nativeTeachingPayload(): JSONObject {
        val example = Exemplar(1L, "format", "format", FloatArray(512).apply { this[0] = 1f },
            true, emptyList(), "synthetic:format", ModelRegistry.PRIMARY_MODEL_FINGERPRINT)
        return JSONObject(TeachingPackIo.serializeNativeTeachings(listOf(example)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun futureNativeTeachingSchemaIsNotSilentlyImported() {
        val payload = nativeTeachingPayload().put("version", TeachingPackIo.NATIVE_TEACHING_VERSION + 1)
        TeachingPackIo.parseNativeTeachings(payload.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingNativeTeachingFingerprintIsNotRestampedAsCurrent() {
        val payload = nativeTeachingPayload()
        payload.getJSONArray("exemplars").getJSONObject(0).remove("model")
        TeachingPackIo.parseNativeTeachings(payload.toString())
    }
}
