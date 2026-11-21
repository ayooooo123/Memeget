package com.memeget.nativeapp.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** Title cards drawn as stills the shape of the edited video, so they fill the frame instead of letterboxing. */
internal object TitleCards {
    private const val LONG_EDGE_PX = 1080

    /** A PNG in editor storage (kept as long as a draft names it) with [text] wrapped, outlined and centred. */
    fun render(context: Context, project: MemeEditProject, text: String, backgroundColor: String): Uri {
        val out = RenderPlanBuilder.calculateOutputSize(project.source.width, project.source.height, project.base)
        val scale = LONG_EDGE_PX.toFloat() / max(out.widthPx, out.heightPx)
        val width = max(2, (out.widthPx * scale).roundToInt())
        val height = max(2, (out.heightPx * scale).roundToInt())
        val background = TextLayoutBridge.parseColor(backgroundColor, Color.BLACK)
        // Light cards get dark text; the outline is always the other one.
        val light = Color.luminance(background) > 0.5f
        val fill = if (light) Color.BLACK else Color.WHITE
        val outline = if (light) Color.WHITE else Color.BLACK

        val style = TextStyle(preset = "impact")
        val textWidth = (width * 0.86f).roundToInt()
        var fontPx = height * 0.12f
        var layout = TextLayoutBridge.measureLayout(context, text, style, fontPx, textWidth).first
        while ((layout.height > height * 0.8f || widestLine(layout) > textWidth) && fontPx > height * 0.03f) {
            fontPx *= 0.9f
            layout = TextLayoutBridge.measureLayout(context, text, style, fontPx, textWidth).first
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(background)
            canvas.translate((width - textWidth) / 2f, (height - layout.height) / 2f)
            val paint = layout.paint
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = fontPx * style.outlineScale * 0.5f
            paint.color = outline
            layout.draw(canvas)
            paint.style = Paint.Style.FILL
            paint.color = fill
            layout.draw(canvas)

            val dir = File(context.filesDir, "editor_assets").apply { mkdirs() }
            val file = File(dir, "card_${UUID.randomUUID()}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return Uri.fromFile(file)
        } finally {
            bitmap.recycle()
        }
    }

    private fun widestLine(layout: android.text.StaticLayout): Float =
        (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0f
}
