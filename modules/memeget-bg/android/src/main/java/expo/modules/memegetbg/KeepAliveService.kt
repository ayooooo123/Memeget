package expo.modules.memegetbg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

// A minimal foreground service whose only job is to keep the app's process
// alive while it indexes in the background. The actual inference still runs in
// the JS runtime (react-native-executorch); this service + a partial wake lock
// stop the OS from killing that work when the app isn't visible.
//
// NOTE: type `dataSync` foreground services are capped at ~6h / 24h on Android
// 14+, and the budget only resets when the user reopens the app. A fully native
// indexer (calling ExecuTorch's AAR directly) is the longer-term answer.
class KeepAliveService : Service() {
  private var wakeLock: PowerManager.WakeLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Memeget"
    val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Indexing in the background"

    val notification = buildNotification(title, text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }

    if (wakeLock == null) {
      val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "memeget:bg-index").apply {
        setReferenceCounted(false)
        acquire(MAX_WAKE_MS)
      }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
    super.onDestroy()
  }

  private fun buildNotification(title: String, text: String): Notification {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      if (nm.getNotificationChannel(CHANNEL_ID) == null) {
        nm.createNotificationChannel(
          NotificationChannel(CHANNEL_ID, "Background indexing", NotificationManager.IMPORTANCE_LOW)
        )
      }
    }
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle(title)
      .setContentText(text)
      .setSmallIcon(android.R.drawable.ic_menu_search)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
  }

  companion object {
    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
    private const val CHANNEL_ID = "memeget_bg"
    private const val NOTIFICATION_ID = 4242
    private const val MAX_WAKE_MS = 6L * 60L * 60L * 1000L // 6h, matching the FGS cap
  }
}
