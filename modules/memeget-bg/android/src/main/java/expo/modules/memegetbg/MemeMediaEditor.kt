package expo.modules.memegetbg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/** Small, offline media edits used by the meme variation flow. */
@OptIn(UnstableApi::class)
object MemeMediaEditor {
  private const val MAX_IMAGE_EDGE = 2048

  fun renderImage(
    context: Context,
    source: String,
    topText: String,
    bottomText: String,
    coverTop: Boolean,
    coverBottom: Boolean
  ): String {
    val uri = sourceUri(source)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openSource(context, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Could not decode $source")

    var sample = 1
    while (max(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_IMAGE_EDGE) sample *= 2
    val decoded = openSource(context, uri).use {
      BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: throw IOException("Could not decode $source")
    val bitmap = decoded.copy(Bitmap.Config.ARGB_8888, true)
    if (bitmap !== decoded) decoded.recycle()
    val canvas = Canvas(bitmap)
    val bandHeight = (bitmap.height * 0.22f).toInt()
    val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 0, 0, 0) }
    if (coverTop) canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bandHeight.toFloat(), bandPaint)
    if (coverBottom) {
      canvas.drawRect(0f, (bitmap.height - bandHeight).toFloat(), bitmap.width.toFloat(), bitmap.height.toFloat(), bandPaint)
    }
    drawCaption(canvas, topText.trim(), top = true)
    drawCaption(canvas, bottomText.trim(), top = false)

    val out = File(context.cacheDir, "meme_work_${System.currentTimeMillis()}.png")
    FileOutputStream(out).use { stream ->
      if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw IOException("PNG encode failed")
    }
    bitmap.recycle()
    return Uri.fromFile(out).toString()
  }

  private fun drawCaption(canvas: Canvas, text: String, top: Boolean) {
    if (text.isBlank()) return
    val width = (canvas.width * 0.9f).toInt()
    val size = min(150f, max(34f, canvas.width * 0.075f))
    val x = (canvas.width - width) / 2f
    val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = size
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val stroke = TextPaint(fill).apply {
      color = Color.BLACK
      style = Paint.Style.STROKE
      strokeWidth = max(4f, size * 0.09f)
      strokeJoin = Paint.Join.ROUND
    }
    val fillLayout = layout(text, fill, width)
    val strokeLayout = layout(text, stroke, width)
    val margin = canvas.height * 0.035f
    val y = if (top) margin else canvas.height - fillLayout.height - margin
    canvas.save()
    canvas.translate(x, y)
    strokeLayout.draw(canvas)
    fillLayout.draw(canvas)
    canvas.restore()
  }

  private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout =
    StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
      .setAlignment(Layout.Alignment.ALIGN_CENTER)
      .setIncludePad(false)
      .build()

  fun exportVideo(
    context: Context,
    source: String,
    topText: String,
    bottomText: String,
    onSuccess: (String) -> Unit,
    onError: (Throwable) -> Unit
  ) {
    val out = File(context.cacheDir, "meme_work_${System.currentTimeMillis()}.mp4")
    val overlays = buildList {
      if (topText.isNotBlank()) add(textOverlay(topText.trim(), top = true))
      if (bottomText.isNotBlank()) add(textOverlay(bottomText.trim(), top = false))
    }
    val builder = EditedMediaItem.Builder(MediaItem.fromUri(sourceUri(source)))
    if (overlays.isNotEmpty()) {
      builder.setEffects(Effects(emptyList(), listOf(OverlayEffect(overlays))))
    }
    val edited = builder.build()
    val transformer = Transformer.Builder(context)
      .setVideoMimeType(MimeTypes.VIDEO_H264)
      .setAudioMimeType(MimeTypes.AUDIO_AAC)
      .addListener(object : Transformer.Listener {
        override fun onCompleted(composition: Composition, result: ExportResult) {
          onSuccess(Uri.fromFile(out).toString())
        }

        override fun onError(
          composition: Composition,
          result: ExportResult,
          exception: ExportException
        ) {
          out.delete()
          onError(exception)
        }
      })
      .build()
    try {
      transformer.start(edited, out.absolutePath)
    } catch (error: Throwable) {
      out.delete()
      onError(error)
    }
  }

  private fun textOverlay(text: String, top: Boolean): TextOverlay {
    val value = SpannableString(text.uppercase()).apply {
      setSpan(ForegroundColorSpan(Color.WHITE), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      setSpan(BackgroundColorSpan(Color.argb(150, 0, 0, 0)), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      setSpan(StyleSpan(Typeface.BOLD), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    val settings = StaticOverlaySettings.Builder()
      .setBackgroundFrameAnchor(0f, if (top) 0.82f else -0.82f)
      .setOverlayFrameAnchor(0f, if (top) 1f else -1f)
      .setScale(0.72f, 0.72f)
      .build()
    return TextOverlay.createStaticTextOverlay(value, settings)
  }

  private fun openSource(context: Context, uri: Uri) =
    if (uri.scheme == "file") {
      FileInputStream(uri.path ?: throw IOException("Missing file path"))
    } else {
      context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open $uri")
    }

  private fun sourceUri(source: String): Uri =
    if (source.contains("://")) Uri.parse(source) else Uri.fromFile(File(source))
}
