package com.lanrhyme.micyou

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

class MicYouTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (AudioEngine.isStreaming()) {
            Toast.makeText(this, R.string.qs_toast_stopped, Toast.LENGTH_SHORT).show()
            AudioEngine.requestDisconnectFromNotification()
            updateTile()
        } else {
            Toast.makeText(this, R.string.qs_toast_connecting, Toast.LENGTH_SHORT).show()
            val intent = Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_QUICK_START
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun updateTile() {
        qsTile?.apply {
            state = if (AudioEngine.isStreaming()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
