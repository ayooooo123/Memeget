package com.memeget.nativeapp.editor

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTrackDurationTest {
    @Test fun webmUsesTheAudioEndRatherThanTheVideoEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File.createTempFile("short-track", ".webm", context.cacheDir)
        try {
            // Ten 1 fps VP9 frames, five seconds of Opus, in a ten-second WebM container.
            instrumentation.context.assets.open("short-track.webm").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val endUs = probeAudioTrackUs(context, Uri.fromFile(file).toString())
            assertTrue("audio end: $endUs", endUs != null && endUs in 4_980_000L..5_060_000L)
        } finally {
            file.delete()
        }
    }
}
