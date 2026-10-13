package com.memeget.nativeapp.storage

import android.content.Context
import android.net.Uri
import com.memeget.nativeapp.Meme
import com.memeget.nativeapp.VectorRecord
import com.memeget.nativeapp.WorkProgress
import com.memeget.nativeapp.inference.ModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object CollectionZipExporter {
    const val COLLECTION_FORMAT = "memeget-collection"
    const val COLLECTION_VERSION = 1
    const val COLLECTION_MODEL = ModelRegistry.PRIMARY_MODEL_FINGERPRINT
    const val LEGACY_COLLECTION_MODEL = "mobileclip-s2"
    const val COLLECTION_DIM = 512

    suspend fun exportZip(
        context: Context,
        destinationUri: Uri,
        allMemes: Flow<Meme>,
        totalEstimate: Int,
        getPosterBytes: suspend (Meme) -> ByteArray?,
        getVectorRecord: suspend (Long) -> VectorRecord?,
        progress: (WorkProgress) -> Unit
    ) {
        var exportedCount = 0
        val tempSpoolFile = File.createTempFile("col_spool_", ".json", context.cacheDir)
        try {
            progress(WorkProgress("exporting", total = totalEstimate, detail = "Starting export...", running = true))
            val outputStream = context.contentResolver.openOutputStream(destinationUri)
                ?: throw IllegalStateException("Could not open output stream for $destinationUri")
            ZipOutputStream(outputStream).use { zipOut ->
                tempSpoolFile.bufferedWriter(StandardCharsets.UTF_8).use { spoolWriter ->
                    allMemes.collect { meme ->
                        val posterBytes = try {
                            getPosterBytes(meme)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        val imagePathInZip = if (posterBytes != null && posterBytes.isNotEmpty()) "images/${meme.id}.jpg" else null
                        if (imagePathInZip != null) {
                            zipOut.putNextEntry(ZipEntry(imagePathInZip))
                            zipOut.write(posterBytes!!)
                            zipOut.closeEntry()
                        }

                        val vectors = try {
                            getVectorRecord(meme.id)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        val item = JSONObject()
                        item.put("id", meme.id.toString())
                        item.put("file", imagePathInZip ?: JSONObject.NULL)
                        item.put("name", meme.name)
                        item.put("kind", meme.kind)
                        item.put("caption", meme.caption)
                        item.put("ocr", meme.ocr)
                        item.put("transcript", meme.transcript)
                        val tagsArray = JSONArray()
                        for (tag in meme.tags) {
                            tagsArray.put(JSONObject().apply {
                                put("label", tag.label)
                                put("category", tag.category)
                                put("source", tag.source)
                            })
                        }
                        item.put("tags", tagsArray)
                        item.put("extraTerms", meme.extraTerms)
                        if (vectors != null && vectors.image.isNotEmpty()) {
                            val values = JSONArray()
                            for (value in vectors.image) values.put(value.toDouble())
                            item.put("embedding", values)
                        } else {
                            item.put("embedding", JSONObject.NULL)
                        }
                        if (vectors?.caption != null && vectors.caption.isNotEmpty()) {
                            val values = JSONArray()
                            for (value in vectors.caption) values.put(value.toDouble())
                            item.put("captionEmbedding", values)
                        } else {
                            item.put("captionEmbedding", JSONObject.NULL)
                        }
                        item.put("model", vectors?.model ?: "")
                        if (exportedCount > 0) spoolWriter.write(",")
                        spoolWriter.write(item.toString())
                        exportedCount++
                        progress(WorkProgress("exporting", exportedCount, maxOf(totalEstimate, exportedCount), meme.name, running = true))
                    }
                }

                // Stream the manifest without retaining the complete collection in memory.
                zipOut.putNextEntry(ZipEntry("manifest.json"))
                val header = """{"format":"$COLLECTION_FORMAT","version":$COLLECTION_VERSION,"model":"$COLLECTION_MODEL","dim":$COLLECTION_DIM,"exportedAt":${System.currentTimeMillis()},"count":$exportedCount,"memes":["""
                zipOut.write(header.toByteArray(StandardCharsets.UTF_8))
                tempSpoolFile.inputStream().use { it.copyTo(zipOut) }
                zipOut.write("]}".toByteArray(StandardCharsets.UTF_8))
                zipOut.closeEntry()
            }
            // Closing writes the ZIP directory and may fail; success must follow that write.
            progress(WorkProgress("done", exportedCount, exportedCount, "Collection export completed"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            progress(WorkProgress("error", exportedCount, totalEstimate, error.message ?: "Export failed", error = error.message))
            throw error
        } finally {
            tempSpoolFile.delete()
        }
    }
}
