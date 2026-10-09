package dev.ravindran.ythub.mobile

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var monitoring = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); channels(this) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(7, notification("Preparing your downloads"))
        DownloadStore.startQueue()
        if (!monitoring) {
            monitoring = true
            scope.launch {
                while (DownloadStore.hasWork()) {
                    val active = DownloadStore.items.value.count { it.working }
                    getSystemService(NotificationManager::class.java).notify(7, notification("$active active · Download. Convert. Manage."))
                    delay(1500)
                }
                monitoring = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(text: String) = NotificationCompat.Builder(this, "downloads")
        .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("YTHub")
        .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openIntent(this)).build()
    override fun onTimeout(startId: Int, fgsType: Int) {
        DownloadStore.items.value.filter { it.working || it.state == "Queued" }.forEach { DownloadStore.pause(it.id) }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        fun channels(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
                NotificationChannel("downloads", "Active downloads", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel("completed", "Download results", NotificationManager.IMPORTANCE_DEFAULT)))
        }
        private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun notifyFinished(context: Context, item: DownloadItem) {
            if (!DownloadStore.prefs.value.notifications) return
            channels(context)
            runCatching { context.getSystemService(NotificationManager::class.java).notify(item.id.hashCode(), NotificationCompat.Builder(context, "completed")
                .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Download complete")
                .setContentText(item.media.title).setContentIntent(openIntent(context)).setAutoCancel(true).build()) }
        }
    }
}
