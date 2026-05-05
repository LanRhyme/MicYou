package com.lanrhyme.micyou.web

import com.lanrhyme.micyou.AudioPacketMessage
import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.StreamState
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Serializable
data class MicYouStatus(
    val appName: String = "MicYou",
    val version: String = "1.0",
    val streamState: String = "idle",
    val clientCount: Int = 0,
    val sampleRate: Int = 48000,
    val channelCount: Int = 1
)

class WebModeService {
    companion object {
        private const val SAMPLE_RATE = 48000
        private const val HOP_LENGTH = 256
    }

    private val audioPacketChannel = Channel<AudioPacketMessage>(
        capacity = 200,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _state = MutableStateFlow(StreamState.Idle)
    val state = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()

    private val _webUrl = MutableStateFlow<String?>(null)
    val webUrl = _webUrl.asStateFlow()

    private val _clientCount = MutableStateFlow(0)
    val clientCount = _clientCount.asStateFlow()

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var serverPort: Int = 0

    fun getLocalIps(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        ips.add(addr.hostAddress ?: continue)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w("WebModeService", "Failed to get local IPs: ${e.message}")
        }

        ips.sortBy { ip ->
            when {
                ip.startsWith("192.168.1.") -> 0
                ip.startsWith("192.168.0.") -> 1
                ip.startsWith("192.168.") -> 2
                ip.startsWith("192.") -> 3
                ip.startsWith("10.") -> 4
                ip.startsWith("172.") -> 5
                else -> 10
            }
        }
        return ips
    }

    fun getBestUrl(): String? {
        val protocol = "http"
        val ips = getLocalIps()
        if (ips.isNotEmpty()) {
            return "$protocol://${ips.first()}:$serverPort"
        }
        return "$protocol://127.0.0.1:$serverPort"
    }

    fun getAllUrls(): List<String> {
        val protocol = "http"
        return getLocalIps().map { "$protocol://$it:$serverPort" }
    }

    fun getPort(): Int = serverPort

    suspend fun start(port: Int = 0) {
        if (server != null) {
            Logger.w("WebModeService", "Server already running")
            return
        }

        _state.value = StreamState.Connecting
        _lastError.value = null

        try {
            val actualPort = if (port in 1..65535) port else findFreePort()
            serverPort = actualPort

            val urls = getAllUrls()
            val primaryUrl = urls.firstOrNull() ?: "http://127.0.0.1:$actualPort"
            _webUrl.value = primaryUrl
            Logger.i("WebModeService", "Available URLs: $urls")

            val serviceRef = this

            server = embeddedServer(CIO, port = actualPort, host = "0.0.0.0") {
                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                        prettyPrint = false
                    })
                }
                install(WebSockets)

                routing {
                    staticResources("/", "webapp")

                    get("/") {
                        call.respondText(
                            text = buildHtmlPage(),
                            contentType = ContentType.Text.Html.withCharset(Charsets.UTF_8)
                        )
                    }

                    get("/api/status") {
                        val status = MicYouStatus(
                            appName = "MicYou",
                            version = System.getProperty("app.version") ?: "1.0",
                            streamState = serviceRef._state.value.name.lowercase(),
                            clientCount = serviceRef._clientCount.value,
                            sampleRate = SAMPLE_RATE,
                            channelCount = 1
                        )
                        call.respond(status)
                    }

                    webSocket("/ws") {
                        serviceRef._clientCount.value++
                        Logger.i("WebModeService", "WebSocket client connected (total: ${serviceRef._clientCount.value})")
                        serviceRef._state.value = StreamState.Streaming

                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Binary) {
                                    serviceRef.processAudioFrame(frame.data)
                                }
                            }
                        } catch (e: Exception) {
                            Logger.w("WebModeService", "WebSocket error: ${e.message}")
                        } finally {
                            serviceRef._clientCount.value--
                            Logger.i("WebModeService", "WebSocket client disconnected (total: ${serviceRef._clientCount.value})")
                            if (serviceRef._clientCount.value == 0) {
                                serviceRef._state.value = StreamState.Connecting
                            }
                        }
                    }
                }
            }

            server?.start(wait = false)
            _state.value = StreamState.Connecting
            Logger.i("WebModeService", "Server started on port $actualPort")
        } catch (e: Exception) {
            Logger.e("WebModeService", "Failed to start server: ${e.message}", e)
            _state.value = StreamState.Error
            _lastError.value = e.message
            throw e
        }
    }

    suspend fun stop() {
        try {
            server?.stop(1000, 2000)
            server = null
            _state.value = StreamState.Idle
            _webUrl.value = null
            _clientCount.value = 0
            Logger.i("WebModeService", "Server stopped")
        } catch (e: Exception) {
            Logger.e("WebModeService", "Error stopping server: ${e.message}", e)
        }
    }

    suspend fun receiveAudioPacket(): AudioPacketMessage? {
        return try {
            audioPacketChannel.receiveCatching().getOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun processAudioFrame(data: ByteArray) {
        if (data.size < 4) return

        val numFloats = data.size / 4
        val floatBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val floatArray = FloatArray(numFloats)
        floatBuffer.get(floatArray)

        val shortArray = ShortArray(numFloats) { i ->
            val clamped = floatArray[i].coerceIn(-1f, 1f)
            (clamped * 32767f).toInt().toShort()
        }

        val byteBuffer = ByteBuffer.allocate(shortArray.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        shortArray.forEach { byteBuffer.putShort(it) }
        val pcmBytes = byteBuffer.array()

        val audioPacket = AudioPacketMessage(
            buffer = pcmBytes,
            sampleRate = SAMPLE_RATE,
            channelCount = 1,
            audioFormat = 3
        )

        try {
            audioPacketChannel.trySend(audioPacket)
        } catch (e: Exception) {
            Logger.w("WebModeService", "Audio packet channel full, dropping packet")
        }
    }

    private fun findFreePort(): Int {
        return try {
            val socket = java.net.ServerSocket(0)
            val port = socket.localPort
            socket.close()
            port
        } catch (e: Exception) {
            8765
        }
    }

    private fun buildHtmlPage(): String {
        return HTML_PAGE.replace("{HOP_LENGTH}", HOP_LENGTH.toString())
    }

    private val HTML_PAGE = """<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="theme-color" content="#4fc3f7">
    <link rel="manifest" href="/manifest.json">
    <title>MicYou Web</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { 
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
            min-height: 100vh; display: flex; flex-direction: column;
            align-items: center; justify-content: center; color: #fff; padding: 20px;
        }
        .container { text-align: center; max-width: 400px; width: 100%; }
        h1 { font-size: 1.5em; margin-bottom: 10px; color: #4fc3f7; }
        .status { 
            padding: 15px 25px; border-radius: 10px; margin: 20px 0;
            font-size: 1.1em; font-weight: 500;
        }
        .status.connected { background: #2e7d32; }
        .status.disconnected { background: #c62828; }
        .status.connecting { background: #f57c00; }
        button {
            padding: 15px 40px; font-size: 1.2em; border: none;
            border-radius: 30px; cursor: pointer; margin: 10px;
            transition: all 0.3s ease; font-weight: 600;
        }
        .start-btn { background: #4fc3f7; color: #1a1a2e; }
        .start-btn:hover { background: #29b6f6; transform: scale(1.05); }
        .stop-btn { background: #ef5350; color: #fff; }
        .stop-btn:hover { background: #e53935; transform: scale(1.05); }
        .audio-level {
            width: 100%; height: 20px; background: #333;
            border-radius: 10px; margin: 15px 0; overflow: hidden;
        }
        .audio-level-bar {
            height: 100%; background: linear-gradient(90deg, #4fc3f7, #29b6f6);
            width: 0%; transition: width 0.1s ease;
        }
        .help-box {
            background: #1e3a5f; padding: 15px; border-radius: 10px;
            margin: 15px 0; font-size: 0.85em; line-height: 1.8;
            text-align: left;
        }
        .help-box h3 { color: #4fc3f7; margin-bottom: 10px; text-align: center; }
        .lang-switch { position: absolute; top: 10px; right: 10px; }
        .lang-switch button {
            padding: 5px 10px; font-size: 0.8em; margin: 2px;
            background: #333; color: #fff;
        }
        .lang-switch button.active { background: #4fc3f7; color: #1a1a2e; }
    </style>
</head>
<body>
    <div class="lang-switch">
        <button id="btnZh" onclick="setLang('zh')" class="active">中文</button>
        <button id="btnEn" onclick="setLang('en')">EN</button>
    </div>
    <div class="container">
        <h1>MicYou Web</h1>
        <p id="subtitle" style="color: #aaa;">网络音频输入</p>
        <div id="status" class="status disconnected" data-zh="未连接" data-en="Disconnected">未连接</div>
        <div class="audio-level"><div id="levelBar" class="audio-level-bar"></div></div>
        <button id="startBtn" class="start-btn" onclick="startStreaming()" data-zh="开始麦克风" data-en="Start Microphone">开始麦克风</button>
        <button id="stopBtn" class="stop-btn" onclick="stopStreaming()" style="display:none;" data-zh="停止" data-en="Stop">停止</button>
        
        <div class="help-box">
            <h3 id="helpTitle" data-zh="使用说明" data-en="Instructions">使用说明</h3>
            <div id="helpContent">
                <p data-zh="1. 点击上方按钮开始传输音频" data-en="1. Click the button above to start audio streaming">1. 点击上方按钮开始传输音频</p>
                <p data-zh="2. 浏览器会请求麦克风权限，请点击允许" data-en="2. Browser will request microphone permission, please allow it">2. 浏览器会请求麦克风权限，请点击允许</p>
                <p data-zh="3. 如果没有弹出权限请求，请检查浏览器设置：" data-en="3. If no permission prompt appears, check browser settings:">3. 如果没有弹出权限请求，请检查浏览器设置：</p>
                <p style="color:#4fc3f7;margin-left:15px;" data-zh="Chrome: 设置 → 隐私和安全 → 网站设置 → 麦克风" data-en="Chrome: Settings → Privacy → Site Settings → Microphone">Chrome: 设置 → 隐私和安全 → 网站设置 → 麦克风</p>
                <p style="color:#4fc3f7;margin-left:15px;" data-zh="Safari: 设置 → 网站 → 麦克风" data-en="Safari: Settings → Websites → Microphone">Safari: 设置 → 网站 → 麦克风</p>
                <p style="margin-top:10px;" data-zh="首次访问可能需要信任证书，点击"继续"即可" data-en="First visit may require trusting the certificate, click 'Continue'">首次访问可能需要信任证书，点击"继续"即可</p>
            </div>
        </div>
    </div>
    <script>
        const HOP_LENGTH = {HOP_LENGTH};
        let ws = null;
        let audioContext = null;
        let processor = null;
        let stream = null;
        let currentLang = 'zh';
        
        const statusEl = document.getElementById('status');
        const levelBar = document.getElementById('levelBar');
        const startBtn = document.getElementById('startBtn');
        const stopBtn = document.getElementById('stopBtn');
        
        function setLang(lang) {
            currentLang = lang;
            document.getElementById('btnZh').className = lang === 'zh' ? 'active' : '';
            document.getElementById('btnEn').className = lang === 'en' ? 'active' : '';
            document.querySelectorAll('[data-zh]').forEach(function(el) {
                el.textContent = el.getAttribute('data-' + lang);
            });
        }
        
        function setStatus(textZh, textEn, cls) {
            statusEl.setAttribute('data-zh', textZh);
            statusEl.setAttribute('data-en', textEn);
            statusEl.textContent = currentLang === 'zh' ? textZh : textEn;
            statusEl.className = 'status ' + cls;
        }
        
        async function startStreaming() {
            setStatus('正在请求麦克风...', 'Requesting microphone...', 'connecting');
            
            try {
                stream = await navigator.mediaDevices.getUserMedia({
                    audio: { sampleRate: 48000, channelCount: 1, echoCancellation: false, noiseSuppression: false }
                });
                
                audioContext = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 48000 });
                const source = audioContext.createMediaStreamSource(stream);
                
                processor = audioContext.createScriptProcessor(HOP_LENGTH, 1, 1);
                
                let wsUrl = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';
                ws = new WebSocket(wsUrl);
                ws.binaryType = 'arraybuffer';
                
                ws.onopen = function() {
                    setStatus('已连接 - 传输中', 'Connected - Streaming', 'connected');
                    startBtn.style.display = 'none';
                    stopBtn.style.display = 'inline-block';
                };
                
                ws.onmessage = function(e) {
                    if (e.data === 'SERVER_STOP') {
                        setStatus('服务已停止', 'Server Stopped', 'disconnected');
                        stopStreaming();
                    }
                };
                
                ws.onclose = function() {
                    setStatus('未连接', 'Disconnected', 'disconnected');
                    startBtn.style.display = 'inline-block';
                    stopBtn.style.display = 'none';
                };
                
                ws.onerror = function(e) {
                    setStatus('连接失败', 'Connection Error', 'disconnected');
                    stopStreaming();
                };
                
                processor.onaudioprocess = function(e) {
                    if (ws && ws.readyState === WebSocket.OPEN) {
                        const inputData = e.inputBuffer.getChannelData(0);
                        const float32Data = new Float32Array(inputData);
                        ws.send(float32Data.buffer);
                        let sum = 0;
                        for (let i = 0; i < inputData.length; i++) {
                            sum += inputData[i] * inputData[i];
                        }
                        const rms = Math.sqrt(sum / inputData.length);
                        const level = Math.min(100, rms * 500);
                        levelBar.style.width = level + '%';
                    }
                };
                
                source.connect(processor);
                processor.connect(audioContext.destination);
                
            } catch (err) {
                let msgZh = '麦克风访问失败，请在浏览器设置中允许麦克风权限';
                let msgEn = 'Microphone access failed. Please allow microphone permission in browser settings';
                setStatus(msgZh, msgEn, 'disconnected');
            }
        }
        
        function stopStreaming() {
            if (processor) { processor.disconnect(); processor = null; }
            if (audioContext) { audioContext.close(); audioContext = null; }
            if (stream) { stream.getTracks().forEach(function(t) { t.stop(); }); stream = null; }
            if (ws) { ws.close(); ws = null; }
            setStatus('未连接', 'Disconnected', 'disconnected');
            startBtn.style.display = 'inline-block';
            stopBtn.style.display = 'none';
            levelBar.style.width = '0%';
        }

        if ('serviceWorker' in navigator) {
            navigator.serviceWorker.register('/sw.js')
                .then(function(reg) { console.log('SW registered:', reg.scope); })
                .catch(function(err) { console.log('SW failed:', err); });
        }
    </script>
</body>
</html>
"""
}
