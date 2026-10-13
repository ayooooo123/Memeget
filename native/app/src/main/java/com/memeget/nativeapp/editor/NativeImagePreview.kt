package com.memeget.nativeapp.editor

import android.content.Context
import android.graphics.Bitmap
import expo.modules.memegetbg.MemeImageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Bounded still preview through the PNG export renderer; caller owns the raster. */
internal object NativeImagePreview {
    private val renderer = Mutex()

    suspend fun render(context: Context, project: MemeEditProject, maxPixels: Long): Bitmap =
        withContext(Dispatchers.Default) {
            require(project.source.kind == MediaEditKind.IMAGE) { "Video preview requires its Media3 composition" }
            renderer.withLock {
                coroutineContext.ensureActive()
                val plan = RenderPlanBuilder.buildImageRenderPlan(
                    project = project,
                    planId = "preview_${System.nanoTime()}",
                    maxOutputPixels = maxPixels.coerceIn(1L, RenderPlanBuilder.MAX_IMAGE_RENDER_PIXELS)
                )
                val bitmap = MemeImageRenderer.renderToBitmap(context, plan.toString())
                try {
                    coroutineContext.ensureActive()
                    bitmap
                } catch (error: Throwable) {
                    bitmap.recycle()
                    throw error
                }
            }
        }
}
