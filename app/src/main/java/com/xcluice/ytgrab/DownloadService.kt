package com.xcluice.ytgrab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DownloadService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var obs: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun nm() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun open() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun build(s: JobState): Notification =
        NotificationCompat.Builder(this, "dl")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(s.title.ifEmpty { "Ytgrab" })
            .setContentText(s.phase)
            .setProgress(100, (s.progress * 100).toInt(), s.progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open())
            .build()

    private fun result(title: String, text: String) {
        nm().notify(
            2,
            NotificationCompat.Builder(this, "dl")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(open())
                .build(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        nm().createNotificationChannel(NotificationChannel("dl", "Downloads", NotificationManager.IMPORTANCE_LOW))
        val first = build(Engine.job.value)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, first)
        }
        obs?.cancel()
        obs = scope.launch {
            Engine.job.collect { s ->
                if (s.active) {
                    nm().notify(1, build(s))
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    when {
                        s.finished -> result("Download complete", s.title)
                        s.error != null -> result("Download failed", s.error)
                    }
                    stopSelf()
                    scope.cancel()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
