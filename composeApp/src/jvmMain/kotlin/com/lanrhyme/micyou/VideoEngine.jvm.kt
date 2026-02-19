package com.lanrhyme.micyou

import com.lanrhyme.micyou.network.VideoServer
import com.lanrhyme.micyou.video.VirtualCameraBridgeFactory
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

actual class VideoEngine actual constructor() {
    private data class VirtualCameraFrame(
        val width: Int,
        val height: Int,
        val jpeg: ByteArray
    )

    private val _state = MutableStateFlow(VideoStreamState.Idle)
    actual val streamState: Flow<VideoStreamState> = _state

    private val _lastError = MutableStateFlow<String?>(null)
    actual val lastError: Flow<String?> = _lastError

    private val _latestFrame = MutableStateFlow<VideoFrameUi?>(null)
    actual val latestFrame: Flow<VideoFrameUi?> = _latestFrame

    private val _stats = MutableStateFlow(VideoStats())
    actual val stats: Flow<VideoStats> = _stats
    private val _rttMs = MutableStateFlow<Long?>(null)
    actual val rttMs: Flow<Long?> = _rttMs
    private val _virtualCameraBinding = MutableStateFlow<String?>(null)
    actual val virtualCameraBinding: Flow<String?> = _virtualCameraBinding

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var serverJob: Job? = null
    private var frameCount = 0
    private var fpsWindowStart = 0L
    private var latencyClockOffsetMs: Long? = null
    private var virtualCameraStarted = false
    private var virtualCameraErrorNotified = false
    private var virtualCameraTargetWidth = 0
    private var virtualCameraTargetHeight = 0
    private var virtualCameraTargetFps = 30
    @Volatile
    private var vcamDeviceName: String? = null
    @Volatile
    private var vcamBackendName: String? = null
    @Volatile
    private var vcamHelperName: String? = null
    private val virtualCameraFrameChannel = Channel<VirtualCameraFrame>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val virtualCameraBridge = VirtualCameraBridgeFactory.create { status ->
        when {
            status.startsWith("Device:", ignoreCase = true) -> {
                vcamDeviceName = status.substringAfter("Device:").trim().ifBlank { null }
            }
            status.startsWith("Backend:", ignoreCase = true) -> {
                vcamBackendName = status.substringAfter("Backend:").trim().ifBlank { null }
            }
            status.startsWith("Helper:", ignoreCase = true) -> {
                vcamHelperName = status.substringAfter("Helper:").trim().ifBlank { null }
            }
            status.startsWith("ERROR:", ignoreCase = true) -> {
                // Keep existing binding text, error is exposed via lastError.
            }
        }
        _virtualCameraBinding.value = buildVirtualCameraBindingText()
    }

    private val videoServer = VideoServer(
        onVideoConfig = { config ->
            if (fpsWindowStart == 0L) fpsWindowStart = System.currentTimeMillis()
            _stats.value = _stats.value.copy(width = config.width, height = config.height)
            ensureVirtualCameraStarted(config.width, config.height, config.fps)
        },
        onVideoFrame = { frame ->
            val now = System.currentTimeMillis()
            frameCount += 1
            val elapsed = now - fpsWindowStart
            if (elapsed >= 1000L) {
                _stats.value = _stats.value.copy(fps = frameCount)
                frameCount = 0
                fpsWindowStart = now
            }
            _latestFrame.value = VideoFrameUi(
                sequenceNumber = frame.sequenceNumber,
                timestampMs = frame.timestampMs,
                width = frame.width,
                height = frame.height,
                jpegBytes = frame.jpegBytes,
                receivedAtMs = now
            )
            val rawLatency = (now - frame.timestampMs).coerceAtLeast(0L)
            val baseline = latencyClockOffsetMs
            val updatedBaseline = if (baseline == null) rawLatency else minOf(baseline, rawLatency)
            latencyClockOffsetMs = updatedBaseline
            _stats.value = _stats.value.copy(
                latencyMs = (rawLatency - updatedBaseline).coerceAtLeast(0L),
                width = frame.width,
                height = frame.height
            )
            enqueueVirtualCameraFrame(frame.width, frame.height, frame.jpegBytes)
        },
        requiredTokenProvider = { PlatformAdaptor.getAuthToken() }
    )

    init {
        scope.launch {
            videoServer.state.collect { s ->
                _state.value = when (s) {
                    StreamState.Idle -> VideoStreamState.Idle
                    StreamState.Connecting -> VideoStreamState.Connecting
                    StreamState.Streaming -> VideoStreamState.Streaming
                    StreamState.Error -> VideoStreamState.Error
                }
            }
        }
        scope.launch {
            videoServer.lastError.collect { e -> _lastError.value = e }
        }
        scope.launch {
            videoServer.rttMs.collect { ms -> _rttMs.value = ms }
        }
        scope.launch(Dispatchers.Default) {
            for (frame in virtualCameraFrameChannel) {
                pushFrameToVirtualCamera(frame.width, frame.height, frame.jpeg)
            }
        }
        virtualCameraBridge.init().onFailure {
            Logger.w("VideoEngine", "Virtual camera init failed: ${it.message}")
        }
    }

    actual suspend fun start(
        ip: String,
        port: Int,
        mode: ConnectionMode,
        isClient: Boolean,
        profile: VideoProfile,
        jpegQuality: Int
    ) {
        if (isClient) return
        if (mode == ConnectionMode.Usb) {
            Logger.i("VideoEngine", "Running ADB reverse for video port $port")
            if (!PlatformAdaptor.runAdbReverse(port)) {
                _lastError.value = "ADB reverse failed for video port $port. Please run: adb reverse tcp:$port tcp:$port"
                _state.value = VideoStreamState.Error
                return
            }
        }
        updateConfig(profile, jpegQuality)
        fpsWindowStart = System.currentTimeMillis()
        serverJob = scope.launch(Dispatchers.IO) {
            videoServer.start(port, mode)
        }
    }

    actual fun updateConfig(profile: VideoProfile, jpegQuality: Int) {
        PlatformAdaptor.setVideoProfile(profile)
        PlatformAdaptor.setVideoQuality(jpegQuality)
    }

    actual fun switchCamera() {
        // Desktop side does not switch local camera in receive mode.
    }

    actual fun restartVirtualCamera() {
        val s = _stats.value
        runCatching {
            virtualCameraBridge.stop()
            virtualCameraStarted = false
            virtualCameraBridge.start(
                width = if (s.width > 0) s.width else PlatformAdaptor.getVideoProfile().width,
                height = if (s.height > 0) s.height else PlatformAdaptor.getVideoProfile().height,
                fps = PlatformAdaptor.getVideoProfile().fps
            )
            virtualCameraStarted = true
            virtualCameraErrorNotified = false
        }.onFailure {
            _lastError.value = "Virtual camera restart failed: ${it.message}"
        }
    }

    actual fun requestRttTest() {
        scope.launch(Dispatchers.IO) {
            val value = videoServer.testRtt()
            if (value == null) {
                _lastError.value = "RTT test failed: no pong from mobile."
            } else {
                _rttMs.value = value
                _lastError.value = null
            }
        }
    }

    actual fun stop() {
        scope.launch(Dispatchers.IO) {
            runCatching { videoServer.stop() }
            serverJob?.cancel()
            serverJob = null
            _latestFrame.value = null
            _stats.value = VideoStats()
            _rttMs.value = null
            _virtualCameraBinding.value = null
            vcamDeviceName = null
            vcamBackendName = null
            vcamHelperName = null
            virtualCameraBridge.stop()
            virtualCameraFrameChannel.tryReceive()
            virtualCameraStarted = false
            virtualCameraErrorNotified = false
            virtualCameraTargetWidth = 0
            virtualCameraTargetHeight = 0
            latencyClockOffsetMs = null
        }
    }

    private fun enqueueVirtualCameraFrame(width: Int, height: Int, jpeg: ByteArray) {
        if (!virtualCameraStarted) return
        virtualCameraFrameChannel.trySend(VirtualCameraFrame(width, height, jpeg))
    }

    private fun ensureVirtualCameraStarted(width: Int, height: Int, fps: Int) {
        if (virtualCameraStarted) return
        val target = normalizeVirtualCameraSize(width, height)
        val targetFps = fps.coerceIn(15, 30)
        _virtualCameraBinding.value = "Detecting virtual camera..."
        virtualCameraBridge.start(target.first, target.second, targetFps).onSuccess {
            virtualCameraStarted = true
            virtualCameraTargetWidth = target.first
            virtualCameraTargetHeight = target.second
            virtualCameraTargetFps = targetFps
            virtualCameraErrorNotified = false
            Logger.i("VideoEngine", "Virtual camera started at ${target.first}x${target.second}@$targetFps")
        }.onFailure {
            if (!virtualCameraErrorNotified) {
                _lastError.value = "Virtual camera unavailable: ${it.message}"
                virtualCameraErrorNotified = true
            }
        }
    }

    private fun pushFrameToVirtualCamera(width: Int, height: Int, jpeg: ByteArray) {
        if (!virtualCameraStarted) return
        val targetW = if (virtualCameraTargetWidth > 0) virtualCameraTargetWidth else width
        val targetH = if (virtualCameraTargetHeight > 0) virtualCameraTargetHeight else height
        val frameBgra = runCatching { decodeJpegToBgra(jpeg, targetW, targetH) }.getOrNull() ?: return
        virtualCameraBridge.pushFrameBgra(targetW, targetH, frameBgra).onFailure {
            if (!virtualCameraErrorNotified) {
                _lastError.value = "Virtual camera frame push failed: ${it.message}. Try lower video profile and ensure virtual camera backend is idle."
                virtualCameraErrorNotified = true
            }
            virtualCameraStarted = false
        }
    }

    private fun decodeJpegToBgra(jpeg: ByteArray, width: Int, height: Int): ByteArray {
        val buffered = ImageIO.read(ByteArrayInputStream(jpeg)) ?: error("jpeg decode failed")
        val scaled = if (buffered.width != width || buffered.height != height) {
            BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { out ->
                val g = out.createGraphics()
                try {
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    g.drawImage(buffered, 0, 0, width, height, null)
                } finally {
                    g.dispose()
                }
            }
        } else {
            buffered
        }
        val w = scaled.width
        val h = scaled.height
        val argb = IntArray(w * h)
        scaled.getRGB(0, 0, w, h, argb, 0, w)
        val out = ByteArray(w * h * 4)
        var i = 0
        for (px in argb) {
            out[i++] = (px and 0xFF).toByte() // B
            out[i++] = ((px ushr 8) and 0xFF).toByte() // G
            out[i++] = ((px ushr 16) and 0xFF).toByte() // R
            out[i++] = ((px ushr 24) and 0xFF).toByte() // A
        }
        return out
    }

    private fun normalizeVirtualCameraSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1280 to 720
        val maxLong = 1280.0
        val maxShort = 720.0
        val landscape = width >= height
        val longSide = if (landscape) width.toDouble() else height.toDouble()
        val shortSide = if (landscape) height.toDouble() else width.toDouble()
        val scale = minOf(maxLong / longSide, maxShort / shortSide, 1.0)
        var w = (width * scale).toInt().coerceAtLeast(2)
        var h = (height * scale).toInt().coerceAtLeast(2)
        if (w % 2 != 0) w -= 1
        if (h % 2 != 0) h -= 1
        return w.coerceAtLeast(2) to h.coerceAtLeast(2)
    }

    private fun buildVirtualCameraBindingText(): String? {
        val device = vcamDeviceName
        val backend = vcamBackendName
        val helper = vcamHelperName
        return when {
            !device.isNullOrBlank() && !backend.isNullOrBlank() -> "$device ($backend)"
            !device.isNullOrBlank() -> device
            !backend.isNullOrBlank() -> "Backend: $backend"
            !helper.isNullOrBlank() -> "Helper: $helper"
            else -> null
        }
    }
}
