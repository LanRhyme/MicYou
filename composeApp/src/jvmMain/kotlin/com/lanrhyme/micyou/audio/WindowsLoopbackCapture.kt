package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.platform.wasapi.*
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.Guid
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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _capturedData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    override val capturedData: SharedFlow<ByteArray> = _capturedData.asSharedFlow()
    
    override var isActive: Boolean = false
        private set
        
    override var format: LoopbackCapture.LoopbackFormat = LoopbackCapture.LoopbackFormat(48000, 2, 16)
        private set

    override fun start(sampleRate: Int, channelCount: Int) {
        if (isActive) return
        
        isActive = true
        job = scope.launch {
            try {
                runCaptureLoop()
            } catch (e: Exception) {
                Logger.e("WindowsLoopback", "Capture loop error: ${e.message}")
            } finally {
                stop()
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
        
        var hr = Ole32.INSTANCE.CoInitialize(null)
        val comInitialized = hr.toInt() >= 0
        
        try {
            val pEnumeratorRef = PointerByReference()
            hr = Ole32.INSTANCE.CoCreateInstance(
                WASAPIConstants.CLSID_MMDeviceEnumerator,
                null,
                WASAPIConstants.CLSCTX_ALL,
                WASAPIConstants.IID_IMMDeviceEnumerator,
                pEnumeratorRef
            )
            if (hr.toInt() < 0) throw Exception("Failed to create MMDeviceEnumerator: $hr")
            val pEnumerator = IMMDeviceEnumerator(pEnumeratorRef.value)

            val pDeviceRef = PointerByReference()
            hr = pEnumerator.GetDefaultAudioEndpoint(WASAPIConstants.eRender, WASAPIConstants.eConsole, pDeviceRef)
            if (hr.toInt() < 0) throw Exception("Failed to get default endpoint: $hr")
            val pDevice = IMMDevice(pDeviceRef.value)

            val pAudioClientRef = PointerByReference()
            hr = pDevice.Activate(WASAPIConstants.IID_IAudioClient, WASAPIConstants.CLSCTX_ALL, null, pAudioClientRef)
            if (hr.toInt() < 0) throw Exception("Failed to activate AudioClient: $hr")
            val pAudioClient = IAudioClient(pAudioClientRef.value)

            val pFormatRef = PointerByReference()
            hr = pAudioClient.GetMixFormat(pFormatRef)
            if (hr.toInt() < 0) throw Exception("Failed to get mix format: $hr")
            val pFormat = pFormatRef.value
            val waveFormat = WAVEFORMATEX()
            waveFormat.useMemory(pFormat)
            waveFormat.read()
            
            Logger.i("WindowsLoopback", "Mix Format: ${waveFormat.nSamplesPerSec}Hz, ${waveFormat.nChannels} channels, ${waveFormat.wBitsPerSample} bits")
            format = LoopbackCapture.LoopbackFormat(waveFormat.nSamplesPerSec, waveFormat.nChannels.toInt(), waveFormat.wBitsPerSample.toInt())

            hr = pAudioClient.Initialize(
                WASAPIConstants.AUDCLNT_SHAREMODE_SHARED,
                WASAPIConstants.AUDCLNT_STREAMFLAGS_LOOPBACK,
                0L, 0L, pFormat, null
            )
            if (hr.toInt() < 0) throw Exception("Failed to initialize AudioClient: $hr")

            val pCaptureClientRef = PointerByReference()
            hr = pAudioClient.GetService(WASAPIConstants.IID_IAudioCaptureClient, pCaptureClientRef)
            if (hr.toInt() < 0) throw Exception("Failed to get CaptureClient: $hr")
            val pCaptureClient = IAudioCaptureClient(pCaptureClientRef.value)

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
                delay(10) // Small sleep to prevent CPU spiking
            }
            
            pAudioClient.Stop()
            
            // Explicit Release
            pCaptureClient.Release()
            pAudioClient.Release()
            pDevice.Release()
            pEnumerator.Release()
            Ole32.INSTANCE.CoTaskMemFree(pFormat)
            
        } catch (e: Exception) {
            Logger.e("WindowsLoopback", "Error: ${e.message}")
            throw e
        } finally {
            if (comInitialized) {
                Ole32.INSTANCE.CoUninitialize()
            }
        }
    }
}
