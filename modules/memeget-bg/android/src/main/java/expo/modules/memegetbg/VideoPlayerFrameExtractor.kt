package expo.modules.memegetbg

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.FrameExtractor
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

// Last-resort poster path: pull one frame through ExoPlayer's (media3) decode
// pipeline instead of the platform MediaExtractor / MediaMetadataRetriever the
// other rungs use. ExoPlayer ships its OWN container parsers, which read a
// handful of streams the platform demuxer rejects outright ("no video track",
// "Could not generate thumbnail") — and expo-video already bundles media3, so
// the exact pipeline that PLAYS the clip in the viewer is the one used to
// poster it here. If it doesn't play in ExoPlayer either, this fails too and
// the file is genuinely undecodable (a corrupt/truncated download).
//
// media3 is pinned to expo-video's version in build.gradle so the shared
// ExoPlayer stays a single coherent version across the app.
@OptIn(UnstableApi::class)
object VideoPlayerFrameExtractor {
  // FrameExtractor decodes on a shared player on the main looper and hands back
  // a ListenableFuture; this runs off an Expo async executor, so blocking on the
  // future is safe (the player still advances on the main thread). Bounded so a
  // wedged decode can't hold the executor thread past the JS-side timeout.
  private const val DEADLINE_SEC = 15L

  fun extract(ctx: Context, source: String, seconds: Double): String {
    val uri = when {
      source.startsWith("content://") || source.startsWith("file://") -> Uri.parse(source)
      else -> Uri.parse("file://$source")
    }
    val extractor = FrameExtractor.Builder(ctx, MediaItem.fromUri(uri)).build()
    try {
      // A last-resort rung — the black-frame-aware paths already failed — so any
      // decodable frame is the win. Grab just past the start to dodge the
      // fade-from-black intro; a shorter clip clamps to its final frame.
      val positionMs = if (seconds >= 0) (seconds * 1000).toLong() else 700L
      val frame = extractor.getFrame(positionMs).get(DEADLINE_SEC, TimeUnit.SECONDS)
      return writeJpeg(ctx, frame.bitmap)
    } finally {
      extractor.close()
    }
  }

  // The extracted frame is a software bitmap, but a HARDWARE-config bitmap can't
  // be read back for JPEG encoding on some devices — copy it to a CPU-backed
  // config first when that happens. meme_work_ prefix keeps a leaked temp inside
  // the launch sweep's cleanup net.
  private fun writeJpeg(ctx: Context, bitmap: Bitmap): String {
    val cpu =
      if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false)
      else bitmap
    try {
      val out =
        File(ctx.cacheDir, "meme_work_pframe_${System.currentTimeMillis()}_${cpu.hashCode()}.jpg")
      FileOutputStream(out).use { fos -> cpu.compress(Bitmap.CompressFormat.JPEG, 85, fos) }
      return "file://${out.absolutePath}"
    } finally {
      if (cpu !== bitmap) cpu.recycle()
    }
  }
}
