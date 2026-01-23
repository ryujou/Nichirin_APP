package com.example.nichirin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ForegroundKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                return START_STICKY
            }
        }
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("正在发送/播放")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            "后台运行",
            NotificationManager.IMPORTANCE_LOW
        )
        mgr.createNotificationChannel(ch)
    }

    companion object {
        private const val CHANNEL_ID = "nichirin_keep_alive"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.example.nichirin.action.STOP_FG"

        fun start(context: Context) {
            val i = Intent(context, ForegroundKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, ForegroundKeepAliveService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(i)
        }
    }
}
