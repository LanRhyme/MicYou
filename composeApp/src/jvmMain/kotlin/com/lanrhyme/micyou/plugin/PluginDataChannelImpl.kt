package com.lanrhyme.micyou.plugin

import com.lanrhyme.micyou.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class PluginDataChannelImpl(
    override val id: String,
    override val config: DataChannelConfig
) : PluginDataChannel {
    
    private val _isConnected = MutableStateFlow(false)
    override val isConnected: Flow<Boolean> = _isConnected.asStateFlow()
    
    private var _localPort = 0
    override val localPort: Int get() = _localPort
    
    private var tcpSocket: Socket? = null
    private var tcpServerSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    
    override suspend fun connect(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            when (config.mode) {
                DataChannelMode.Tcp -> {
                    val socket = Socket(host, port)
                    tcpSocket = socket
                    _localPort = socket.localPort
                    _isConnected.value = true
                    Logger.i("PluginDataChannel", "TCP connected to $host:$port")
                }
                DataChannelMode.Udp -> {
                    val socket = DatagramSocket()
                    socket.connect(InetSocketAddress(host, port))
                    udpSocket = socket
                    _localPort = socket.localPort
                    _isConnected.value = true
                    Logger.i("PluginDataChannel", "UDP connected to $host:$port")
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("PluginDataChannel", "Failed to connect: ${e.message}")
            Result.failure(e)
        }
    }
    
    override suspend fun bind(port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            when (config.mode) {
                DataChannelMode.Tcp -> {
                    val serverSocket = ServerSocket(port)
                    tcpServerSocket = serverSocket
                    _localPort = serverSocket.localPort
                    _isConnected.value = true
                    Logger.i("PluginDataChannel", "TCP server bound to port ${serverSocket.localPort}")
                }
                DataChannelMode.Udp -> {
                    val socket = DatagramSocket(port)
                    udpSocket = socket
                    _localPort = socket.localPort
                    _isConnected.value = true
                    Logger.i("PluginDataChannel", "UDP socket bound to port $port")
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("PluginDataChannel", "Failed to bind: ${e.message}")
            Result.failure(e)
        }
    }
    
    override suspend fun send(data: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            when (config.mode) {
                DataChannelMode.Tcp -> {
                    val socket = tcpSocket ?: return@withContext Result.failure(Exception("Not connected"))
                    socket.getOutputStream().write(data)
                    socket.getOutputStream().flush()
                }
                DataChannelMode.Udp -> {
                    val socket = udpSocket ?: return@withContext Result.failure(Exception("Not bound"))
    val packet = DatagramPacket(data, data.size)
                    socket.send(packet)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("PluginDataChannel", "Failed to send: ${e.message}")
            Result.failure(e)
        }
    }
    
    override fun receive(): Flow<ByteArray> = kotlinx.coroutines.flow.flow {
        when (config.mode) {
            DataChannelMode.Tcp -> {
                val socket = tcpSocket ?: return@flow
                val input = socket.getInputStream()
    val buffer = ByteArray(config.bufferSize)
                while (_isConnected.value && !socket.isClosed) {
                    try {
                        val bytesRead = withContext(Dispatchers.IO) { input.read(buffer) }
                        if (bytesRead > 0) {
                            emit(buffer.copyOf(bytesRead))
                        } else if (bytesRead == -1) {
                            break
                        }
                    } catch (e: Exception) {
                        if (_isConnected.value) {
                            Logger.e("PluginDataChannel", "Receive error: ${e.message}")
                        }
                        break
                    }
                }
            }
            DataChannelMode.Udp -> {
                val socket = udpSocket ?: return@flow
                val buffer = ByteArray(config.bufferSize)
    val packet = DatagramPacket(buffer, buffer.size)
                while (_isConnected.value && !socket.isClosed) {
                    try {
                        withContext(Dispatchers.IO) { socket.receive(packet) }
                        emit(packet.data.copyOf(packet.length))
                    } catch (e: Exception) {
                        if (_isConnected.value) {
                            Logger.e("PluginDataChannel", "Receive error: ${e.message}")
                        }
                        break
                    }
                }
            }
        }
    }
    
    override suspend fun close() {
        withContext(Dispatchers.IO) {
            _isConnected.value = false
            tcpSocket?.close()
            tcpSocket = null
            tcpServerSocket?.close()
            tcpServerSocket = null
            udpSocket?.close()
            udpSocket = null
            Logger.i("PluginDataChannel", "Channel $id closed")
        }
    }
}

class PluginDataChannelProviderImpl : PluginDataChannelProvider {
    // 使用 SupervisorJob 确保单个 channel 的失败不会影响其他 channel
    // 每次需要使用时检查 scope 是否有效，无效时重新创建
    private var scopeJob: Job = SupervisorJob()
    private var scope: CoroutineScope = CoroutineScope(Dispatchers.Default + scopeJob)
    private val channels = mutableMapOf<String, PluginDataChannel>()

    /**
     * 获取有效的 scope，如果之前的 scope 已被取消则重新创建
     */
    private fun getValidScope(): CoroutineScope {
        if (!scopeJob.isActive) {
            scopeJob = SupervisorJob()
            scope = CoroutineScope(Dispatchers.Default + scopeJob)
            Logger.d("PluginDataChannel", "Scope recreated after cancellation")
        }
        return scope
    }

    override fun createChannel(id: String, config: DataChannelConfig): PluginDataChannel {
        val validScope = getValidScope()
    val existing = channels[id]
        if (existing != null) {
            validScope.launch { existing.close() }
        }
    val channel = PluginDataChannelImpl(id, config)
        channels[id] = channel
        return channel
    }

    override fun getChannel(id: String): PluginDataChannel? = channels[id]

    override fun closeChannel(id: String) {
        val validScope = getValidScope()
        channels[id]?.let { channel ->
            validScope.launch { channel.close() }
            channels.remove(id)
        }
    }

    override fun closeAllChannels() {
        val validScope = getValidScope()
        channels.values.forEach { channel ->
            validScope.launch { channel.close() }
        }
        channels.clear()
        // 只取消子协程，保留 scope 可复用性
        scopeJob.cancelChildren()
        Logger.d("PluginDataChannel", "All channels closed, scope remains valid")
    }
}
