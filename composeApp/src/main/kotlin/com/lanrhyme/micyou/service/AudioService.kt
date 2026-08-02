package com.lanrhyme.micyou.service
import com.lanrhyme.micyou.R

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
import android.net.wifi.WifiManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.runBlocking
import com.lanrhyme.micyou.audio.AudioEngine
import com.lanrhyme.micyou.service.AudioService
import com.lanrhyme.micyou.util.AppLanguage
import com.lanrhyme.micyou.util.getString
class AudioService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        private const val CHANNEL_ID = "AudioServiceChannel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_DISCONNECT = "ACTION_DISCONNECT"
        const val EXTRA_USE_WIFI_LOCK = "EXTRA_USE_WIFI_LOCK"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundService(intent.getBooleanExtra(EXTRA_USE_WIFI_LOCK, false))
            ACTION_STOP -> stopForegroundService()
            ACTION_DISCONNECT -> {
                AudioEngine.requestDisconnectFromNotification()
                stopForegroundService()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundService(useWifiLock: Boolean) {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireSessionLocks(useWifiLock)
    }

    private fun acquireSessionLocks(useWifiLock: Boolean) {
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:audio-stream"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (useWifiLock && wifiLock?.isHeld != true) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "$packageName:audio-stream"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } else if (!useWifiLock) {
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        }
    }

    private fun releaseSessionLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun stopForegroundService() {
        releaseSessionLocks()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        releaseSessionLocks()
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        val disconnectIntent = Intent(this, AudioService::class.java).apply { action = ACTION_DISCONNECT }
    val disconnectPendingIntent = PendingIntent.getService(
            this,
            0,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    val (title, text) = resolveNotificationText()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(disconnectPendingIntent)
            .build()
    }

    private fun resolveNotificationText(): Pair<String, String> {
        val selectedLanguage = readSelectedLanguage()
        return when (selectedLanguage) {
            AppLanguage.English -> "MicYou Streaming" to "Tap to disconnect"
            AppLanguage.Chinese -> "MicYou 正在传输" to "点击断开连接"
            AppLanguage.ChineseTraditional -> "MicYou 正在傳輸" to "點擊中斷連線"
            AppLanguage.Cantonese -> "MicYou 傳輸緊" to "撳掣斷開連線"
            else -> getString(R.string.streaming_notification_title) to getString(R.string.streaming_notification_text)
        }
    }

    private fun readSelectedLanguage(): AppLanguage {
        val prefs = getSharedPreferences("android_mic_prefs", Context.MODE_PRIVATE)
    val saved = prefs.getString("language", AppLanguage.System.name)
        return try {
            AppLanguage.valueOf(saved ?: AppLanguage.System.name)
        } catch (_: Exception) {
            AppLanguage.System
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = runBlocking { getString(R.string.audioStreamingService) }
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
