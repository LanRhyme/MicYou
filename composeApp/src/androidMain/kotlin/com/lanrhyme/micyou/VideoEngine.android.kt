package com.lanrhyme.micyou

import android.graphics.ImageFormat
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readInt
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.system.measureTimeMillis

actual class VideoEngine actual constructor() {
    private data class EncodedVideoFrame(
        val jpegBytes: ByteArray,
        val width: Int,
        val height: Int
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startStopMutex = Mutex()
    private var workerJob: Job? = null

    @OptIn(ExperimentalSerializationApi::class)
    private val proto = ProtoBuf { }

    private var profile: VideoProfile = VideoProfile.FHD_1080P_30
    private var baseQuality: Int = 85
    private var dynamicQuality: Int = 85
    private var dynamicFrameIntervalMs: Long = 33L
    private var cameraFacing: CameraFacing = CameraFacing.Back
    private var activeMode: ConnectionMode = ConnectionMode.Wifi

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var analyzerExecutor: ExecutorService? = null

    private var nextAllowedFrameAtMs: Long = 0L
    private var sequenceNumber: Int = 0
    private var currentFrameConsumer: ((VideoFrameMessage) -> Unit)? = null

    private val check1 = "MicYouCheck1"
    private val check2 = "MicYouCheck2"

    actual suspend fun start(
        ip: String,
        port: Int,
        mode: ConnectionMode,
        isClient: Boolean,
        profile: VideoProfile,
        jpegQuality: Int
    ) {
        if (!isClient) return
        updateConfig(profile, jpegQuality)
        _lastError.value = null
        _state.value = VideoStreamState.Connecting

        startStopMutex.withLock {
            if (workerJob?.isActive == true) return
            workerJob = scope.launch {
                runStreamingSession(ip, port, mode)
            }
        }
    }

    actual fun updateConfig(profile: VideoProfile, jpegQuality: Int) {
        this.profile = profile
        this.baseQuality = jpegQuality.coerceIn(30, 95)
        this.dynamicQuality = this.baseQuality
        this.dynamicFrameIntervalMs = (1000L / profile.fps.coerceAtLeast(1))
        PlatformAdaptor.setVideoProfile(profile)
        PlatformAdaptor.setVideoQuality(this.baseQuality)
    }

    actual fun switchCamera() {
        cameraFacing = if (cameraFacing == CameraFacing.Back) CameraFacing.Front else CameraFacing.Back
        if (_state.value == VideoStreamState.Streaming) {
            scope.launch {
                runCatching {
                    nextAllowedFrameAtMs = 0L
                    bindAnalyzer()
                }.onFailure {
                    Logger.e("VideoEngine", "Failed to switch camera", it)
                    _lastError.value = "Switch camera failed: ${it.message}"
                }
            }
        }
    }

    actual fun restartVirtualCamera() {
        // Android sender side has no virtual camera.
    }

    actual fun requestRttTest() {
        // RTT test is initiated by desktop side.
    }

    actual fun stop() {
        scope.launch {
            startStopMutex.withLock {
                workerJob?.cancel()
                workerJob = null
                cleanupCamera()
                _state.value = VideoStreamState.Idle
            }
        }
    }

    private suspend fun runStreamingSession(ip: String, port: Int, mode: ConnectionMode) {
        activeMode = mode
        if (mode == ConnectionMode.Bluetooth) {
            _state.value = VideoStreamState.Error
            _lastError.value = "Video over Bluetooth is not supported yet."
            return
        }

        var selectorManager: SelectorManager? = null
        var socket: Socket? = null
        var input: ByteReadChannel? = null
        var output: ByteWriteChannel? = null
        var sendChannel: Channel<VideoFrameMessage>? = null
        var writerJob: Job? = null
        var readerJob: Job? = null
        val writeMutex = Mutex()
        var phase = "connect"

        try {
            val (connectedSocket, connectedSelector) = connectSocket(ip, port, mode)
            socket = connectedSocket
            selectorManager = connectedSelector
            input = socket.openReadChannel()
            output = socket.openWriteChannel(autoFlush = true)

            phase = "handshake"
            output.writeFully(check1.encodeToByteArray())
            output.flush()

            val responseBuffer = ByteArray(check2.length)
            input.readFully(responseBuffer, 0, responseBuffer.size)
            if (!responseBuffer.decodeToString().equals(check2)) {
                throw java.io.IOException("Video handshake failed")
            }

            phase = "auth"
            val connectToken = PlatformAdaptor.getAuthToken().ifBlank { null }
            val connectWrapper = MessageWrapper(
                connect = ConnectMessage(
                    protocolVersion = PROTOCOL_VERSION,
                    token = connectToken
                )
            )
            writeWrapper(writeMutex, output, connectWrapper)

            phase = "config"
            writeWrapper(
                writeMutex,
                output,
                MessageWrapper(
                    videoConfig = VideoConfigMessage(
                        width = profile.width,
                        height = profile.height,
                        fps = profile.fps,
                        jpegQuality = dynamicQuality,
                        rotation = 0,
                        cameraFacing = cameraFacing.value
                    )
                )
            )

            phase = "stream"
            sendChannel = Channel(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            if (mode == ConnectionMode.Usb) {
                dynamicQuality = dynamicQuality.coerceAtMost(70)
                dynamicFrameIntervalMs = dynamicFrameIntervalMs.coerceAtLeast(33L) // up to 30fps for call apps
            }
            val outputRef = output
            writerJob = scope.launch {
                for (frame in sendChannel) {
                    val elapsed = measureTimeMillis {
                        writeWrapper(writeMutex, outputRef, MessageWrapper(videoFrame = frame))
                    }
                    tuneForNetwork(elapsed)
                }
            }
            readerJob = scope.launch {
                runCatching { processControlLoop(input, outputRef, writeMutex) }
            }

            currentFrameConsumer = { frame ->
                sendChannel.trySend(frame)
                _stats.value = VideoStats(
                    fps = profile.fps,
                    latencyMs = 0L,
                    width = frame.width,
                    height = frame.height
                )
            }
            bindAnalyzer()

            _state.value = VideoStreamState.Streaming
            writerJob.join()
        } catch (e: CancellationException) {
            Logger.d("VideoEngine", "Video streaming cancelled")
            _state.value = VideoStreamState.Idle
        } catch (e: Exception) {
            Logger.e("VideoEngine", "Video streaming failed", e)
            _lastError.value = mapVideoErrorMessage(e, mode, phase)
            _state.value = VideoStreamState.Error
        } finally {
            writerJob?.cancel()
            readerJob?.cancel()
            sendChannel?.close()
            currentFrameConsumer = null
            cleanupCamera()
            runCatching { socket?.close() }
            runCatching { selectorManager?.close() }
        }
    }

    private fun mapVideoErrorMessage(error: Throwable, mode: ConnectionMode, phase: String): String {
        val raw = error.message?.trim().orEmpty()
        val normalized = raw.lowercase()
        return when {
            normalized.contains("connection refused") -> {
                if (mode == ConnectionMode.Usb) {
                    "连接被拒绝：请先在电脑端开启音频/视频接收，再执行 adb reverse tcp:7000 tcp:7000。"
                } else {
                    "连接被拒绝：请确认电脑端视频服务已开启，且手机目标 IP/端口正确。"
                }
            }

            normalized.contains("channel is already closed") &&
                (phase == "auth" || phase == "config") -> {
                "桌面端在鉴权阶段断开了连接：请检查两端 token 是否一致（或都留空）。"
            }

            normalized.contains("channel is already closed") ||
                normalized.contains("socket closed") ||
                normalized.contains("connection reset") -> {
                "视频通道已关闭：请确认电脑端正在接收，并重试连接。"
            }

            normalized.contains("handshake") -> {
                "视频握手失败：请重启手机端和电脑端后重试。"
            }

            raw.isNotBlank() -> raw
            else -> "视频连接失败"
        }
    }

    private suspend fun connectSocket(ip: String, port: Int, mode: ConnectionMode): Pair<Socket, SelectorManager> {
        val maxAttempts = if (mode == ConnectionMode.Usb) 4 else 3
        val baseDelayMs = if (mode == ConnectionMode.Usb) 400L else 600L
        var lastError: Exception? = null

        for (attempt in 1..maxAttempts) {
            val selector = SelectorManager(Dispatchers.IO)
            try {
                val targetIp = if (mode == ConnectionMode.Usb) "127.0.0.1" else ip
                val socket = aSocket(selector).tcp().connect(targetIp, port) {
                    keepAlive = true
                    noDelay = true
                    socketTimeout = 10000L
                }
                return socket to selector
            } catch (e: Exception) {
                lastError = e
                runCatching { selector.close() }
                if (attempt < maxAttempts) {
                    kotlinx.coroutines.delay(baseDelayMs * (1L shl (attempt - 1)))
                }
            }
        }
        throw lastError ?: IllegalStateException("Unable to connect video socket")
    }

    private suspend fun bindAnalyzer() {
        val context = ContextHelper.getContext() ?: throw IllegalStateException("Context unavailable")
        val lifecycleOwner = LifecycleOwnerHolder.get() ?: throw IllegalStateException("Lifecycle owner unavailable")
        val provider = cameraProvider ?: awaitCameraProvider(context).also { cameraProvider = it }

        withContext(Dispatchers.Main) {
            provider.unbindAll()
            analyzerExecutor?.shutdownNow()
            analyzerExecutor = Executors.newSingleThreadExecutor()

            analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(profile.width, profile.height))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().apply {
                    setAnalyzer(analyzerExecutor!!) { image ->
                        processFrame(image)
                    }
                }

            val selector = CameraSelector.Builder()
                .requireLensFacing(
                    if (cameraFacing == CameraFacing.Back) CameraSelector.LENS_FACING_BACK
                    else CameraSelector.LENS_FACING_FRONT
                )
                .build()
            provider.bindToLifecycle(lifecycleOwner, selector, analysis)
        }
    }

    private fun processFrame(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now < nextAllowedFrameAtMs) return
            nextAllowedFrameAtMs = now + dynamicFrameIntervalMs

            val encoded = imageToJpeg(image, dynamicQuality) ?: return
            val ts = System.currentTimeMillis()
            val frame = VideoFrameMessage(
                sequenceNumber = sequenceNumber++,
                timestampMs = ts,
                width = encoded.width,
                height = encoded.height,
                jpegBytes = encoded.jpegBytes
            )
            currentFrameConsumer?.invoke(frame)
            _latestFrame.value = VideoFrameUi(
                sequenceNumber = frame.sequenceNumber,
                timestampMs = frame.timestampMs,
                width = frame.width,
                height = frame.height,
                jpegBytes = frame.jpegBytes,
                receivedAtMs = ts
            )
        } finally {
            image.close()
        }
    }

    private fun imageToJpeg(image: ImageProxy, quality: Int): EncodedVideoFrame? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val nv21 = yuv420888ToNv21(image)
        val sourceW = image.width
        val sourceH = image.height
        val targetRatio = profile.width.toFloat() / profile.height.toFloat()
        val cropRect = computeCenterCropRect(sourceW, sourceH, targetRatio)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, sourceW, sourceH, null)
        val output = ByteArrayOutputStream()
        if (!yuvImage.compressToJpeg(cropRect, quality, output)) return null
        var jpeg = output.toByteArray()
        var outW = cropRect.width()
        var outH = cropRect.height()

        // Hard bound output size to profile to reduce bandwidth/latency.
        if (outW > profile.width || outH > profile.height) {
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return EncodedVideoFrame(jpeg, outW, outH)
            val scaled = Bitmap.createScaledBitmap(bitmap, profile.width, profile.height, true)
            val scaledOutput = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceAtMost(75), scaledOutput)) return null
            jpeg = scaledOutput.toByteArray()
            outW = profile.width
            outH = profile.height
            bitmap.recycle()
            scaled.recycle()
        }
        return EncodedVideoFrame(jpeg, outW, outH)
    }

    private fun computeCenterCropRect(width: Int, height: Int, targetRatio: Float): Rect {
        val srcRatio = width.toFloat() / height.toFloat()
        return if (srcRatio > targetRatio) {
            val cropW = (height * targetRatio).toInt().coerceAtLeast(2)
            val left = ((width - cropW) / 2).coerceAtLeast(0)
            Rect(left, 0, left + cropW, height)
        } else {
            val cropH = (width / targetRatio).toInt().coerceAtLeast(2)
            val top = ((height - cropH) / 2).coerceAtLeast(0)
            Rect(0, top, width, top + cropH)
        }
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val width = image.width
        val height = image.height

        val ySize = width * height
        val uvSize = width * height / 2
        val out = ByteArray(ySize + uvSize)

        var outOffset = 0
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            yBuffer.position(rowStart)
            yBuffer.get(out, outOffset, width)
            outOffset += width
        }

        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        for (row in 0 until height / 2) {
            val rowStart = row * uvRowStride
            for (col in 0 until width / 2) {
                val uvOffset = rowStart + col * uvPixelStride
                out[outOffset++] = vBuffer.get(uvOffset)
                out[outOffset++] = uBuffer.get(uvOffset)
            }
        }
        return out
    }

    private fun tuneForNetwork(sendElapsedMs: Long) {
        val baseInterval = (1000L / profile.fps.coerceAtLeast(1))
        if (activeMode == ConnectionMode.Usb) {
            if (sendElapsedMs > dynamicFrameIntervalMs * 2 && dynamicQuality > 25) {
                dynamicQuality = (dynamicQuality - 6).coerceAtLeast(25)
            } else if (sendElapsedMs < dynamicFrameIntervalMs && dynamicQuality < baseQuality) {
                dynamicQuality = (dynamicQuality + 3).coerceAtMost(baseQuality)
            }
            return
        }
        if (sendElapsedMs > dynamicFrameIntervalMs * 2) {
            if (dynamicQuality > 25) {
                dynamicQuality = (dynamicQuality - 5).coerceAtLeast(25)
            } else {
                dynamicFrameIntervalMs = (dynamicFrameIntervalMs + 8).coerceAtMost(250)
            }
        } else if (sendElapsedMs < dynamicFrameIntervalMs / 2) {
            if (dynamicQuality < baseQuality) {
                dynamicQuality = (dynamicQuality + 3).coerceAtMost(baseQuality)
            } else if (dynamicFrameIntervalMs > baseInterval) {
                dynamicFrameIntervalMs = (dynamicFrameIntervalMs - 2).coerceAtLeast(baseInterval)
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun writeWrapper(writeMutex: Mutex, output: ByteWriteChannel, wrapper: MessageWrapper) {
        val packetBytes = proto.encodeToByteArray(MessageWrapper.serializer(), wrapper)
        writeMutex.withLock {
            output.writeInt(PACKET_MAGIC)
            output.writeInt(packetBytes.size)
            output.writeFully(packetBytes)
            output.flush()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun processControlLoop(
        input: ByteReadChannel,
        output: ByteWriteChannel,
        writeMutex: Mutex
    ) {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            var magic = input.readInt()
            if (magic != PACKET_MAGIC) {
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    val b = input.readByte().toInt() and 0xFF
                    magic = (magic shl 8) or b
                    if (magic == PACKET_MAGIC) break
                }
            }
            val length = input.readInt()
            if (length <= 0 || length > 8 * 1024 * 1024) continue
            val packet = ByteArray(length)
            input.readFully(packet)
            val wrapper = runCatching { proto.decodeFromByteArray(MessageWrapper.serializer(), packet) }.getOrNull() ?: continue
            val control = wrapper.videoControl ?: continue
            val pingId = control.pingId ?: continue
            val pong = MessageWrapper(videoControl = VideoControlMessage(pongId = pingId))
            writeWrapper(writeMutex, output, pong)
        }
    }

    private suspend fun awaitCameraProvider(context: android.content.Context): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        continuation.resume(future.get())
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                },
                androidx.core.content.ContextCompat.getMainExecutor(context)
            )
        }

    private fun cleanupCamera() {
        runCatching {
            analysis?.clearAnalyzer()
            analysis = null
            cameraProvider?.unbindAll()
            analyzerExecutor?.shutdownNow()
            analyzerExecutor = null
        }
    }
}
