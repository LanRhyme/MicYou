/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/LanRhyme/MicYou>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version, with the MicYou Plugin Exception.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

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
import android.app.AlarmManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.runBlocking
import com.lanrhyme.micyou.audio.AudioEngine
import com.lanrhyme.micyou.MainActivity
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
        const val ACTION_START_IDLE = "ACTION_START_IDLE"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_DISCONNECT = "ACTION_DISCONNECT"
        const val EXTRA_USE_WIFI_LOCK = "EXTRA_USE_WIFI_LOCK"
        const val PREFS_NAME = "android_mic_prefs"
        const val KEY_WIFI_LOCK = "audio_wifi_lock"
        const val KEY_STREAMING = "audio_streaming"
        private const val RESTART_DELAY_MS = 5000L
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundService(
                intent.getBooleanExtra(EXTRA_USE_WIFI_LOCK, readWifiLockFlag()),
                streaming = true
            )
            ACTION_START_IDLE -> startForegroundService(useWifiLock = false, streaming = false)
            ACTION_STOP -> enterIdle()
            ACTION_DISCONNECT -> {
                AudioEngine.requestDisconnectFromNotification()
                enterIdle()
            }
            null -> startForegroundService(readWifiLockFlag(), streaming = readStreamingFlag())
        }
        return START_STICKY
    }

    private fun startForegroundService(useWifiLock: Boolean, streaming: Boolean) {
        writeWifiLockFlag(useWifiLock)
        writeStreamingFlag(streaming)
        cancelRestartAlarm()
        if (streaming) {
            acquireSessionLocks(useWifiLock)
        } else {
            releaseSessionLocks()
        }
        val notification = createNotification(streaming)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (streaming) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun enterIdle() {
        writeStreamingFlag(false)
        releaseSessionLocks()
        val notification = createNotification(streaming = false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        scheduleRestart()
    }

    // [API 21+ 兼容] setExactAndAllowWhileIdle() 是 API 23+ 才有的，
    // 低版本用 setExact() 替代（虽不支持空闲时精确唤醒，但兼容模式 targetSdk 29 不需要 Doze 模式适配）。
    private fun scheduleRestart() {
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + RESTART_DELAY_MS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, restartPendingIntent())
        } else {
            alarm.setExact(AlarmManager.RTC_WAKEUP, triggerAt, restartPendingIntent())
        }
    }

    private fun cancelRestartAlarm() {
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(restartPendingIntent())
    }

    // [API 21+ 兼容] PendingIntent.FLAG_IMMUTABLE 是 API 23+ 才有的常量，
    // 低版本只用 FLAG_UPDATE_CURRENT（默认行为即不可变）。
    private fun pendingIntentFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }

    private fun restartPendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            0,
            Intent(this, RestartReceiver::class.java).apply { action = ACTION_START },
            pendingIntentFlags()
        )

    private fun writeWifiLockFlag(useWifiLock: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WIFI_LOCK, useWifiLock)
            .apply()
    }

    private fun readWifiLockFlag(): Boolean =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_WIFI_LOCK, false)

    private fun writeStreamingFlag(streaming: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STREAMING, streaming)
            .apply()
    }

    private fun readStreamingFlag(): Boolean =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_STREAMING, false)

    override fun onDestroy() {
        releaseSessionLocks()
        super.onDestroy()
    }

    private fun createNotification(streaming: Boolean): Notification {
        val (title, text) = if (streaming) {
            resolveNotificationText()
        } else {
            resolveIdleNotificationText()
        }
        val contentIntent = if (streaming) {
            val disconnectIntent = Intent(this, AudioService::class.java).apply { action = ACTION_DISCONNECT }
            PendingIntent.getService(
                this,
                0,
                disconnectIntent,
                pendingIntentFlags()
            )
        } else {
            val openApp = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            PendingIntent.getActivity(
                this,
                1,
                openApp,
                pendingIntentFlags()
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
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

    private fun resolveIdleNotificationText(): Pair<String, String> {
        val selectedLanguage = readSelectedLanguage()
        return when (selectedLanguage) {
            AppLanguage.English -> "MicYou is on" to "Tap to manage"
            AppLanguage.Chinese -> "MicYou 已开启" to "点击管理"
            AppLanguage.ChineseTraditional -> "MicYou 已開啟" to "點擊管理"
            AppLanguage.Cantonese -> "MicYou 開咗" to "撳掣管理"
            else -> getString(R.string.notification_idle_title) to getString(R.string.notification_idle_text)
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
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
