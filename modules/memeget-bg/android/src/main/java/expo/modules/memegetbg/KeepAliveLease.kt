package expo.modules.memegetbg

import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.CopyOnWriteArrayList

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
  /** Android ended the service before the last holder let go; the next [acquire] tries again. */
  private var lost = false
  private val lostListeners = CopyOnWriteArrayList<() -> Unit>()

  /** Start (or refresh) the foreground service and take a lease on it. */
  fun acquire(context: Context, title: String, text: String, progress: Int = -1, total: Int = -1) {
    synchronized(lock) {
      holders += 1
      lost = false
    }
    send(context, intent(context, title, text, progress, total))
  }

  /** Update the notification without changing the lease count. Ignored when nobody holds one or it was lost. */
  fun update(context: Context, title: String, text: String, progress: Int = -1, total: Int = -1) {
    synchronized(lock) { if (holders <= 0 || lost) return }
    send(context, intent(context, title, text, progress, total))
  }

  /** Release one lease; the service stops only when the last holder lets go. */
  fun release(context: Context) {
    val stop = synchronized(lock) {
      if (holders > 0) holders -= 1
      // A lost service has already stopped itself; starting it again only to stop it could be refused.
      holders == 0 && !lost
    }
    if (!stop) return
    // Deliberately NOT stopService: a lease taken and released faster than Android creates the
    // service leaves the startForegroundService promise unkept, and the system kills the whole
    // process for it (ForegroundServiceDidNotStartInTimeException, seen on a Pixel when an export
    // was cancelled a beat after it started). Service intents are ordered, so a stop sent the
    // same way always arrives after the start it is undoing.
    send(context, intent(context, "Memeget", "Finishing up", -1, -1).setAction(KeepAliveService.ACTION_STOP))
  }

  /**
   * Called when Android takes the service away while leases are held: on Android 15 and later a
   * `dataSync` service gets six hours in the background per day, after which it must stop, and a
   * new start is refused until the app is opened again. Holders whose work needs the service
   * should wind it down and leave the rest to work that does not.
   */
  fun addLostListener(listener: () -> Unit) {
    lostListeners += listener
  }

  fun removeLostListener(listener: () -> Unit) {
    lostListeners -= listener
  }

  /** The service has stopped itself early; see [addLostListener]. */
  internal fun markLost() {
    synchronized(lock) { lost = true }
    lostListeners.forEach { it() }
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
    // A foreground service start can be refused outright - background start restrictions, the
    // six-hour dataSync budget, or a user who revoked notifications. That loses the keep-alive,
    // never the work it was protecting: holders hear about it (see addLostListener) and decide.
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }.onFailure {
      if (intent.action != KeepAliveService.ACTION_STOP) markLost()
    }
  }
}
