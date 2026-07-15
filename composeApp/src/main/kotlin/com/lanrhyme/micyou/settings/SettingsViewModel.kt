package com.lanrhyme.micyou.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanrhyme.micyou.theme.PaletteStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val repo = SettingsRepository

    init {
        loadSettings()
    }

    private fun loadSettings() {
        val shouldShowFirstLaunchDialog = !repo.hasLaunchedBefore()
        if (shouldShowFirstLaunchDialog) {
            repo.markLaunched()
        }

        _uiState.update {
            it.copy(
                themeMode = repo.getThemeMode(),
                seedColor = repo.getSeedColor(),
                useDynamicColor = repo.getUseDynamicColor(),
                oledPureBlack = repo.getOledPureBlack(),
                paletteStyle = repo.getPaletteStyle(),
                useExpressiveShapes = repo.getUseExpressiveShapes(),
                language = repo.getLanguage(),
                autoStart = repo.getAutoStart(),
                enableStreamingNotification = repo.getEnableStreamingNotification(),
                keepScreenOn = repo.getKeepScreenOn(),
                visualizerStyle = repo.getVisualizerStyle(),
                backgroundSettings = repo.getBackgroundSettings(),
                autoCheckUpdate = repo.getAutoCheckUpdate(),
                useMirrorDownload = repo.getUseMirrorDownload(),
                mirrorCdk = repo.getMirrorCdk(),
                showFirstLaunchDialog = shouldShowFirstLaunchDialog
            )
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        _uiState.update { it.copy(themeMode = mode) }
        repo.putThemeMode(mode)
    }

    fun setSeedColor(color: Long) {
        _uiState.update { it.copy(seedColor = color) }
        repo.putSeedColor(color)
    }

    fun setUseDynamicColor(enable: Boolean) {
        repo.putUseDynamicColor(enable)
        _uiState.update { it.copy(useDynamicColor = enable) }
    }

    fun setOledPureBlack(enabled: Boolean) {
        _uiState.update { it.copy(oledPureBlack = enabled) }
        repo.putOledPureBlack(enabled)
    }

    fun setPaletteStyle(style: PaletteStyle) {
        _uiState.update { it.copy(paletteStyle = style) }
        repo.putPaletteStyle(style)
    }

    fun setUseExpressiveShapes(enabled: Boolean) {
        _uiState.update { it.copy(useExpressiveShapes = enabled) }
        repo.putUseExpressiveShapes(enabled)
    }

    fun setLanguage(language: AppLanguage) {
        Logger.i("SettingsViewModel", "Setting language to ${language.name}")
        _uiState.update { it.copy(language = language) }
        repo.putLanguage(language)
    }

    fun setAutoStart(enabled: Boolean) {
        _uiState.update { it.copy(autoStart = enabled) }
        repo.putAutoStart(enabled)
    }

    fun setEnableStreamingNotification(enabled: Boolean) {
        _uiState.update { it.copy(enableStreamingNotification = enabled) }
        repo.putEnableStreamingNotification(enabled)
    }

    fun setKeepScreenOn(enabled: Boolean) {
        _uiState.update { it.copy(keepScreenOn = enabled) }
        repo.putKeepScreenOn(enabled)
    }

    fun setVisualizerStyle(style: VisualizerStyle) {
        _uiState.update { it.copy(visualizerStyle = style) }
        repo.putVisualizerStyle(style)
    }

    fun setAutoCheckUpdate(enabled: Boolean) {
        _uiState.update { it.copy(autoCheckUpdate = enabled) }
        repo.putAutoCheckUpdate(enabled)
    }

    fun setUseMirrorDownload(enabled: Boolean) {
        if (enabled) {
            _uiState.update { it.copy(showMirrorCdkDialog = true) }
        } else {
            _uiState.update { it.copy(useMirrorDownload = false) }
            repo.putUseMirrorDownload(false)
        }
    }

    fun setMirrorCdk(cdk: String) {
        _uiState.update { it.copy(mirrorCdk = cdk) }
        repo.putMirrorCdk(cdk)
    }

    fun confirmMirrorCdk(cdk: String) {
        if (cdk.isBlank()) return
        setMirrorCdk(cdk)
        _uiState.update {
            it.copy(useMirrorDownload = true, showMirrorCdkDialog = false)
        }
        repo.putUseMirrorDownload(true)
    }

    fun dismissMirrorCdkDialog() {
        _uiState.update { it.copy(showMirrorCdkDialog = false) }
    }

    fun setBackgroundImage(path: String?) {
        val newSettings = _uiState.value.backgroundSettings.copy(imagePath = path ?: "")
        _uiState.update { it.copy(backgroundSettings = newSettings) }
        repo.putBackgroundImage(path ?: "")
    }

    fun setBackgroundBrightness(brightness: Float) {
        val newSettings = _uiState.value.backgroundSettings.copy(brightness = brightness)
        _uiState.update { it.copy(backgroundSettings = newSettings) }
        repo.putBackgroundBrightness(brightness)
    }

    fun setBackgroundBlur(blurRadius: Float) {
        val newSettings = _uiState.value.backgroundSettings.copy(blurRadius = blurRadius)
        _uiState.update { it.copy(backgroundSettings = newSettings) }
        repo.putBackgroundBlur(blurRadius)
    }

    fun setCardOpacity(opacity: Float) {
        val newSettings = _uiState.value.backgroundSettings.copy(cardOpacity = opacity)
        _uiState.update { it.copy(backgroundSettings = newSettings) }
        repo.putCardOpacity(opacity)
    }

    fun setEnableHazeEffect(enabled: Boolean) {
        val newSettings = _uiState.value.backgroundSettings.copy(enableHazeEffect = enabled)
        _uiState.update { it.copy(backgroundSettings = newSettings) }
        repo.putEnableHazeEffect(enabled)
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
