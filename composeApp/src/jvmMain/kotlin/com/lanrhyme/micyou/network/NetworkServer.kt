package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.AudioPacketMessage
import com.lanrhyme.micyou.ConnectionMode
import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.StreamState
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.BindException
import javax.bluetooth.DiscoveryAgent
import javax.bluetooth.LocalDevice
import javax.bluetooth.UUID
import javax.microedition.io.Connector
import javax.microedition.io.StreamConnection
import javax.microedition.io.StreamConnectionNotifier

class NetworkServer(
    private val onAudioPacketReceived: suspend (AudioPacketMessage) -> Unit,
    private val onMuteStateChanged: (Boolean) -> Unit,
    private val requiredTokenProvider: () -> String = { "" }
) {
    private val _state = MutableStateFlow(StreamState.Idle)
    val state = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()

    private var serverJob: Job? = null
    private var selectorManager: SelectorManager? = null
    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var btNotifier: StreamConnectionNotifier? = null
    private var activeBtConnection: StreamConnection? = null
    private var activeHandler: ConnectionHandler? = null
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun start(port: Int, mode: ConnectionMode) {
        if (serverJob?.isActive == true) {
            Logger.w("NetworkServer", "Server is already running")
            return
        }

        _state.value = StreamState.Connecting
        _lastError.value = null

        coroutineScope {
            serverJob = launch(Dispatchers.IO) {
                try {
                    if (mode == ConnectionMode.Bluetooth) runBluetoothServer() else runTcpServer(port)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e("NetworkServer", "Fatal server error", e)
                    _state.value = StreamState.Error
                    _lastError.value = "Server error: ${e.message}"
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

    suspend fun sendMuteState(muted: Boolean) {
        activeHandler?.sendMuteState(muted)
    }

    private suspend fun runTcpServer(port: Int) {
        try {
            selectorManager = SelectorManager(Dispatchers.IO)
            serverSocket = aSocket(selectorManager!!).tcp().bind("0.0.0.0", port)
            Logger.i("NetworkServer", "Listening on TCP port $port")

            while (currentCoroutineContext().isActive) {
                val socket = serverSocket?.accept() ?: break
                activeSocket = socket
                Logger.i("NetworkServer", "Accepted TCP connection from ${socket.remoteAddress}")
                handleConnection(
                    input = socket.openReadChannel(),
                    output = socket.openWriteChannel(autoFlush = true),
                    closeAction = {
                        socket.close()
                        activeSocket = null
                    }
                )
            }
        } catch (e: BindException) {
            val msg = "Port $port is already in use."
            Logger.e("NetworkServer", msg)
            _lastError.value = msg
            _state.value = StreamState.Error
        }
    }

    private suspend fun runBluetoothServer() {
        while (currentCoroutineContext().isActive) {
            try {
                val localDevice = LocalDevice.getLocalDevice()
                localDevice.discoverable = DiscoveryAgent.GIAC
                val uuid = UUID("0000110100001000800000805F9B34FB", false)
                val url = "btspp://localhost:$uuid;name=MicYouServer"
                btNotifier = Connector.open(url) as StreamConnectionNotifier
                Logger.i("NetworkServer", "Bluetooth server started: $url")

                while (currentCoroutineContext().isActive) {
                    val connection = btNotifier?.acceptAndOpen() ?: break
                    activeBtConnection = connection
                    Logger.i("NetworkServer", "Accepted Bluetooth connection")

                    val input = connection.openInputStream().toByteReadChannel()
                    val output = connection.openOutputStream().toManagedByteWriteChannel(bridgeScope)
                    handleConnection(
                        input = input,
                        output = output,
                        closeAction = {
                            connection.close()
                            activeBtConnection = null
                        }
                    )
                }
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    Logger.e("NetworkServer", "Bluetooth server error", e)
                    if (_state.value != StreamState.Connecting) {
                        _state.value = StreamState.Error
                        _lastError.value = "Bluetooth error: ${e.message}"
                        delay(5000)
                        _state.value = StreamState.Connecting
                    }
                }
            }
        }
    }

    private suspend fun handleConnection(
        input: ByteReadChannel,
        output: ByteWriteChannel,
        closeAction: suspend () -> Unit
    ) {
        _state.value = StreamState.Streaming
        _lastError.value = null

        val handler = ConnectionHandler(
            input = input,
            output = output,
            onAudioPacketReceived = onAudioPacketReceived,
            onMuteStateChanged = onMuteStateChanged,
            onError = { _lastError.value = it },
            requiredTokenProvider = requiredTokenProvider
        )
        activeHandler = handler

        try {
            handler.run()
        } finally {
            activeHandler = null
            closeAction()
            _state.value = StreamState.Connecting
            Logger.i("NetworkServer", "Connection closed")
        }
    }

    private fun cleanup() {
        try {
            activeSocket?.close()
            activeSocket = null
            serverSocket?.close()
            serverSocket = null
            btNotifier?.close()
            btNotifier = null
            activeBtConnection?.close()
            activeBtConnection = null
            selectorManager?.close()
            selectorManager = null
            bridgeScope.coroutineContext.cancelChildren()
        } catch (e: Exception) {
            Logger.e("NetworkServer", "Resource cleanup failed", e)
        }
    }
}

private fun java.io.OutputStream.toManagedByteWriteChannel(scope: CoroutineScope): ByteWriteChannel {
    val channel = ByteChannel()
    scope.launch {
        val buffer = ByteArray(4096)
        try {
            while (!channel.isClosedForRead) {
                val count = channel.readAvailable(buffer)
                if (count == -1) break
                write(buffer, 0, count)
                flush()
            }
        } catch (_: Exception) {
            // Ignore stream close errors.
        }
    }
    return channel
}
