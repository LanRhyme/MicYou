package com.lanrhyme.micyou

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.content.pm.PackageManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        AndroidContext.init(this)
        ContextHelper.init(this)
        Logger.init(AndroidLogger(this))
        Logger.i("MainActivity", "App started")
        
        BackgroundImagePicker.registerLauncher(this)

        val shouldQuickStart = intent?.action == ACTION_QUICK_START

        val permissionsToRequest = mutableListOf<String>()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 1001)
        }

        setContent {
            val appViewModel: MainViewModel = viewModel()
            val keepScreenOn by appViewModel.uiState.collectAsState().let { state ->
                derivedStateOf { state.value.keepScreenOn }
            }
            val streamState by appViewModel.uiState.collectAsState().let { state ->
                derivedStateOf { state.value.streamState }
            }

            LaunchedEffect(shouldQuickStart) {
                if (shouldQuickStart && appViewModel.uiState.value.streamState == StreamState.Idle) {
                    appViewModel.startStream()
                    moveTaskToBack(true)
                }
            }

            LaunchedEffect(shouldQuickStart, streamState) {
                if (shouldQuickStart) {
                    when (streamState) {
                        StreamState.Streaming -> {
                            Toast.makeText(this@MainActivity, R.string.qs_toast_connected, Toast.LENGTH_SHORT).show()
                        }
                        StreamState.Error -> {
                            Toast.makeText(this@MainActivity, R.string.qs_toast_failed, Toast.LENGTH_SHORT).show()
                        }
                        else -> {}
                    }
                }
            }

            DisposableEffect(keepScreenOn) {
                if (keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }

                onDispose {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            App(viewModel = appViewModel)
        }
    }

    companion object {
        const val ACTION_QUICK_START = "com.lanrhyme.micyou.ACTION_QUICK_START"
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
