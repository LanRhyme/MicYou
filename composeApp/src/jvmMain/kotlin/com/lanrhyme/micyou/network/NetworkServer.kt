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

    // 当前活动的连接处理器
    private var activeHandler: ConnectionHandler? = null

    suspend fun start(
        port: Int
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
                // Wifi 模式：同时启动 TCP + UDP 双协议
                runDualProtocolServer(port, startupComplete)
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

        // 先启动 UDP 接收器
        udpHandler = UdpConnectionHandler(
            port = udpPort,
            onAudioPacketReceived = onAudioPacketReceived,
            onError = { error ->
                Logger.w("UdpConnectionHandler", "UDP 错误: $error")
                // UDP 错误不中断连接，仅记录
            }
        )
        udpHandler?.start()

        // 然后启动 TCP 控制通道
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
        
        try {
            handler.run()
        } finally {
            activeHandler = null
            closeAction()
            Logger.i("NetworkServer", "连接已关闭")
            _state.value = StreamState.Connecting
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
            
            selectorManager?.close()
            selectorManager = null
        } catch (e: Exception) {
            Logger.e("NetworkServer", "清理资源出错", e)
        }
    }
}
