package expo.modules.memegetbg

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.IOException

class StreamingProbeProvider : ContentProvider() {
  override fun onCreate(): Boolean = true

  override fun getType(uri: Uri): String = "image/jpeg"

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    val pipe = ParcelFileDescriptor.createPipe()
    Thread(
      {
        try {
          ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
            checkNotNull(context).assets.open("probe_orientation_2.jpg").use { input ->
              input.copyTo(output)
            }
          }
        } catch (_: IOException) {
          // Metadata/seekability probes intentionally open and close without
          // consuming the stream, so the writer can observe EPIPE.
        }
      },
      "streaming-probe-provider"
    ).start()
    return pipe[0]
  }

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?
  ): Cursor? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?
  ): Int = 0
}
