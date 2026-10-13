package com.memeget.nativeapp.cloud

import com.memeget.nativeapp.CloudConfig
import com.memeget.nativeapp.CloudPayloadPrep
import com.memeget.nativeapp.CloudProviderType
import com.memeget.nativeapp.Meme
import com.memeget.nativeapp.PayloadTooLargeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConsentStateMachineTest {

    @Test
    fun testPayloadScopeIsolation_TextConsentOff() {
        val meme = Meme(
            id = 1L,
            uri = "/private/storage/user_photo_123.jpg",
            name = "my_private_meme.png",
            kind = "image",
            ocr = "Super Secret User Text @alice",
            caption = "Alice laughing at Bob",
            transcript = "Spoken audio text",
        )

        // Consent text state OFF: must yield empty string and 0 bytes
        val configOff = CloudConfig(consentTextState = false)
        val (text, bytes) = CloudPayloadPrep.buildTextState(meme, configOff)
        assertEquals("", text)
        assertEquals(0L, bytes)
    }

    @Test
    fun testPayloadScopeIsolation_TranscriptRequiresExplicitSeparateOptIn() {
        val meme = Meme(
            id = 2L,
            uri = "/private/storage/clip.mp4",
            name = "video.mp4",
            kind = "video",
            ocr = "Meme Headline",
            caption = "Funny scene",
            transcript = "Sensitive audio conversation between people",
        )

        // Text consent ON, but transcript consent OFF: transcript must be strictly excluded
        val configNoTranscript = CloudConfig(
            consentTextState = true,
            consentTranscript = false,
        )
        val (textWithoutTranscript, _) = CloudPayloadPrep.buildTextState(meme, configNoTranscript)
        assertTrue(textWithoutTranscript.contains("Meme Headline"))
        assertTrue(textWithoutTranscript.contains("Funny scene"))
        assertFalse("Transcript must not be included when consentTranscript is false", textWithoutTranscript.contains("Sensitive audio conversation"))

        // When transcript consent is explicitly enabled
        val configWithTranscript = CloudConfig(
            consentTextState = true,
            consentTranscript = true,
        )
        val (textWithTranscript, _) = CloudPayloadPrep.buildTextState(meme, configWithTranscript)
        assertTrue(textWithTranscript.contains("Sensitive audio conversation"))

        // Text state must NEVER include local file path or original file name
        assertFalse(textWithTranscript.contains("/private/storage"))
        assertFalse(textWithTranscript.contains("video.mp4"))
    }

    @Test
    fun testPayloadSizeConstraint_ExceedingOneMegabyteRejects() {
        // Construct a string larger than 1 MiB (1,048,576 bytes)
        val largeBuilder = StringBuilder(1024 * 1025)
        for (i in 0 until (1024 * 1025) / 10) {
            largeBuilder.append("0123456789")
        }
        val hugeJson = "{\"data\":\"" + largeBuilder.toString() + "\"}"

        try {
            CloudPayloadPrep.validatePayloadSize(hugeJson)
            fail("Must throw PayloadTooLargeException when payload exceeds 1 MiB")
        } catch (_: PayloadTooLargeException) {
            // expected
        }

        // Payload within 1 MiB succeeds
        val validJson = "{\"data\":\"small payload\"}"
        CloudPayloadPrep.validatePayloadSize(validJson)
    }

}
