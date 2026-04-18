package com.lanrhyme.micyou

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.content.pm.PackageManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    private var permissionDialogState = mutableStateOf(false)
    private var currentPermissionsState = mutableStateOf<List<PermissionState>>(emptyList())
    private var permissionDialogDismissed = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Update permission states after request
        currentPermissionsState.value = getRequiredPermissions(this)

        // Check if all required permissions are granted
        val allRequiredGranted = hasAllRequiredPermissions(currentPermissionsState.value)
        if (!allRequiredGranted) {
            // Keep showing dialog if required permissions not granted
            permissionDialogState.value = true
        } else {
            permissionDialogState.value = false
            permissionDialogDismissed.value = true
        }
    }

    fun requestPermissions(permissions: List<String>) {
        permissionLauncher.launch(permissions.toTypedArray())
    }

    fun showPermissionDialog() {
        currentPermissionsState.value = getRequiredPermissions(this)
        permissionDialogState.value = true
    }

    fun hidePermissionDialog() {
        permissionDialogState.value = false
        permissionDialogDismissed.value = true
    }

    fun shouldShowPermissionDialog(): Boolean {
        return !hasAllRequiredPermissions(getRequiredPermissions(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        AndroidContext.init(this)
        ContextHelper.init(this)
        Logger.init(AndroidLogger(this))
        Logger.i("MainActivity", "App started")

        BackgroundImagePicker.registerLauncher(this)
        PluginFileChooserHelper.registerLauncher(this)

        val shouldQuickStart = intent?.action == ACTION_QUICK_START

        // Initialize permission state
        currentPermissionsState.value = getRequiredPermissions(this)

        // Show permission dialog first if needed (before first launch dialog)
        val needsPermissions = shouldShowPermissionDialog()
        if (needsPermissions) {
            permissionDialogState.value = true
        } else {
            // No permissions needed, mark as dismissed so first launch can show
            permissionDialogDismissed.value = true
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
                    // Don't auto-start if permissions are missing
                    if (!needsPermissions) {
                        appViewModel.startStream()
                        moveTaskToBack(true)
                    }
                }
            }

            LaunchedEffect(shouldQuickStart, streamState) {
                if (shouldQuickStart && !needsPermissions) {
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

            val themeMode by appViewModel.uiState.collectAsState().let { state ->
                derivedStateOf { state.value.themeMode }
            }
            val isDark = isDarkThemeActive(themeMode)

            DisposableEffect(isDark) {
                this@MainActivity.enableEdgeToEdge(
                    statusBarStyle = if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    },
                    navigationBarStyle = if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    }
                )
                onDispose {}
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

            // Permission dialog state from activity
            val showPermissionDialog by permissionDialogState
            val currentPermissions by currentPermissionsState
            val isPermissionDialogDismissed by permissionDialogDismissed

            App(
                viewModel = appViewModel,
                showPermissionDialog = showPermissionDialog,
                currentPermissions = currentPermissions,
                onRequestPermissions = { perms ->
                    requestPermissions(perms)
                },
                onPermissionDialogDismiss = {
                    hidePermissionDialog()
                },
                isPermissionDialogDismissed = isPermissionDialogDismissed
            )
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
