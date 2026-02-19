package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.ConnectionMode
import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.StreamState
import com.lanrhyme.micyou.VideoConfigMessage
import com.lanrhyme.micyou.VideoFrameMessage
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.BindException

class VideoServer(
    private val onVideoConfig: (VideoConfigMessage) -> Unit,
    private val onVideoFrame: (VideoFrameMessage) -> Unit,
    private val requiredTokenProvider: () -> String = { "" }
) {
    private val _state = MutableStateFlow(StreamState.Idle)
    val state = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()
    private val _rttMs = MutableStateFlow<Long?>(null)
    val rttMs = _rttMs.asStateFlow()

    private var serverJob: Job? = null
    private var selectorManager: SelectorManager? = null
    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var activeHandler: VideoConnectionHandler? = null

    suspend fun start(port: Int, mode: ConnectionMode) {
        if (serverJob?.isActive == true) return
        if (mode == ConnectionMode.Bluetooth) {
            _state.value = StreamState.Error
            _lastError.value = "Video over Bluetooth is not supported yet."
            return
        }
        _state.value = StreamState.Connecting
        _lastError.value = null

        coroutineScope {
            serverJob = launch(Dispatchers.IO) {
                try {
                    runTcpServer(port)
                } catch (e: CancellationException) {
                    Logger.d("VideoServer", "Video server coroutine cancelled")
                } catch (e: Exception) {
                    Logger.e("VideoServer", "Fatal server error", e)
                    _state.value = StreamState.Error
                    _lastError.value = "Video server error: ${e.message}"
                } finally {
                    cleanup()
                    if (_state.value != StreamState.Error) _state.value = StreamState.Idle
                }
            }
        }
    }

    suspend fun stop() {
        serverJob?.cancel()
        serverJob?.join()
        cleanup()
    }

    suspend fun testRtt(timeoutMs: Long = 3000L): Long? {
        val handler = activeHandler ?: return null
        val previous = _rttMs.value
        if (!handler.sendRttPing()) return null
        return withTimeoutOrNull(timeoutMs) {
            var result: Long? = null
            while (true) {
                val current = _rttMs.value
                if (current != null && current != previous) {
                    result = current
                    break
                }
                kotlinx.coroutines.delay(20)
            }
            result
        }
    }

    private suspend fun runTcpServer(port: Int) {
        try {
            selectorManager = SelectorManager(Dispatchers.IO)
            serverSocket = aSocket(selectorManager!!).tcp().bind("0.0.0.0", port)
            Logger.i("VideoServer", "Listening video TCP port $port")

            while (currentCoroutineContext().isActive) {
                val socket = serverSocket?.accept() ?: break
                activeSocket = socket
                Logger.i("VideoServer", "Accepted video connection from ${socket.remoteAddress}")
                handleConnection(socket)
            }
        } catch (e: BindException) {
            val msg = "Video port $port is already in use."
            _lastError.value = msg
            _state.value = StreamState.Error
        }
    }

    private suspend fun handleConnection(socket: Socket) {
        _state.value = StreamState.Streaming
        _lastError.value = null
        val handler = VideoConnectionHandler(
            input = socket.openReadChannel(),
            output = socket.openWriteChannel(autoFlush = true),
            onVideoConfig = onVideoConfig,
            onVideoFrame = onVideoFrame,
            onRttMeasured = { _rttMs.value = it },
            onError = { _lastError.value = it },
            requiredTokenProvider = requiredTokenProvider
        )
        activeHandler = handler
        try {
            handler.run()
        } finally {
            activeHandler = null
            runCatching { socket.close() }
            activeSocket = null
            _state.value = StreamState.Connecting
            Logger.i("VideoServer", "Video connection closed")
        }
    }

    private fun cleanup() {
        runCatching {
            activeSocket?.close()
            activeSocket = null
            serverSocket?.close()
            serverSocket = null
            selectorManager?.close()
            selectorManager = null
        }
    }
}
