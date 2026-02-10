package com.lanrhyme.androidmic_md

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ConnectionMode {
    Wifi, Usb
}

enum class StreamState {
    Idle, Connecting, Streaming, Error
}

data class AppUiState(
    val mode: ConnectionMode = ConnectionMode.Wifi,
    val streamState: StreamState = StreamState.Idle,
    val ipAddress: String = "192.168.1.5", // 默认 IP
    val port: String = "6000",
    val errorMessage: String? = null
)

class MainViewModel : ViewModel() {
    private val audioEngine = AudioEngine()
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    val audioLevels = audioEngine.audioLevels

    init {
        viewModelScope.launch {
            audioEngine.streamState.collect { state ->
                _uiState.update { it.copy(streamState = state) }
            }
        }
    }

    fun toggleStream() {
        if (_uiState.value.streamState == StreamState.Streaming || _uiState.value.streamState == StreamState.Connecting) {
            stopStream()
        } else {
            startStream()
        }
    }

    private fun startStream() {
        val ip = _uiState.value.ipAddress
        val port = _uiState.value.port.toIntOrNull() ?: 6000
        val mode = _uiState.value.mode
        val isClient = getPlatform().type == PlatformType.Android

        viewModelScope.launch {
            audioEngine.start(ip, port, mode, isClient)
        }
    }

    private fun stopStream() {
        audioEngine.stop()
    }

    fun setMode(mode: ConnectionMode) {
        _uiState.update { it.copy(mode = mode) }
    }
    
    fun setIp(ip: String) {
        _uiState.update { it.copy(ipAddress = ip) }
    }

    fun setPort(port: String) {
        _uiState.update { it.copy(port = port) }
    }
}
