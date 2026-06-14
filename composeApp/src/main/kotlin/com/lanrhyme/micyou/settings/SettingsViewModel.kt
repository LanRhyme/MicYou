package com.lanrhyme.micyou.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanrhyme.micyou.theme.PaletteStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.lanrhyme.micyou.settings.Settings
import com.lanrhyme.micyou.settings.SettingsFactory
import com.lanrhyme.micyou.settings.SettingsUiState
import com.lanrhyme.micyou.settings.SettingsViewModel
import com.lanrhyme.micyou.theme.ThemeMode
import com.lanrhyme.micyou.ui.background.BackgroundImagePicker
import com.lanrhyme.micyou.ui.background.BackgroundSettings
import com.lanrhyme.micyou.util.AppLanguage
import com.lanrhyme.micyou.util.Logger
import com.lanrhyme.micyou.viewmodel.VisualizerStyle
data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.System,
    val seedColor: Long = 0xFF4A672D,
    val useDynamicColor: Boolean = false,
    val oledPureBlack: Boolean = false,
    val paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    val useExpressiveShapes: Boolean = true,
    val language: AppLanguage = AppLanguage.System,
    val autoStart: Boolean = false,
    val enableStreamingNotification: Boolean = true,
    val keepScreenOn: Boolean = false,
    val autoCheckUpdate: Boolean = true,
    val useMirrorDownload: Boolean = false,
    val mirrorCdk: String = "",
    val visualizerStyle: VisualizerStyle = VisualizerStyle.VolumeRing,
    val backgroundSettings: BackgroundSettings = BackgroundSettings(),
    val snackbarMessage: String? = null,
    val showFirstLaunchDialog: Boolean = false,
    val showMirrorCdkDialog: Boolean = false
)

class SettingsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private val settings = SettingsFactory.getSettings()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        val savedThemeModeName = settings.getString("theme_mode", ThemeMode.System.name)
    val savedThemeMode = try { ThemeMode.valueOf(savedThemeModeName) } catch(e: Exception) { ThemeMode.System }
    val savedSeedColor = settings.getLong("seed_color", 0xFF4A672D)
    val savedUseDynamicColor = settings.getBoolean("use_dynamic_color", false)
    val savedOledPureBlack = settings.getBoolean("oled_pure_black", false)
    val savedPaletteStyleName = settings.getString("palette_style", PaletteStyle.TonalSpot.name)
    val savedPaletteStyle = try { PaletteStyle.valueOf(savedPaletteStyleName) } catch(e: Exception) { PaletteStyle.TonalSpot }
    val savedUseExpressiveShapes = settings.getBoolean("use_expressive_shapes", true)
    val initialLanguage = try { 
            AppLanguage.valueOf(settings.getString("language", AppLanguage.System.name)) 
        } catch(e: Exception) { 
            AppLanguage.System 
        }
    val savedAutoStart = settings.getBoolean("auto_start", false)
    val savedEnableStreamingNotification = settings.getBoolean("enable_streaming_notification", true)
    val savedKeepScreenOn = settings.getBoolean("keep_screen_on", false)
    val savedVisualizerStyleName = settings.getString("visualizer_style", VisualizerStyle.VolumeRing.name)
    val savedVisualizerStyle = try {
            VisualizerStyle.valueOf(savedVisualizerStyleName)
        } catch (e: Exception) {
            VisualizerStyle.VolumeRing
        }
    val savedBackgroundImagePath = settings.getString("background_image_path", "")
    val savedBackgroundBrightness = settings.getFloat("background_brightness", 0.5f)
    val savedBackgroundBlur = settings.getFloat("background_blur", 0f)
    val savedCardOpacity = settings.getFloat("card_opacity", 1f)
    val savedEnableHazeEffect = settings.getBoolean("enable_haze_effect", false)
    val savedAutoCheckUpdate = settings.getBoolean("auto_check_update", true)
    val savedUseMirrorDownload = settings.getBoolean("use_mirror_download", false)
    val savedMirrorCdk = settings.getString("mirror_cdk", "")
    val hasLaunchedBefore = settings.getBoolean("has_launched_before", false)
    val shouldShowFirstLaunchDialog = !hasLaunchedBefore
        if (shouldShowFirstLaunchDialog) {
            settings.putBoolean("has_launched_before", true)
        }

        _uiState.update {
            it.copy(
                themeMode = savedThemeMode,
                seedColor = savedSeedColor,
                useDynamicColor = savedUseDynamicColor,
                oledPureBlack = savedOledPureBlack,
                paletteStyle = savedPaletteStyle,
                useExpressiveShapes = savedUseExpressiveShapes,
                language = initialLanguage,
                autoStart = savedAutoStart,
                enableStreamingNotification = savedEnableStreamingNotification,
                keepScreenOn = savedKeepScreenOn,
                visualizerStyle = savedVisualizerStyle,
                backgroundSettings = BackgroundSettings(
                    imagePath = savedBackgroundImagePath,
                    brightness = savedBackgroundBrightness,
                    blurRadius = savedBackgroundBlur,
                    cardOpacity = savedCardOpacity,
                    enableHazeEffect = savedEnableHazeEffect
                ),
                autoCheckUpdate = savedAutoCheckUpdate,
                useMirrorDownload = savedUseMirrorDownload,
                mirrorCdk = savedMirrorCdk,
                showFirstLaunchDialog = shouldShowFirstLaunchDialog
            ) 
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        _uiState.update { it.copy(themeMode = mode) }
        settings.putString("theme_mode", mode.name)
    }

    fun setSeedColor(color: Long) {
        _uiState.update { it.copy(seedColor = color) }
        settings.putLong("seed_color", color)
    }

    fun setUseDynamicColor(enable: Boolean) {
        settings.putBoolean("use_dynamic_color", enable)
        _uiState.update { it.copy(useDynamicColor = enable) }
    }

    fun setOledPureBlack(enabled: Boolean) {
        _uiState.update { it.copy(oledPureBlack = enabled) }
        settings.putBoolean("oled_pure_black", enabled)
    }

    fun setPaletteStyle(style: PaletteStyle) {
        _uiState.update { it.copy(paletteStyle = style) }
        settings.putString("palette_style", style.name)
    }

    fun setUseExpressiveShapes(enabled: Boolean) {
        _uiState.update { it.copy(useExpressiveShapes = enabled) }
        settings.putBoolean("use_expressive_shapes", enabled)
    }

    fun setLanguage(language: AppLanguage) {
        Logger.i("SettingsViewModel", "Setting language to ${language.name}")
        _uiState.update { it.copy(language = language) }
        settings.putString("language", language.name)
    }

    fun setAutoStart(enabled: Boolean) {
        _uiState.update { it.copy(autoStart = enabled) }
        settings.putBoolean("auto_start", enabled)
    }

    fun setEnableStreamingNotification(enabled: Boolean) {
        _uiState.update { it.copy(enableStreamingNotification = enabled) }
        settings.putBoolean("enable_streaming_notification", enabled)
    }

    fun setKeepScreenOn(enabled: Boolean) {
        _uiState.update { it.copy(keepScreenOn = enabled) }
        settings.putBoolean("keep_screen_on", enabled)
    }

    fun setVisualizerStyle(style: VisualizerStyle) {
        _uiState.update { it.copy(visualizerStyle = style) }
        settings.putString("visualizer_style", style.name)
    }

    fun setAutoCheckUpdate(enabled: Boolean) {
        _uiState.update { it.copy(autoCheckUpdate = enabled) }
        settings.putBoolean("auto_check_update", enabled)
    }

    fun setUseMirrorDownload(enabled: Boolean) {
        if (enabled) {
            // Show dialog first, don't change the state yet
            // User needs to confirm with CDK before enabling
            _uiState.update { it.copy(showMirrorCdkDialog = true) }
        } else {
            _uiState.update { it.copy(useMirrorDownload = false) }
            settings.putBoolean("use_mirror_download", false)
        }
    }

    fun setMirrorCdk(cdk: String) {
        _uiState.update { it.copy(mirrorCdk = cdk) }
        settings.putString("mirror_cdk", cdk)
    }

    fun confirmMirrorCdk(cdk: String) {
        if (cdk.isBlank()) return
        setMirrorCdk(cdk)
        _uiState.update {
            it.copy(
                useMirrorDownload = true,
                showMirrorCdkDialog = false
            )
        }
        settings.putBoolean("use_mirror_download", true)
    }

    fun dismissMirrorCdkDialog() {
        _uiState.update { it.copy(showMirrorCdkDialog = false) }
    }

    fun setBackgroundImage(path: String?) {
        val newSettings = _uiState.value.backgroundSettings.copy(imagePath = path ?: "")
        _uiState.update { it.copy(backgroundSettings = newSettings) }
        settings.putString("background_image_path", path ?: "")
    }
    
    fun setBackgroundBrightness(brightness: Float) {
        val newSettings = _uiState.value.backgroundSettings.copy(brightness = brightness)
        _uiState.update { it.copy(backgroundSettings = newSettings) }
        settings.putFloat("background_brightness", brightness)
    }
    
    fun setBackgroundBlur(blurRadius: Float) {
        val newSettings = _uiState.value.backgroundSettings.copy(blurRadius = blurRadius)
        _uiState.update { it.copy(backgroundSettings = newSettings) }
        settings.putFloat("background_blur", blurRadius)
    }
    
    fun setCardOpacity(opacity: Float) {
        val newSettings = _uiState.value.backgroundSettings.copy(cardOpacity = opacity)
        _uiState.update { it.copy(backgroundSettings = newSettings) }
        settings.putFloat("card_opacity", opacity)
    }
    
    fun setEnableHazeEffect(enabled: Boolean) {
        val newSettings = _uiState.value.backgroundSettings.copy(enableHazeEffect = enabled)
        _uiState.update { it.copy(backgroundSettings = newSettings) }
        settings.putBoolean("enable_haze_effect", enabled)
    }
    
    fun clearBackgroundImage() {
        setBackgroundImage("")
    }
    
    fun pickBackgroundImage() {
        BackgroundImagePicker.pickImage(viewModelScope) { path ->
            path?.let { setBackgroundImage(it) }
        }
    }

    fun showSnackbar(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun dismissFirstLaunchDialog() {
        _uiState.update { it.copy(showFirstLaunchDialog = false) }
    }

    fun exportLog(onResult: (String?) -> Unit) {
        val path = Logger.getLogFilePath()
        onResult(path)
    }
}