package com.alizz.filemanager.server

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
import com.alizz.filemanager.MainActivity
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

const val ACTION_START_SERVER = "com.alizz.filemanager.server.START"
const val ACTION_STOP_SERVER = "com.alizz.filemanager.server.STOP"
const val EXTRA_ROOT = "root"
const val EXTRA_PORT = "port"
const val EXTRA_PASSWORD = "password"

private const val CHANNEL_ID = "file_server"
private const val NOTIF_ID = 41

object ServerStatus {
    @Volatile var running: Boolean = false
    @Volatile var url: String = ""
    @Volatile var rootPath: String = ""
    @Volatile var authRequired: Boolean = false
}

/** Foreground HTTP file server. Serves one local folder on the LAN. */
class FileServerService : Service() {
    private var server: HttpFileServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVER -> {
                stopServer()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val root = intent?.getStringExtra(EXTRA_ROOT)?.let(::File)
                val port = intent?.getIntExtra(EXTRA_PORT, 8080) ?: 8080
                val password = intent?.getStringExtra(EXTRA_PASSWORD).orEmpty()
                if (root == null || !root.isDirectory) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundCompat(notification("Starting…"))
                stopServer()
                try {
                    val s = HttpFileServer(root, port, password)
                    s.start()
                    server = s
                    val lan = lanAddress() ?: "localhost"
                    ServerStatus.running = true
                    ServerStatus.url = "http://$lan:$port/"
                    ServerStatus.rootPath = root.absolutePath
                    ServerStatus.authRequired = password.isNotEmpty()
                    startForegroundCompat(notification(ServerStatus.url))
                } catch (e: Exception) {
                    ServerStatus.running = false
                    ServerStatus.url = ""
                    stopSelf()
                }
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopServer()
    }

    private fun stopServer() {
        try {
            server?.stop()
        } catch (e: Exception) {
        }
        server = null
        ServerStatus.running = false
        ServerStatus.url = ""
        ServerStatus.rootPath = ""
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            androidx.core.app.ServiceCompat.startForeground(
                this, NOTIF_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "File server", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("File server running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun lanAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
