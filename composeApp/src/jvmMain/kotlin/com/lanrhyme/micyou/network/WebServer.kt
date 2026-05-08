package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.AudioPacketMessage
import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.StreamState
import io.ktor.http.ContentType
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class WebServer(
    private val port: Int,
    private val onAudioPacketReceived: (AudioPacketMessage) -> Unit
) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    private val _state = MutableStateFlow(StreamState.Idle)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val clientCount = AtomicInteger(0)
    private val _clientCountFlow = MutableStateFlow(0)
    val clientCountFlow: StateFlow<Int> = _clientCountFlow.asStateFlow()

    @Volatile
    var isRunning: Boolean = false
        private set

    private val htmlContent: String by lazy { WebHtmlPage.getHtml() }

    fun start() {
        if (isRunning) {
            Logger.w("WebServer", "WebServer is already running")
            return
        }

        _state.value = StreamState.Connecting

        try {
            val keyStore = SelfSignedCertificate.generate()
            val password = SelfSignedCertificate.getKeyStorePassword()

            server = embeddedServer(Netty,
                environment = applicationEnvironment {},
                configure = {
                    sslConnector(
                        keyStore = keyStore,
                        keyAlias = SelfSignedCertificate.getCertAlias(),
                        keyStorePassword = { password.toCharArray() },
                        privateKeyPassword = { password.toCharArray() }
                    ) {
                        port = this@WebServer.port
                        host = "0.0.0.0"
                    }
                }
            ) {
                install(WebSockets) {
                    pingPeriod = 30.seconds
                    timeout = 15.seconds
                }
                install(CORS) {
                    anyHost()
                }
                routing {
                    get("/") {
                        call.respondText(htmlContent, ContentType.Text.Html)
                    }
                    webSocket("/ws") {
                        handleWebSocketSession()
                    }
                }
            }

            server!!.start(wait = false)

            isRunning = true
            _state.value = StreamState.Idle
            Logger.i("WebServer", "HTTPS+WebSocket server started on port $port")
        } catch (e: Exception) {
            _state.value = StreamState.Error
            _lastError.value = "Failed to start web server: ${e.message}"
            Logger.e("WebServer", "Failed to start web server", e)
        }
    }

    private suspend fun DefaultWebSocketServerSession.handleWebSocketSession() {
        val currentCount = clientCount.incrementAndGet()
        _clientCountFlow.value = currentCount
        if (currentCount == 1) {
            _state.value = StreamState.Streaming
        }
        Logger.i("WebServer", "WebSocket client connected (total: $currentCount)")

        try {
            for (frame in incoming) {
                if (!isActive) break
                when (frame) {
                    is Frame.Binary -> {
                        processAudioData(frame.data)
                    }
                    is Frame.Close -> {
                        break
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            Logger.w("WebServer", "WebSocket session error: ${e.message}")
        } finally {
            val remaining = clientCount.decrementAndGet()
            _clientCountFlow.value = remaining
            if (remaining == 0) _state.value = StreamState.Idle
            Logger.i("WebServer", "WebSocket client disconnected (remaining: $remaining)")
        }
    }

    fun stop() {
        isRunning = false
        _state.value = StreamState.Idle
        try {
            server?.stop(1000, 2000)
        } catch (_: Exception) {}
        server = null
        clientCount.set(0)
        _clientCountFlow.value = 0
        Logger.i("WebServer", "WebServer stopped")
    }

    private fun processAudioData(float32Bytes: ByteArray) {
        try {
            val numFloats = float32Bytes.size / 4
            if (numFloats == 0) return
            val shortBuffer = ShortArray(numFloats)
            val byteBuffer = ByteBuffer.wrap(float32Bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until numFloats) {
                if (byteBuffer.remaining() < 4) break
                val sample = byteBuffer.float
                shortBuffer[i] = (sample * 32767f).coerceIn(-32767f, 32767f).toInt().toShort()
            }
            val pcmBytes = ByteArray(shortBuffer.size * 2)
            val outBuf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (s in shortBuffer) outBuf.putShort(s)
            val audioPacket = AudioPacketMessage(buffer = pcmBytes, sampleRate = 48000, channelCount = 1, audioFormat = 2)
            onAudioPacketReceived(audioPacket)
        } catch (e: Exception) {
            Logger.w("WebServer", "Error processing audio data: ${e.message}")
        }
    }

    fun getClientCount(): Int = clientCount.get()
}
