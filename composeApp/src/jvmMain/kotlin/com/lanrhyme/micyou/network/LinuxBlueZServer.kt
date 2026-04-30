package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.*
import com.lanrhyme.micyou.platform.PlatformInfo
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.errorBluetoothGeneric
import micyou.composeapp.generated.resources.errorServerGeneric
import org.jetbrains.compose.resources.getString
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader

class LinuxBlueZServer(
    private val onAudioPacketReceived: suspend (AudioPacketMessage) -> Unit,
    private val onMuteStateChanged: (Boolean) -> Unit
) {
    private val _state = MutableStateFlow(StreamState.Idle)
    val state = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()

    private var serverJob: Job? = null
    private var activeHandler: ConnectionHandler? = null
    private var rfcommProcess: Process? = null
    private var currentChannel: Int = 1

    private val deviceFile: String get() = "/dev/rfcomm0"

    suspend fun start(port: Int = -1) {
        serverJob?.takeIf { it.isActive }?.let {
            Logger.w("LinuxBlueZServer", "服务器已在运行")
            return
        }

        _state.value = StreamState.Connecting
        _lastError.value = null

        coroutineScope {
            serverJob = launch(Dispatchers.IO) {
                try {
                    runBlueZServer()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e("LinuxBlueZServer", "服务器致命错误", e)
                    _state.value = StreamState.Error
                    _lastError.value = String.format(getString(Res.string.errorServerGeneric), e.message ?: "")
                } finally {
                    cleanup()
                    if (_state.value != StreamState.Error) {
                        _state.value = StreamState.Idle
                    }
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

    private suspend fun runBlueZServer() {
        while (currentCoroutineContext().isActive) {
            try {
                val localAddress = getLocalBluetoothAddress()
                Logger.i("LinuxBlueZServer", "本地蓝牙地址: $localAddress")

                makeDeviceDiscoverable()

                currentChannel = registerSppService()
                Logger.i("LinuxBlueZServer", "SPP 服务注册成功，频道: $currentChannel")

                Logger.i("LinuxBlueZServer", "正在监听 RFCOMM 频道 $currentChannel")

                listenForConnections(currentChannel)

            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    Logger.e("LinuxBlueZServer", "蓝牙服务器错误: ${e.message}")
                    if (_state.value != StreamState.Connecting) {
                        _state.value = StreamState.Error
                        _lastError.value = String.format(getString(Res.string.errorBluetoothGeneric), e.message ?: "")
                        delay(5000)
                        _state.value = StreamState.Connecting
                    }
                }
            }
        }
    }

    private fun registerSppService(): Int {
        releaseRfcomm()
    val uuid = "0000110100001000800000805F9B34FB"
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sdptool", "add", "SP", "-a", uuid))
    val reader = BufferedReader(InputStreamReader(process.inputStream))
    var output = ""
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output += line
            }
    val exitCode = process.waitFor()
            Logger.i("LinuxBlueZServer", "sdptool 退出码: $exitCode")

            if (output.contains("Service Added")) {
                val channelMatch = Regex("Channel: (\\d+)").find(output)
                return channelMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
            }
        } catch (e: Exception) {
            Logger.w("LinuxBlueZServer", "sdptool 注册失败: ${e.message}")
        }
        return 1
    }

    private fun releaseRfcomm() {
        try {
            Runtime.getRuntime().exec(arrayOf("rfcomm", "release", "0")).waitFor()
        } catch (e: Exception) {
        }
    }

    private fun getLocalBluetoothAddress(): String {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("hciconfig"))
    val reader = BufferedReader(InputStreamReader(process.inputStream))
    var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line?.contains("hci") == true) {
                    val nextLine = reader.readLine()
                    if (nextLine?.contains("BD Address:") == true) {
                        val address = nextLine.split("BD Address:").getOrNull(1)?.trim()?.split(" ")?.firstOrNull()
                        if (!address.isNullOrEmpty()) {
                            return address
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w("LinuxBlueZServer", "获取蓝牙地址失败: ${e.message}")
        }
        return "00:00:00:00:00:00"
    }

    private fun makeDeviceDiscoverable() {
        try {
            Runtime.getRuntime().exec(arrayOf("hciconfig", "hci0", "piscan")).waitFor()
            Logger.i("LinuxBlueZServer", "设备已设置为可发现模式")
        } catch (e: Exception) {
            Logger.w("LinuxBlueZServer", "设置可发现模式失败: ${e.message}")
        }
    }

    private suspend fun listenForConnections(channel: Int) {
        while (currentCoroutineContext().isActive) {
            try {
                rfcommProcess?.destroy()
                rfcommProcess = null

                releaseRfcomm()
    val processBuilder = ProcessBuilder(
                    "rfcomm", "listen", deviceFile, channel.toString()
                )
                processBuilder.redirectErrorStream(true)
    val process = processBuilder.start()
                rfcommProcess = process

                val reader = BufferedReader(InputStreamReader(process.inputStream))
    var connectionEstablished = false
                while (!connectionEstablished && currentCoroutineContext().isActive) {
                    val line = reader.readLine()
                    if (line != null) {
                        Logger.i("LinuxBlueZServer", "rfcomm: $line")
                        if (line.contains("Waiting for connection") || line.contains("Connected")) {
                            connectionEstablished = true
                        }
                    } else {
                        break
                    }
                    delay(100)
                }

                if (!currentCoroutineContext().isActive) {
                    break
                }

                if (process.isAlive) {
                    delay(500)
                    handleRfcommConnection()
                }

            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    Logger.w("LinuxBlueZServer", "监听失败: ${e.message}")
                    delay(2000)
                }
            }
        }
    }

    private suspend fun handleRfcommConnection() {
        try {
            val rfcommFile = java.io.File(deviceFile)
            if (!rfcommFile.exists()) {
                Logger.w("LinuxBlueZServer", "设备文件 $deviceFile 不存在")
                return
            }

            Logger.i("LinuxBlueZServer", "打开 RFCOMM 设备: $deviceFile")
    val inputStream = rfcommFile.inputStream()
    val outputStream = rfcommFile.outputStream()

            handleConnection(
                input = inputStream.toByteReadChannel(),
                output = outputStream.toByteWriteChannel(),
                closeAction = {
                    try {
                        inputStream.close()
                        outputStream.close()
                        releaseRfcomm()
                    } catch (e: Exception) {
                    }
                }
            )
        } catch (e: Exception) {
            Logger.e("LinuxBlueZServer", "处理连接失败: ${e.message}")
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
            Logger.i("LinuxBlueZServer", "连接已关闭")
            _state.value = StreamState.Connecting
        }
    }

    private fun cleanup() {
        try {
            rfcommProcess?.destroy()
            rfcommProcess = null
            releaseRfcomm()

            try {
                Runtime.getRuntime().exec(arrayOf("sdptool", "remove", "SP")).waitFor()
            } catch (e: Exception) {
            }
        } catch (e: Exception) {
            Logger.e("LinuxBlueZServer", "清理资源出错", e)
        }
    }

    companion object {
        val isSupported: Boolean
            get() = PlatformInfo.isLinux
    }
}

@OptIn(DelicateCoroutinesApi::class)
private fun java.io.OutputStream.toByteWriteChannel(context: kotlin.coroutines.CoroutineContext = Dispatchers.IO): ByteWriteChannel {
    val channel = ByteChannel()
    GlobalScope.launch(context) {
        val buffer = ByteArray(4096)
        try {
            while (!channel.isClosedForRead) {
                val count = channel.readAvailable(buffer)
                if (count == -1) break
                write(buffer, 0, count)
                flush()
            }
        } catch (e: Exception) {
        }
    }
    return channel
}
