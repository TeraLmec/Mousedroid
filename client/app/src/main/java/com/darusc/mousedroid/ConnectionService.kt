package com.darusc.mousedroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.darusc.mousedroid.networking.Connection
import com.darusc.mousedroid.networking.ConnectionManager

class ConnectionService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        BatteryMonitor.getInstance().start(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            ConnectionManager.getInstance().disconnect()
            stopSelf()
            return START_NOT_STICKY
        }

        val mode = intent?.getStringExtra(EXTRA_MODE) ?: "Connected"
        val host = intent?.getStringExtra(EXTRA_HOST) ?: "Mousedroid server"
        startForeground(NOTIFICATION_ID, buildNotification(mode, host))
        return START_STICKY
    }

    override fun onDestroy() {
        BatteryMonitor.getInstance().stop(applicationContext)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(mode: String, host: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ConnectionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_connection_title))
            .setContentText("$mode connection to $host")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.disconnect), disconnectIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_connections),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "mousedroid_connection"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_DISCONNECT = "com.darusc.mousedroid.DISCONNECT"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_HOST = "host"

        fun start(context: Context, mode: Connection.Mode, hostName: String) {
            val intent = Intent(context, ConnectionService::class.java).apply {
                putExtra(EXTRA_MODE, mode.name)
                putExtra(EXTRA_HOST, hostName)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
