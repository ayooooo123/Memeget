package expo.modules.memegetbg

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Reference counting in front of [KeepAliveService].
 *
 * The service is a singleton with one notification, and both the JS indexer and the native video
 * exporter want to hold it at the same time. Without a count, whichever finishes first calls stop
 * and kills the other one's lease: a cancelled export would take the background indexer's
 * foreground service down with it, and a finished index would drop the export's wake lock while
 * the encoder still needs the CPU.
 *
 * Counted here rather than inside the service because stopping is not a countable event - it just
 * tears the service down.
 */
object KeepAliveLease {
  private val lock = Any()
  private var holders = 0

  /** Start (or refresh) the foreground service and take a lease on it. */
  fun acquire(context: Context, title: String, text: String, progress: Int = -1, total: Int = -1) {
    synchronized(lock) { holders += 1 }
    send(context, intent(context, title, text, progress, total))
  }

  /** Update the notification without changing the lease count. Ignored when nobody holds one. */
  fun update(context: Context, title: String, text: String, progress: Int = -1, total: Int = -1) {
    synchronized(lock) { if (holders <= 0) return }
    send(context, intent(context, title, text, progress, total))
  }

  /** Release one lease; the service stops only when the last holder lets go. */
  fun release(context: Context) {
    val stop = synchronized(lock) {
      if (holders > 0) holders -= 1
      holders == 0
    }
    if (!stop) return
    // Deliberately NOT stopService: a lease taken and released faster than Android creates the
    // service leaves the startForegroundService promise unkept, and the system kills the whole
    // process for it (ForegroundServiceDidNotStartInTimeException, seen on a Pixel when an export
    // was cancelled a beat after it started). Service intents are ordered, so a stop sent the
    // same way always arrives after the start it is undoing.
    send(context, intent(context, "Memeget", "Finishing up", -1, -1).setAction(KeepAliveService.ACTION_STOP))
  }

  /** How many holders the lease has right now. Test hook: a leak is invisible otherwise. */
  fun holderCount(): Int = synchronized(lock) { holders }

  private fun intent(context: Context, title: String, text: String, progress: Int, total: Int) =
    Intent(context, KeepAliveService::class.java).apply {
      putExtra(KeepAliveService.EXTRA_TITLE, title)
      putExtra(KeepAliveService.EXTRA_TEXT, text)
      putExtra(KeepAliveService.EXTRA_PROGRESS, progress)
      putExtra(KeepAliveService.EXTRA_MAX, total)
    }

  private fun send(context: Context, intent: Intent) {
    // A foreground service start can be refused outright - background start restrictions, or a
    // user who revoked notifications. That is a reason to lose the keep-alive, never a reason to
    // lose the work it was protecting.
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }
  }
}
