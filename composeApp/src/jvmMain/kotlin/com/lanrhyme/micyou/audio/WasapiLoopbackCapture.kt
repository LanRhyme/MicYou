package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.Logger
import com.sun.jna.*
import com.sun.jna.platform.win32.*
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.W32APIOptions
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Windows WASAPI 回流采集实现
 * 使用 JNA 直接调用 Windows Core Audio API 进行系统音频采集
 */
class WasapiLoopbackCapture : LoopbackCapture {
    private var capturing = false
    private var audioCallback: ((ByteArray, Int, Int, Long) -> Unit)? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val isCapturing: Boolean get() = capturing

    override suspend fun start(sampleRate: Int, channelCount: Int) {
        if (capturing) return
        capturing = true

        Logger.i("WasapiLoopback", "Starting WASAPI loopback capture: ${sampleRate}Hz, ${channelCount}ch")

        captureJob = scope.launch {
            try {
                runCapture(sampleRate, channelCount)
            } catch (e: Exception) {
                Logger.e("WasapiLoopback", "Capture loop failed: ${e.message}", e)
            } finally {
                capturing = false
            }
        }
    }

    override fun stop() {
        capturing = false
        captureJob?.cancel()
        captureJob = null
        Logger.i("WasapiLoopback", "Loopback capture stopped")
    }

    override fun onAudioData(callback: (ByteArray, Int, Int, Long) -> Unit) {
        audioCallback = callback
    }

    private suspend fun runCapture(targetSampleRate: Int, targetChannels: Int) {
        // 初始化 COM
        Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED)
        
        var enumerator: Wasapi.IMMDeviceEnumerator? = null
        var device: Wasapi.IMMDevice? = null
        var audioClient: Wasapi.IAudioClient? = null
        var captureClient: Wasapi.IAudioCaptureClient? = null
        var mixFormatPtr: Pointer? = null

        try {
            // 1. 创建设备枚举器
            val pEnumerator = PointerByReference()
            var hr = Ole32.INSTANCE.CoCreateInstance(
                Wasapi.CLSID_MMDeviceEnumerator,
                null,
                Wasapi.CLSCTX_ALL,
                Wasapi.IID_IMMDeviceEnumerator,
                pEnumerator
            )
            if (hr.toInt() != 0) throw Exception("CoCreateInstance(IMMDeviceEnumerator) failed: $hr")
            enumerator = Wasapi.IMMDeviceEnumerator(pEnumerator.value)

            // 2. 获取默认渲染设备（系统输出）
            val pDevice = PointerByReference()
            hr = enumerator.GetDefaultAudioEndpoint(Wasapi.EDataFlow.eRender, Wasapi.ERole.eConsole, pDevice)
            if (hr.toInt() != 0) throw Exception("GetDefaultAudioEndpoint failed: $hr")
            device = Wasapi.IMMDevice(pDevice.value)

            // 3. 激活 IAudioClient
            val pAudioClient = PointerByReference()
            hr = device.Activate(Wasapi.IID_IAudioClient, Wasapi.CLSCTX_ALL, null, pAudioClient)
            if (hr.toInt() != 0) throw Exception("Activate IAudioClient failed: $hr")
            audioClient = Wasapi.IAudioClient(pAudioClient.value)

            // 4. 获取混合格式
            val pFormat = PointerByReference()
            hr = audioClient.GetMixFormat(pFormat)
            if (hr.toInt() != 0) throw Exception("GetMixFormat failed: $hr")
            mixFormatPtr = pFormat.value
            val mixFormat = Structure.newInstance(Wasapi.WAVEFORMATEX::class.java, mixFormatPtr)
            mixFormat.read()

            Logger.i("WasapiLoopback", "System Mix Format: ${mixFormat.nSamplesPerSec}Hz, ${mixFormat.nChannels}ch, ${mixFormat.wBitsPerSample}bits")

            // 5. 初始化 IAudioClient 为回环模式
            hr = audioClient.Initialize(
                Wasapi.AUDCLNT_SHAREMODE_SHARED,
                Wasapi.AUDCLNT_STREAMFLAGS_LOOPBACK,
                0L, 0L, mixFormatPtr, null
            )
            if (hr.toInt() != 0) {
                throw Exception("IAudioClient.Initialize failed: 0x${Integer.toHexString(hr.toInt())}")
            }

            // 6. 获取 IAudioCaptureClient 服务
            val pCaptureClient = PointerByReference()
            hr = audioClient.GetService(Wasapi.IID_IAudioCaptureClient, pCaptureClient)
            if (hr.toInt() != 0) throw Exception("GetService(IAudioCaptureClient) failed: $hr")
            captureClient = Wasapi.IAudioCaptureClient(pCaptureClient.value)

            // 7. 开始采集
            hr = audioClient.Start()
            if (hr.toInt() != 0) throw Exception("IAudioClient.Start failed: $hr")

            // 采集参数
            val srcChannels = mixFormat.nChannels.toInt()
            val srcSampleRate = mixFormat.nSamplesPerSec
            val srcBits = mixFormat.wBitsPerSample.toInt()
            
            var isFloat = false
            val formatTag = mixFormat.wFormatTag.toInt() and 0xFFFF
            if (formatTag == Wasapi.WAVE_FORMAT_EXTENSIBLE) {
                val ext = Structure.newInstance(Wasapi.WAVEFORMATEXTENSIBLE::class.java, mixFormatPtr)
                ext.read()
                if (ext.SubFormat == Wasapi.KSDATAFORMAT_SUBTYPE_IEEE_FLOAT) {
                    isFloat = true
                }
            } else if (formatTag == 3) {
                isFloat = true
            }

            val resampler = if (srcSampleRate != targetSampleRate) {
                ResamplerEffect().apply {
                    playbackRatio = srcSampleRate.toDouble() / targetSampleRate.toDouble()
                }
            } else null

            val pData = PointerByReference()
            val pNumFrames = IntByReference()
            val pFlags = IntByReference()
            val pPacketSize = IntByReference()

            var floatBuffer = FloatArray(0)
            var shortBuffer = ShortArray(0)

            while (capturing) {
                hr = captureClient.GetNextPacketSize(pPacketSize)
                if (hr.toInt() != 0) break
                
                val packetSize = pPacketSize.value
                if (packetSize == 0) {
                    delay(1)
                    continue
                }

                hr = captureClient.GetBuffer(pData, pNumFrames, pFlags, null, null)
                if (hr.toInt() != 0) break
                
                val numFrames = pNumFrames.value
                val dataPtr = pData.value
                val flags = pFlags.value
                
                if ((flags and 0x01) == 0) {
                    val totalSamples = numFrames * srcChannels
                    
                    if (isFloat && srcBits == 32) {
                        if (floatBuffer.size < totalSamples) floatBuffer = FloatArray(totalSamples)
                        dataPtr.read(0, floatBuffer, 0, totalSamples)
                        
                        var outShorts: ShortArray
                        if (targetChannels == 1 && srcChannels == 2) {
                            outShorts = ShortArray(numFrames)
                            for (i in 0 until numFrames) {
                                val mono = (floatBuffer[i * 2] + floatBuffer[i * 2 + 1]) / 2f
                                outShorts[i] = (mono * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                            }
                        } else {
                            outShorts = ShortArray(numFrames * targetChannels)
                            for (i in 0 until numFrames) {
                                for (c in 0 until targetChannels) {
                                    val idx = if (c < srcChannels) i * srcChannels + c else i * srcChannels
                                    outShorts[i * targetChannels + c] = (floatBuffer[idx] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                                }
                            }
                        }

                        if (resampler != null) outShorts = resampler.process(outShorts, targetChannels)
                        
                        if (outShorts.isNotEmpty()) {
                            val finalBytes = ByteArray(outShorts.size * 2)
                            ByteBuffer.wrap(finalBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outShorts)
                            audioCallback?.invoke(finalBytes, targetSampleRate, targetChannels, System.currentTimeMillis())
                        }
                    } else if (!isFloat && srcBits == 16) {
                        if (shortBuffer.size < totalSamples) shortBuffer = ShortArray(totalSamples)
                        dataPtr.read(0, shortBuffer, 0, totalSamples)
                        
                        var outShorts: ShortArray
                        if (targetChannels == 1 && srcChannels == 2) {
                            outShorts = ShortArray(numFrames)
                            for (i in 0 until numFrames) {
                                outShorts[i] = ((shortBuffer[i * 2].toInt() + shortBuffer[i * 2 + 1].toInt()) / 2).toShort()
                            }
                        } else {
                            outShorts = shortBuffer.copyOf(totalSamples)
                        }

                        if (resampler != null) outShorts = resampler.process(outShorts, targetChannels)

                        val finalBytes = ByteArray(outShorts.size * 2)
                        ByteBuffer.wrap(finalBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outShorts)
                        audioCallback?.invoke(finalBytes, targetSampleRate, targetChannels, System.currentTimeMillis())
                    }
                }

                captureClient.ReleaseBuffer(numFrames)
            }

        } catch (e: Exception) {
            Logger.e("WasapiLoopback", "Exception in capture loop: ${e.message}", e)
        } finally {
            try {
                audioClient?.Stop()
                captureClient?.Release()
                audioClient?.Release()
                device?.Release()
                enumerator?.Release()
                if (mixFormatPtr != null) Wasapi.Ole32Ext.INSTANCE.CoTaskMemFree(mixFormatPtr)
            } catch (e: Exception) { }
            Ole32.INSTANCE.CoUninitialize()
        }
    }

    private object Wasapi {
        val CLSID_MMDeviceEnumerator = Guid.GUID.fromString("{BCDE0395-E52F-467C-8E3D-C4579291692E}")
        val IID_IMMDeviceEnumerator = Guid.GUID.fromString("{A95664D2-9614-4F35-A746-DE8DB63617E6}")
        val IID_IAudioClient = Guid.GUID.fromString("{1CB9AD4C-DBFA-4c32-B178-C2F568A703B2}")
        val IID_IAudioCaptureClient = Guid.GUID.fromString("{c8adbd64-e71e-48a0-a4de-67fd9d2bd122}")
        val KSDATAFORMAT_SUBTYPE_IEEE_FLOAT = Guid.GUID.fromString("{00000003-0000-0010-8000-00AA00389B71}")

        const val CLSCTX_ALL = 23
        const val AUDCLNT_SHAREMODE_SHARED = 0
        const val AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000
        const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE

        object EDataFlow { const val eRender = 0 }
        object ERole { const val eConsole = 0 }

        @Structure.FieldOrder("wFormatTag", "nChannels", "nSamplesPerSec", "nAvgBytesPerSec", "nBlockAlign", "wBitsPerSample", "cbSize")
        open class WAVEFORMATEX : Structure() {
            @JvmField var wFormatTag: Short = 0
            @JvmField var nChannels: Short = 0
            @JvmField var nSamplesPerSec: Int = 0
            @JvmField var nAvgBytesPerSec: Int = 0
            @JvmField var nBlockAlign: Short = 0
            @JvmField var wBitsPerSample: Short = 0
            @JvmField var cbSize: Short = 0
        }

        @Structure.FieldOrder("Format", "Samples", "dwChannelMask", "SubFormat")
        class WAVEFORMATEXTENSIBLE : Structure() {
            @JvmField var Format: WAVEFORMATEX = WAVEFORMATEX()
            @JvmField var Samples: Short = 0 
            @JvmField var dwChannelMask: Int = 0
            @JvmField var SubFormat: Guid.GUID = Guid.GUID()
        }

        class IMMDeviceEnumerator(p: Pointer) : Unknown(p) {
            fun GetDefaultAudioEndpoint(dataFlow: Int, role: Int, ppDevice: PointerByReference): WinNT.HRESULT {
                return _invokeNativeObject(4, arrayOf(getPointer(), dataFlow, role, ppDevice), WinNT.HRESULT::class.java) as WinNT.HRESULT
            }
        }

        class IMMDevice(p: Pointer) : Unknown(p) {
            fun Activate(riid: Guid.GUID, dwClsCtx: Int, pActivationParams: Pointer?, ppInterface: PointerByReference): WinNT.HRESULT {
                return _invokeNativeObject(3, arrayOf(getPointer(), riid.getPointer(), dwClsCtx, pActivationParams, ppInterface), WinNT.HRESULT::class.java) as WinNT.HRESULT
            }
        }

        class IAudioClient(p: Pointer) : Unknown(p) {
            fun GetMixFormat(ppDeviceFormat: PointerByReference): WinNT.HRESULT {
                return _invokeNativeObject(8, arrayOf(getPointer(), ppDeviceFormat), WinNT.HRESULT::class.java) as WinNT.HRESULT
            }
            fun Initialize(shareMode: Int, streamFlags: Int, hnsBufferDuration: Long, hnsPeriodicity: Long, pFormat: Pointer, audioSessionGuid: Guid.GUID?): WinNT.HRESULT {
                return _invokeNativeObject(3, arrayOf(getPointer(), shareMode, streamFlags, hnsBufferDuration, hnsPeriodicity, pFormat, audioSessionGuid?.getPointer()), WinNT.HRESULT::class.java) as WinNT.HRESULT
            }
            fun GetService(riid: Guid.GUID, ppv: PointerByReference): WinNT.HRESULT {
                return _invokeNativeObject(14, arrayOf(getPointer(), riid.getPointer(), ppv), WinNT.HRESULT::class.java) as WinNT.HRESULT
            }

            fun Start(): WinNT.HRESULT = _invokeNativeObject(10, arrayOf(getPointer()), WinNT.HRESULT::class.java) as WinNT.HRESULT
            fun Stop(): WinNT.HRESULT = _invokeNativeObject(11, arrayOf(getPointer()), WinNT.HRESULT::class.java) as WinNT.HRESULT
        }

        class IAudioCaptureClient(p: Pointer) : Unknown(p) {
            fun GetBuffer(ppData: PointerByReference, pNumFrames: IntByReference, pdwFlags: IntByReference, pos: LongByReference?, qpc: LongByReference?): WinNT.HRESULT {
                return _invokeNativeObject(3, arrayOf(getPointer(), ppData, pNumFrames, pdwFlags, pos, qpc), WinNT.HRESULT::class.java) as WinNT.HRESULT
            }
            fun ReleaseBuffer(numFrames: Int): WinNT.HRESULT = _invokeNativeObject(4, arrayOf(getPointer(), numFrames), WinNT.HRESULT::class.java) as WinNT.HRESULT
            fun GetNextPacketSize(pNumFrames: IntByReference): WinNT.HRESULT = _invokeNativeObject(5, arrayOf(getPointer(), pNumFrames), WinNT.HRESULT::class.java) as WinNT.HRESULT
        }

        interface Ole32Ext : Ole32 {
            override fun CoTaskMemFree(pv: Pointer?)
            companion object {
                val INSTANCE = Native.load("ole32", Ole32Ext::class.java, W32APIOptions.DEFAULT_OPTIONS) as Ole32Ext
            }
        }
    }
}
