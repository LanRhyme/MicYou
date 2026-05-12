package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.platform.wasapi.*
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Windows implementation of LoopbackCapture using WASAPI via JNA.
 */
class WindowsLoopbackCapture : LoopbackCapture {
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private val _capturedData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    override val capturedData: SharedFlow<ByteArray> = _capturedData.asSharedFlow()
    
    @Volatile
    override var isActive: Boolean = false
        private set
        
    @Volatile
    override var format: LoopbackCapture.LoopbackFormat = LoopbackCapture.LoopbackFormat(48000, 2, 16)
        private set

    override fun start(sampleRate: Int, channelCount: Int) {
        if (isActive) return
        
        isActive = true
        // Use a dedicated single thread or COINIT_MULTITHREADED carefully
        job = scope.launch {
            try {
                runCaptureLoop()
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Logger.e("WindowsLoopback", "Capture loop error: ${e.message}")
                }
            } finally {
                withContext(NonCancellable) {
                    isActive = false
                }
            }
        }
    }

    override fun stop() {
        isActive = false
        job?.cancel()
        job = null
    }

    private suspend fun runCaptureLoop() {
        Logger.i("WindowsLoopback", "Initializing WASAPI loopback...")
        
        // CoInitializeEx with COINIT_MULTITHREADED
        val hrInit = Ole32.INSTANCE.CoInitializeEx(null, WASAPIConstants.COINIT_MULTITHREADED)
        val comInitialized = hrInit.toInt() >= 0
        
        var pEnumerator: IMMDeviceEnumerator? = null
        var pDevice: IMMDevice? = null
        var pAudioClient: IAudioClient? = null
        var pCaptureClient: IAudioCaptureClient? = null
        var pFormat: Pointer? = null

        try {
            val pEnumeratorRef = PointerByReference()
            var hr = Ole32.INSTANCE.CoCreateInstance(
                WASAPIConstants.CLSID_MMDeviceEnumerator,
                null,
                WASAPIConstants.CLSCTX_ALL,
                WASAPIConstants.IID_IMMDeviceEnumerator,
                pEnumeratorRef
            )
            if (hr.toInt() < 0) throw Exception("Failed to create MMDeviceEnumerator: $hr")
            pEnumerator = IMMDeviceEnumerator(pEnumeratorRef.value)

            val pDeviceRef = PointerByReference()
            hr = pEnumerator.GetDefaultAudioEndpoint(WASAPIConstants.eRender, WASAPIConstants.eConsole, pDeviceRef)
            if (hr.toInt() < 0) throw Exception("Failed to get default endpoint: $hr")
            pDevice = IMMDevice(pDeviceRef.value)

            val pAudioClientRef = PointerByReference()
            hr = pDevice.Activate(WASAPIConstants.IID_IAudioClient, WASAPIConstants.CLSCTX_ALL, null, pAudioClientRef)
            if (hr.toInt() < 0) throw Exception("Failed to activate AudioClient: $hr")
            pAudioClient = IAudioClient(pAudioClientRef.value)

            val pFormatRef = PointerByReference()
            hr = pAudioClient.GetMixFormat(pFormatRef)
            if (hr.toInt() < 0) throw Exception("Failed to get mix format: $hr")
            pFormat = pFormatRef.value
            
            val waveFormat = WAVEFORMATEX(pFormat)
            waveFormat.read()
            
            var bits = waveFormat.wBitsPerSample.toInt()
            var isFloat = false

            if (waveFormat.wFormatTag.toInt() == 0xFFFE) { // WAVE_FORMAT_EXTENSIBLE
                val extFormat = WAVEFORMATEXTENSIBLE(pFormat)
                extFormat.read()
                bits = extFormat.Format.wBitsPerSample.toInt()
                if (extFormat.SubFormat == WASAPIConstants.KSDATAFORMAT_SUBTYPE_IEEE_FLOAT) {
                    isFloat = true
                }
            } else if (waveFormat.wFormatTag.toInt() == 0x0003) { // WAVE_FORMAT_IEEE_FLOAT
                isFloat = true
            }
            
            // Protocol format mapping
            val protocolBits = if (isFloat) 32 else bits

            Logger.i("WindowsLoopback", "Mix Format: ${waveFormat.nSamplesPerSec}Hz, ${waveFormat.nChannels} channels, $bits bits (Float=$isFloat)")
            format = LoopbackCapture.LoopbackFormat(waveFormat.nSamplesPerSec, waveFormat.nChannels.toInt(), protocolBits)

            hr = pAudioClient.Initialize(
                WASAPIConstants.AUDCLNT_SHAREMODE_SHARED,
                WASAPIConstants.AUDCLNT_STREAMFLAGS_LOOPBACK,
                0L, 0L, pFormat, null
            )
            if (hr.toInt() < 0) throw Exception("Failed to initialize AudioClient: $hr")

            val pCaptureClientRef = PointerByReference()
            hr = pAudioClient.GetService(WASAPIConstants.IID_IAudioCaptureClient, pCaptureClientRef)
            if (hr.toInt() < 0) throw Exception("Failed to get CaptureClient: $hr")
            pCaptureClient = IAudioCaptureClient(pCaptureClientRef.value)

            hr = pAudioClient.Start()
            if (hr.toInt() < 0) throw Exception("Failed to start AudioClient: $hr")

            val ppData = PointerByReference()
            val pNumFramesToRead = IntByReference()
            val pdwFlags = IntByReference()
            
            val bytesPerFrame = waveFormat.nBlockAlign.toInt()

            while (isActive && coroutineContext.isActive) {
                hr = pCaptureClient.GetBuffer(ppData, pNumFramesToRead, pdwFlags, null, null)
                if (hr.toInt() == 0) {
                    val numFrames = pNumFramesToRead.value
                    if (numFrames > 0) {
                        val dataSize = numFrames * bytesPerFrame
                        val data = ppData.value.getByteArray(0, dataSize)
                        _capturedData.emit(data)
                        pCaptureClient.ReleaseBuffer(numFrames)
                    }
                }
                delay(10)
            }
            
            pAudioClient.Stop()
            
        } finally {
            withContext(NonCancellable) {
                pCaptureClient?.Release()
                pAudioClient?.Release()
                pDevice?.Release()
                pEnumerator?.Release()
                if (pFormat != null) {
                    Ole32.INSTANCE.CoTaskMemFree(pFormat)
                }
                if (comInitialized) {
                    Ole32.INSTANCE.CoUninitialize()
                }
            }
        }
    }
}
