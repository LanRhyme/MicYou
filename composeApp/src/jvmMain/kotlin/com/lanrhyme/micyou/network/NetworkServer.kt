package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.*
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.errorPortInUseMessage
import micyou.composeapp.generated.resources.errorRecordingPermissionDenied
import micyou.composeapp.generated.resources.errorServerGeneric
import micyou.composeapp.generated.resources.errorSocketError
import org.jetbrains.compose.resources.getString
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.EOFException
import java.net.BindException

/**
 * 管理网络服务器（TCP/UDP）的生命周期。
 * 职责包括：
 * 1. 绑定端口
 * 2. 接受传入连接
 * 3. 将连接处理委托给 ConnectionHandler
 * 4. 报告服务器状态
 */
class NetworkServer(
    private val onAudioPacketReceived: suspend (AudioPacketMessage) -> Unit,
    private val onMuteStateChanged: (Boolean) -> Unit,
    private val onPluginSyncReceived: ((PluginSyncMessage) -> Unit)? = null
) {
    private val _state = MutableStateFlow(StreamState.Idle)
    val state = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()

    // 使用统一的协程作用域管理所有服务器相关协程的生命周期
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    private var selectorManager: SelectorManager? = null
    
    // TCP 资源
    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null

    // UDP 资源
    private var udpHandler: UdpConnectionHandler? = null
    private var jitterBuffer: JitterBuffer? = null

    // 当前活动的连接处理器
    private var activeHandler: ConnectionHandler? = null

    suspend fun start(
        port: Int,
        protocol: TransportProtocol = TransportProtocol.Both
    ) {
        serverJob?.takeIf { it.isActive }?.let {
            Logger.w("NetworkServer", "服务器已在运行")
            return
        }

        _state.value = StreamState.Connecting
        _lastError.value = null

        // 使用 CompletableDeferred 确保绑定成功后才返回，同时捕获异常
        val startupComplete = CompletableDeferred<Unit>()

        serverJob = serverScope.launch {
            try {
                // 根据协议类型启动服务器
                when (protocol) {
                    TransportProtocol.Tcp -> {
                        // 纯 TCP 模式：同时传输音频和控制消息
                        runTcpOnlyServer(port, startupComplete)
                    }
                    TransportProtocol.Both -> {
                        // TCP+UDP 模式：TCP 控制通道 + UDP 音频通道
                        runDualProtocolServer(port, startupComplete)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("NetworkServer", "服务器致命错误", e)
                _state.value = StreamState.Error
                _lastError.value = String.format(getString(Res.string.errorServerGeneric), e.message ?: "")
                startupComplete.completeExceptionally(e)
            } finally {
                cleanup()
                if (_state.value != StreamState.Error) {
                    _state.value = StreamState.Idle
                }
            }
        }
        
        // 等待启动完成，失败时抛出异常
        try {
            startupComplete.await()
        } catch (e: Exception) {
            // 取消 serverJob
            serverJob?.cancel()
            throw e
        }
    }

    suspend fun stop() {
        serverJob?.cancel()
        // 使用超时保护，避免长时间等待协程结束
        withTimeoutOrNull(Constants.SERVER_STOP_TIMEOUT_MS) {
            serverJob?.join()
        } ?: Logger.w("NetworkServer", "Server job join timeout after ${Constants.SERVER_STOP_TIMEOUT_MS}ms")
        serverJob = null
        cleanup()
    }

    suspend fun sendMuteState(muted: Boolean) {
        activeHandler?.sendMuteState(muted)
    }

    suspend fun sendPluginSync(plugins: List<PluginInfoMessage>, platform: String) {
        activeHandler?.sendPluginSync(plugins, platform)
    }

    fun getUdpStats(): UdpConnectionHandler.UdpStats? = udpHandler?.getStats()
    
    fun getRtt(): Long = activeHandler?.getRtt() ?: 0L

    /**
     * 运行双协议服务器：TCP 控制通道 + UDP 音频通道
     * TCP 负责：握手、控制消息（静音/插件同步）
     * UDP 负责：音频数据传输
     */
    private suspend fun runDualProtocolServer(port: Int, startupComplete: CompletableDeferred<Unit>? = null) {
        val udpPort = calculateUdpPort(port)
        Logger.i("NetworkServer", "启动双协议服务器: TCP 端口 $port, UDP 端口 $udpPort")

        // 启动 Jitter Buffer（处理乱序包，带等待窗口和 FEC 恢复）
        jitterBuffer = JitterBuffer(
            onAudioPacketReady = onAudioPacketReceived,
            fecGroupSize = 12
        ).also { it.start() }

        // 启动 UDP 接收器
        udpHandler = UdpConnectionHandler(
            port = udpPort,
            onAudioPacketReceived = onAudioPacketReceived,
            onError = { error ->
                Logger.w("UdpConnectionHandler", "UDP 错误: $error")
                // UDP 错误不中断连接，仅记录
            },
            onAudioPacketOrderedReceived = { packet ->
                jitterBuffer?.insert(packet)
            }
        )
        udpHandler?.start()

        // 然后启动 TCP 控制通道
        runTcpServer(port, startupComplete)
    }

    /**
     * 运行仅 TCP 服务器：音频和控制消息都通过 TCP 传输
     * 适用于需要简化网络配置的场景
     */
    private suspend fun runTcpOnlyServer(port: Int, startupComplete: CompletableDeferred<Unit>? = null) {
        Logger.i("NetworkServer", "启动仅 TCP 服务器: 端口 $port")
        // 仅启动 TCP 服务器，音频和控制消息都通过 TCP 传输
        runTcpServer(port, startupComplete)
    }

    private suspend fun runTcpServer(port: Int, startupComplete: CompletableDeferred<Unit>? = null) {
        try {
            val manager = SelectorManager(Dispatchers.IO)
            selectorManager = manager
            serverSocket = aSocket(manager).tcp().bind("0.0.0.0", port = port)
            Logger.i("NetworkServer", "正在监听 TCP 端口 $port")
            
            // 通知启动成功
            startupComplete?.complete(Unit)
            
            while (currentCoroutineContext().isActive) {
                val socket = serverSocket?.accept() ?: break
                activeSocket = socket
                Logger.i("NetworkServer", "接受来自 ${socket.remoteAddress} 的 TCP 连接")
                
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
            val msg = String.format(getString(Res.string.errorPortInUseMessage), port)
            Logger.e("NetworkServer", msg, e)
            _lastError.value = msg
            _state.value = StreamState.Error
            throw Exception(msg, e)
        } catch (e: java.net.SocketException) {
            if (e.message?.contains("Permission denied", ignoreCase = true) == true) {
                val msg = getString(Res.string.errorRecordingPermissionDenied)
                Logger.e("NetworkServer", msg, e)
                _lastError.value = msg
                _state.value = StreamState.Error
                throw Exception(msg, e)
            } else {
                val msg = String.format(getString(Res.string.errorSocketError), e.message ?: "")
                Logger.e("NetworkServer", msg, e)
                _lastError.value = msg
                _state.value = StreamState.Error
                throw Exception(msg, e)
            }
        } catch (e: Exception) {
            val msg = String.format(getString(Res.string.errorServerGeneric), e.message ?: "")
            Logger.e("NetworkServer", msg, e)
            _lastError.value = msg
            _state.value = StreamState.Error
            throw e
        }
    }

    private suspend fun handleConnection(
        input: ByteReadChannel,
        output: ByteWriteChannel,
        closeAction: suspend () -> Unit
    ) {
        _state.value = StreamState.Streaming
        _lastError.value = null

        // Read first 4 bytes for protocol detection
        val magicBytes = ByteArray(4)
        try {
            input.readFully(magicBytes)
        } catch (e: EOFException) {
            closeAction()
            return
        }

        val magic = ((magicBytes[0].toInt() and 0xFF) shl 24) or
                    ((magicBytes[1].toInt() and 0xFF) shl 16) or
                    ((magicBytes[2].toInt() and 0xFF) shl 8) or
                    (magicBytes[3].toInt() and 0xFF)

        when (magic) {
            IosProtocolConstants.MAGIC_HEADER -> {
                Logger.i("NetworkServer", "检测到 iOS 协议客户端")
                val headerRemaining = ByteArray(12)
                try {
                    input.readFully(headerRemaining)
                } catch (e: Exception) {
                    Logger.e("NetworkServer", "Failed to read iOS header", e)
                    closeAction()
                    return
                }
                val fullHeader = magicBytes + headerRemaining
                handleIosConnection(input, output, fullHeader, closeAction)
            }
            else -> {
                // Android protocol: prepend read bytes back to stream
                val androidInput = prependByteReadChannel(input, magicBytes)
                handleAndroidConnection(androidInput, output, closeAction)
            }
        }
    }

    private suspend fun handleAndroidConnection(
        input: ByteReadChannel,
        output: ByteWriteChannel,
        closeAction: suspend () -> Unit
    ) {
        val handler = ConnectionHandler(
            input = input,
            output = output,
            onAudioPacketReceived = onAudioPacketReceived,
            onMuteStateChanged = onMuteStateChanged,
            onPluginSyncReceived = onPluginSyncReceived,
            onError = { error ->
                _lastError.value = error
            }
        )
        activeHandler = handler

        val udpMonitorJob = if (udpHandler != null) {
            serverScope.launch {
                monitorUdpConnection()
            }
        } else null

        try {
            handler.run()
        } finally {
            udpMonitorJob?.cancel()
            activeHandler = null
            closeAction()
            Logger.i("NetworkServer", "连接已关闭")
            _state.value = StreamState.Connecting
        }
    }

    private suspend fun handleIosConnection(
        input: ByteReadChannel,
        output: ByteWriteChannel,
        headerBytes: ByteArray,
        closeAction: suspend () -> Unit
    ) {
        val handler = IosConnectionHandler(
            input = input,
            output = output,
            headerBytes = headerBytes,
            onAudioPacketReceived = onAudioPacketReceived,
            onError = { error ->
                _lastError.value = error
            }
        )

        try {
            handler.run()
        } finally {
            closeAction()
            Logger.i("NetworkServer", "iOS 连接已关闭")
            _state.value = StreamState.Connecting
        }
    }

    private fun prependByteReadChannel(source: ByteReadChannel, prefix: ByteArray): ByteReadChannel {
        val out = ByteChannel(autoFlush = true)
        serverScope.launch {
            try {
                out.writeFully(prefix)
                out.flush()
                val buffer = ByteArray(4096)
                while (!source.isClosedForRead) {
                    val read = source.readAvailable(buffer, 0, buffer.size)
                    if (read == -1) break
                    if (read > 0) {
                        out.writeFully(buffer, 0, read)
                    }
                }
            } finally {
                out.close()
            }
        }
        return out
    }
    
    /**
     * 监控 UDP 连接状态
     * 当 TCP 连接成功后，如果一段时间内没有收到 UDP 包，发出警告
     */
    private suspend fun monitorUdpConnection() {
        val monitorStartDelay = 5000L  // 等待5秒让客户端开始发送UDP
        val monitorInterval = 3000L    // 每3秒检查一次
        val noPacketWarningThreshold = 10000L  // 10秒没有收到UDP包则警告
        
        delay(monitorStartDelay)
        
        // 记录初始包数，避免受之前连接的统计数据干扰
        val initialPackets = udpHandler?.getStats()?.packetsReceived ?: 0L
        val monitorStartTime = System.nanoTime()
        var warningFired = false
        
        while (currentCoroutineContext().isActive) {
            val stats = udpHandler?.getStats()
            if (stats != null) {
                val now = System.nanoTime()
                val currentPackets = stats.packetsReceived - initialPackets
                
                if (currentPackets == 0L) {
                    // 从未收到过新 UDP 包
                    val waitingTimeMs = (now - monitorStartTime) / 1_000_000
                    if (waitingTimeMs > noPacketWarningThreshold && !warningFired) {
                        Logger.w("NetworkServer", "UDP 连接监控: TCP 连接已建立，但 ${waitingTimeMs/1000} 秒内未收到任何 UDP 音频包。UDP 端口可能被防火墙阻止。")
                        _lastError.value = "UDP_AUDIO_WARNING"
                        warningFired = true
                    }
                } else {
                    // 检查 UDP 包是否中断
                    val timeSinceLastPacketMs = (now - stats.lastPacketReceivedTimeNano) / 1_000_000
                    if (timeSinceLastPacketMs > noPacketWarningThreshold && !warningFired) {
                        Logger.w("NetworkServer", "UDP 连接监控: 已 ${timeSinceLastPacketMs/1000} 秒未收到 UDP 包，连接可能中断。")
                        _lastError.value = "UDP_AUDIO_WARNING"
                        warningFired = true
                    }
                    // 收到新包后重置警告标志，并清除错误状态以允许下次触发
                    if (stats.lastPacketReceivedTimeNano > monitorStartTime) {
                        if (warningFired) {
                            _lastError.value = null
                            warningFired = false
                        }
                    }
                }
            }
            
            delay(monitorInterval)
        }
    }

    private fun cleanup() {
        try {
            activeSocket?.close()
            activeSocket = null
            serverSocket?.close()
            serverSocket = null

            // 同步停止 UDP 处理器
            udpHandler?.let { handler ->
                runBlocking {
                    try {
                        withTimeout(2000) {
                            handler.stop()
                        }
                    } catch (e: Exception) {
                        Logger.w("NetworkServer", "停止 UDP 处理器超时或出错: ${e.message}")
                    }
                }
                udpHandler = null
            }

            jitterBuffer?.stop()
            jitterBuffer = null

            selectorManager?.close()
            selectorManager = null
        } catch (e: Exception) {
            Logger.e("NetworkServer", "清理资源出错", e)
        }
    }
}
