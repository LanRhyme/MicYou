/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/MicYou-Dev/MicYou>
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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(AudioService.PREFS_NAME, Context.MODE_PRIVATE)
        val useWifiLock = prefs.getBoolean(AudioService.KEY_WIFI_LOCK, false)
        val streaming = prefs.getBoolean(AudioService.KEY_STREAMING, false)
        val service = Intent(context, AudioService::class.java).apply {
            action = if (streaming) {
                AudioService.ACTION_START
            } else {
                AudioService.ACTION_START_IDLE
            }
            putExtra(AudioService.EXTRA_USE_WIFI_LOCK, useWifiLock)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        }
    }
}