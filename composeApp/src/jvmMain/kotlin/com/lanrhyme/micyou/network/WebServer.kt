package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.AudioPacketMessage
import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.StreamState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class WebServer(
    private val port: Int,
    private val onAudioPacketReceived: (AudioPacketMessage) -> Unit
) {
    private var serverChannel: AsynchronousServerSocketChannel? = null
    private var sslContext: SSLContext? = null

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
    private val wsClients = CopyOnWriteArraySet<AsynchronousSocketChannel>()

    fun start() {
        if (isRunning) {
            Logger.w("WebServer", "WebServer is already running")
            return
        }

        _state.value = StreamState.Connecting

        try {
            val keyStore = SelfSignedCertificate.generate()
            val password = SelfSignedCertificate.getKeyStorePassword().toCharArray()

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, password)

            sslContext = SSLContext.getInstance("TLS")
            sslContext!!.init(kmf.keyManagers, null, SecureRandom())

            serverChannel = AsynchronousServerSocketChannel.open()
            serverChannel!!.bind(InetSocketAddress("0.0.0.0", port))

            acceptConnections()

            isRunning = true
            _state.value = StreamState.Idle
            Logger.i("WebServer", "HTTPS+WebSocket server started on port $port")
        } catch (e: Exception) {
            _state.value = StreamState.Error
            _lastError.value = "Failed to start web server: ${e.message}"
            Logger.e("WebServer", "Failed to start web server", e)
        }
    }

    private fun acceptConnections() {
        if (!isRunning) return
        serverChannel?.accept(null, object : CompletionHandler<AsynchronousSocketChannel, Void?> {
            override fun completed(result: AsynchronousSocketChannel, attachment: Void?) {
                if (isRunning) {
                    acceptConnections()
                    handleTlsHandshake(result)
                } else {
                    closeSocket(result)
                }
            }

            override fun failed(exc: Throwable, attachment: Void?) {
                if (isRunning) {
                    Logger.w("WebServer", "Accept failed: ${exc.message}")
                    acceptConnections()
                }
            }
        })
    }

    @Volatile
    private var upgradeInProgress = false

    private fun handleTlsHandshake(socket: AsynchronousSocketChannel) {
        try {
            val engine = sslContext!!.createSSLEngine()
            engine.useClientMode = false
            engine.beginHandshake()

            val appBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize * 2)
            val netBuffer = ByteBuffer.allocate(engine.session.packetBufferSize * 2)
            val peerNetBuffer = ByteBuffer.allocate(engine.session.packetBufferSize * 2)

            doHandshake(socket, engine, appBuffer, netBuffer, peerNetBuffer)
        } catch (e: Exception) {
            Logger.w("WebServer", "TLS setup failed: ${e.message}")
            closeSocket(socket)
        }
    }

    private fun doHandshake(
        socket: AsynchronousSocketChannel,
        engine: SSLEngine,
        appBuffer: ByteBuffer,
        myNetBuffer: ByteBuffer,
        peerNetBuffer: ByteBuffer
    ) {
        val hs = engine.handshakeStatus
        when (hs) {
            javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                myNetBuffer.clear()
                val result = engine.wrap(appBuffer, myNetBuffer)
                myNetBuffer.flip()
                when (result.handshakeStatus) {
                    javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                        socket.write(myNetBuffer, null, WriteHandler(socket, engine, appBuffer, myNetBuffer, peerNetBuffer) { doRead(socket, engine, appBuffer, myNetBuffer, peerNetBuffer) })
                    }
                    javax.net.ssl.SSLEngineResult.HandshakeStatus.FINISHED, javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> {
                        socket.write(myNetBuffer, null, WriteHandler(socket, engine, appBuffer, myNetBuffer, peerNetBuffer) { onTlsConnected(socket, engine, appBuffer, myNetBuffer, peerNetBuffer) })
                    }
                    else -> {
                        socket.write(myNetBuffer, null, WriteHandler(socket, engine, appBuffer, myNetBuffer, peerNetBuffer) { doHandshake(socket, engine, appBuffer, myNetBuffer, peerNetBuffer) })
                    }
                }
            }
            javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                peerNetBuffer.clear()
                socket.read(peerNetBuffer, null, ReadHandler(socket, engine, appBuffer, myNetBuffer, peerNetBuffer) {
                    peerNetBuffer.flip()
                    engine.unwrap(peerNetBuffer, appBuffer)
                    if (engine.handshakeStatus == javax.net.ssl.SSLEngineResult.HandshakeStatus.FINISHED || engine.handshakeStatus == javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                        onTlsConnected(socket, engine, appBuffer, myNetBuffer, peerNetBuffer)
                    } else {
                        doHandshake(socket, engine, appBuffer, myNetBuffer, peerNetBuffer)
                    }
                })
            }
            javax.net.ssl.SSLEngineResult.HandshakeStatus.FINISHED, javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> {
                onTlsConnected(socket, engine, appBuffer, myNetBuffer, peerNetBuffer)
            }
            else -> {
                doHandshake(socket, engine, appBuffer, myNetBuffer, peerNetBuffer)
            }
        }
    }

    private fun onTlsConnected(
        socket: AsynchronousSocketChannel,
        engine: SSLEngine,
        appBuffer: ByteBuffer,
        myNetBuffer: ByteBuffer,
        peerNetBuffer: ByteBuffer
    ) {
        peerNetBuffer.clear()
        socket.read(peerNetBuffer, null, ReadHandler(socket, engine, appBuffer, myNetBuffer, peerNetBuffer) {
            peerNetBuffer.flip()
            appBuffer.clear()
            engine.unwrap(peerNetBuffer, appBuffer)
            appBuffer.flip()

            val requestBytes = ByteArray(appBuffer.remaining())
            appBuffer.get(requestBytes)
            val request = String(requestBytes, StandardCharsets.UTF_8)

            if (request.contains("Upgrade: websocket") && request.contains("Connection: Upgrade")) {
                val wsKey = extractWebSocketKey(request)
                if (wsKey != null) {
                    handleWebSocketUpgrade(socket, engine, wsKey, appBuffer, myNetBuffer, peerNetBuffer)
                } else {
                    closeSocket(socket)
                }
            } else {
                sendHttpResponse(socket, engine, appBuffer, myNetBuffer, "200 OK", htmlContent.toByteArray(StandardCharsets.UTF_8), "text/html; charset=utf-8")
                closeSocket(socket)
            }
        })
    }

    private fun sendHttpResponse(
        socket: AsynchronousSocketChannel,
        engine: SSLEngine,
        appBuffer: ByteBuffer,
        myNetBuffer: ByteBuffer,
        status: String,
        body: ByteArray,
        contentType: String
    ) {
        val response = (
            "HTTP/1.1 $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        ).toByteArray(StandardCharsets.UTF_8) + body

        appBuffer.clear()
        appBuffer.put(response)
        appBuffer.flip()
        myNetBuffer.clear()
        engine.wrap(appBuffer, myNetBuffer)
        myNetBuffer.flip()
        socket.write(myNetBuffer, null, WriteHandler(socket, engine, appBuffer, myNetBuffer, myNetBuffer) { closeSocket(socket) })
    }

    private fun extractWebSocketKey(request: String): String? {
        val lines = request.split("\r\n")
        for (line in lines) {
            if (line.lowercase().startsWith("sec-websocket-key:")) {
                return line.substringAfter(":").trim()
            }
        }
        return null
    }

    private fun handleWebSocketUpgrade(
        socket: AsynchronousSocketChannel,
        engine: SSLEngine,
        wsKey: String,
        appBuffer: ByteBuffer,
        myNetBuffer: ByteBuffer,
        peerNetBuffer: ByteBuffer
    ) {
        val acceptKey = generateWebSocketAccept(wsKey)
        val response = (
            "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $acceptKey\r\n" +
            "\r\n"
        ).toByteArray(StandardCharsets.UTF_8)

        appBuffer.clear()
        appBuffer.put(response)
        appBuffer.flip()
        myNetBuffer.clear()
        engine.wrap(appBuffer, myNetBuffer)
        myNetBuffer.flip()

        socket.write(myNetBuffer, null, WriteHandler(socket, engine, appBuffer, myNetBuffer, peerNetBuffer) {
            wsClients.add(socket)

            val currentCount = clientCount.incrementAndGet()
            _clientCountFlow.value = currentCount
            if (currentCount == 1) {
                _state.value = StreamState.Streaming
            }
            Logger.i("WebServer", "WebSocket client connected (total: $currentCount)")

            readWebSocketFrame(socket, engine, appBuffer, myNetBuffer, peerNetBuffer)
        })
    }

    private fun generateWebSocketAccept(key: String): String {
        val magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val md = MessageDigest.getInstance("SHA-1")
        md.update((key + magic).toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(md.digest())
    }

    private fun readWebSocketFrame(
        socket: AsynchronousSocketChannel,
        engine: SSLEngine,
        appBuffer: ByteBuffer,
        myNetBuffer: ByteBuffer,
        peerNetBuffer: ByteBuffer
    ) {
        if (!wsClients.contains(socket)) return
        peerNetBuffer.clear()

        try {
            socket.read(peerNetBuffer, null, ReadHandler(socket, engine, appBuffer, myNetBuffer, peerNetBuffer) {
                peerNetBuffer.flip()
                appBuffer.clear()
                val result = engine.unwrap(peerNetBuffer, appBuffer)
                appBuffer.flip()

                if (result.bytesProduced() > 0 && appBuffer.remaining() >= 2) {
                    val first = appBuffer.get()
                    val second = appBuffer.get()
                    val opcode = (first.toInt() and 0x0F)
                    val masked = ((second.toInt() and 0x80) != 0)
                    var payloadLen = (second.toInt() and 0x7F).toLong()

                    if (payloadLen == 126L) {
                        if (appBuffer.remaining() >= 2) {
                            payloadLen = ((appBuffer.get().toInt() and 0xFF) shl 8 or (appBuffer.get().toInt() and 0xFF)).toLong()
                        } else {
                            readWebSocketFrame(socket, engine, appBuffer, myNetBuffer, peerNetBuffer); return@ReadHandler
                        }
                    } else if (payloadLen == 127L) {
                        if (appBuffer.remaining() >= 8) {
                            payloadLen = 0
                            for (i in 0..7) payloadLen = (payloadLen shl 8) or (appBuffer.get().toInt() and 0xFF).toLong()
                        } else {
                            readWebSocketFrame(socket, engine, appBuffer, myNetBuffer, peerNetBuffer); return@ReadHandler
                        }
                    }

                    val mask = if (masked) {
                        if (appBuffer.remaining() >= 4) {
                            ByteArray(4) { appBuffer.get() }
                        } else {
                            readWebSocketFrame(socket, engine, appBuffer, myNetBuffer, peerNetBuffer); return@ReadHandler
                        }
                    } else ByteArray(0)

                    val payloadSize = payloadLen.toInt()
                    if (payloadSize > 0 && appBuffer.remaining() >= payloadSize) {
                        val payload = ByteArray(payloadSize)
                        appBuffer.get(payload)
                        if (masked) {
                            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                        }

                        when (opcode) {
                            0x2 -> {
                                processAudioData(payload)
                            }
                            0x8 -> {
                                wsClients.remove(socket)
                                val remaining = clientCount.decrementAndGet()
                                _clientCountFlow.value = remaining
                                if (remaining == 0) _state.value = StreamState.Idle
                                closeSocket(socket)
                                return@ReadHandler
                            }
                            0x9 -> {
                                val pong = byteArrayOf(0x8A.toByte(), 0)
                                appBuffer.clear()
                                appBuffer.put(pong)
                                appBuffer.flip()
                                myNetBuffer.clear()
                                engine.wrap(appBuffer, myNetBuffer)
                                myNetBuffer.flip()
                                socket.write(myNetBuffer, null, WriteHandler(socket, engine, appBuffer, myNetBuffer, peerNetBuffer) {})
                            }
                        }
                    }
                }
                readWebSocketFrame(socket, engine, appBuffer, myNetBuffer, peerNetBuffer)
            })
        } catch (e: Exception) {
            wsClients.remove(socket)
            clientCount.decrementAndGet()
            closeSocket(socket)
        }
    }

    private fun closeSocket(socket: AsynchronousSocketChannel) {
        try { socket.close() } catch (_: Exception) {}
    }

    private fun doRead(
        socket: AsynchronousSocketChannel,
        engine: SSLEngine,
        appBuffer: ByteBuffer,
        myNetBuffer: ByteBuffer,
        peerNetBuffer: ByteBuffer
    ) {
        peerNetBuffer.clear()
        socket.read(peerNetBuffer, null, ReadHandler(socket, engine, appBuffer, myNetBuffer, peerNetBuffer) {
            peerNetBuffer.flip()
            appBuffer.clear()
            engine.unwrap(peerNetBuffer, appBuffer)
            doHandshake(socket, engine, appBuffer, myNetBuffer, peerNetBuffer)
        })
    }

    private class ReadHandler(
        private val socket: AsynchronousSocketChannel,
        private val engine: SSLEngine,
        private val appBuffer: ByteBuffer,
        private val myNetBuffer: ByteBuffer,
        private val peerNetBuffer: ByteBuffer,
        private val onRead: () -> Unit
    ) : CompletionHandler<Int, Void?> {
        override fun completed(result: Int, attachment: Void?) {
            if (result < 0) {
                try { socket.close() } catch (_: Exception) {}
                return
            }
            onRead()
        }
        override fun failed(exc: Throwable, attachment: Void?) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private class WriteHandler(
        private val socket: AsynchronousSocketChannel,
        private val engine: SSLEngine,
        private val appBuffer: ByteBuffer,
        private val myNetBuffer: ByteBuffer,
        private val peerNetBuffer: ByteBuffer,
        private val onComplete: () -> Unit
    ) : CompletionHandler<Int, Void?> {
        override fun completed(result: Int, attachment: Void?) { onComplete() }
        override fun failed(exc: Throwable, attachment: Void?) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun stop() {
        isRunning = false
        _state.value = StreamState.Idle
        try {
            wsClients.forEach { try { it.close() } catch (_: Exception) {} }
            wsClients.clear()
            serverChannel?.close()
        } catch (_: Exception) {}
        serverChannel = null
        sslContext = null
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
