package com.lanrhyme.micyou.plugin

import kotlinx.coroutines.flow.Flow

enum class DataChannelMode {
    Tcp, Udp
}

data class DataChannelConfig(
    val mode: DataChannelMode = DataChannelMode.Tcp,
    val port: Int = 0,
    val bufferSize: Int = 8192
)

interface PluginDataChannel {
    val id: String
    val config: DataChannelConfig
    val isConnected: Flow<Boolean>
    val localPort: Int
    
    suspend fun connect(host: String, port: Int): Result<Unit>
    suspend fun bind(port: Int = 0): Result<Unit>
    suspend fun send(data: ByteArray): Result<Unit>
    fun receive(): Flow<ByteArray>
    suspend fun close()
    
    data class ReceivedPacket(
        val data: ByteArray,
        val remoteHost: String,
        val remotePort: Int
    )
}

interface PluginDataChannelProvider {
    fun createChannel(
        id: String,
        config: DataChannelConfig = DataChannelConfig()
    ): PluginDataChannel
    
    fun getChannel(id: String): PluginDataChannel?
    fun closeChannel(id: String)
    fun closeAllChannels()
}
