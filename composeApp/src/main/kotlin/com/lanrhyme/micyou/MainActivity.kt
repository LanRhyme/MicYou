package com.lanrhyme.micyou
import com.lanrhyme.micyou.R

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.view.WindowManager
import android.widget.Toast
import java.util.Locale
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
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init
import com.lanrhyme.micyou.settings.Settings
import com.lanrhyme.micyou.theme.isDarkThemeActive
import com.lanrhyme.micyou.theme.ThemeMode
import com.lanrhyme.micyou.util.AppLanguage
import com.lanrhyme.micyou.util.ContextHelper
import com.lanrhyme.micyou.util.Logger
import com.lanrhyme.micyou.util.PermissionState
import com.lanrhyme.micyou.ui.dialog.getRequiredPermissions
import com.lanrhyme.micyou.ui.dialog.hasAllRequiredPermissions
import com.lanrhyme.micyou.util.setAppLocale
import com.lanrhyme.micyou.viewmodel.MainViewModel
import com.lanrhyme.micyou.viewmodel.StreamState

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

    override fun attachBaseContext(newBase: Context) {
        val savedLanguage = try {
            val prefs = newBase.getSharedPreferences("android_mic_prefs", Context.MODE_PRIVATE)
            val raw = prefs.getString("language", null)
            if (raw != null) {
                try {
                    AppLanguage.valueOf(raw).code
                } catch (_: Exception) {
                    raw
                }
            } else {
                "system"
            }
        } catch (e: Exception) {
            "system"
        }
        val wrappedContext = if (savedLanguage != "system") {
            wrapContextWithLocale(newBase, savedLanguage)
        } else {
            newBase
        }
        super.attachBaseContext(wrappedContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        ContextHelper.init(this)

        // Initialize locale from saved settings
        val savedLanguage = try {
            val prefs = getSharedPreferences("android_mic_prefs", MODE_PRIVATE)
            val raw = prefs.getString("language", null)
            if (raw != null) {
                // Saved value is AppLanguage.name, convert to AppLanguage.code
                try {
                    AppLanguage.valueOf(raw).code
                } catch (_: Exception) {
                    // Fallback: if the raw value looks like a code already, use it
                    raw
                }
            } else {
                "system"
            }
        } catch (e: Exception) {
            "system"
        }
        setAppLocale(savedLanguage)

        Logger.init(this)
        Logger.i("MainActivity", "App started")

        FileKit.init(this)
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
                activity = this@MainActivity,
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

        fun wrapContextWithLocale(context: Context, languageCode: String): Context {
            val locale = try {
                val parts = if (languageCode.contains("-r")) {
                    languageCode.split("-r", limit = 2)
                } else if (languageCode.contains("-")) {
                    languageCode.split("-", limit = 2)
                } else {
                    null
                }
                if (parts != null && parts.size == 2) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        Locale.Builder().setLanguage(parts[0]).setRegion(parts[1]).build()
                    } else {
                        @Suppress("DEPRECATION")
                        Locale(parts[0], parts[1])
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        Locale.Builder().setLanguage(languageCode).build()
                    } else {
                        @Suppress("DEPRECATION")
                        Locale(languageCode)
                    }
                }
            } catch (e: Exception) {
                Locale.getDefault()
            }
            val config = Configuration(context.resources.configuration)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                config.setLocale(locale)
            } else {
                @Suppress("DEPRECATION")
                config.locale = locale
            }
            return context.createConfigurationContext(config)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}