package com.lanrhyme.micyou.platform.wasapi

import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

@Structure.FieldOrder("wFormatTag", "nChannels", "nSamplesPerSec", "nAvgBytesPerSec", "nBlockAlign", "wBitsPerSample", "cbSize")
open class WAVEFORMATEX : Structure {
    @JvmField var wFormatTag: Short = 0
    @JvmField var nChannels: Short = 0
    @JvmField var nSamplesPerSec: Int = 0
    @JvmField var nAvgBytesPerSec: Int = 0
    @JvmField var nBlockAlign: Short = 0
    @JvmField var wBitsPerSample: Short = 0
    @JvmField var cbSize: Short = 0

    constructor() : super()
    constructor(p: Pointer) : super(p)
}

@Structure.FieldOrder("Format", "Samples", "dwChannelMask", "SubFormat")
open class WAVEFORMATEXTENSIBLE : Structure {
    @JvmField var Format: WAVEFORMATEX = WAVEFORMATEX()
    @JvmField var Samples: Short = 0
    @JvmField var dwChannelMask: Int = 0
    @JvmField var SubFormat: GUID = GUID()

    constructor() : super()
    constructor(p: Pointer) : super(p)
}

class IMMDeviceEnumerator(p: Pointer) : Unknown(p) {
    fun GetDefaultAudioEndpoint(dataFlow: Int, role: Int, ppDevice: PointerByReference): WinNT.HRESULT {
        return _invokeNativeObject(4, arrayOf(this.getPointer(), dataFlow, role, ppDevice), WinNT.HRESULT::class.java) as WinNT.HRESULT
    }
}

class IMMDevice(p: Pointer) : Unknown(p) {
    fun Activate(iid: GUID, dwClsCtx: Int, pActivationParams: Pointer?, ppInterface: PointerByReference): WinNT.HRESULT {
        return _invokeNativeObject(3, arrayOf(this.getPointer(), iid, pActivationParams, ppInterface), WinNT.HRESULT::class.java) as WinNT.HRESULT
    }
}

class IAudioClient(p: Pointer) : Unknown(p) {
    fun GetMixFormat(ppDeviceFormat: PointerByReference): WinNT.HRESULT {
        return _invokeNativeObject(8, arrayOf(this.getPointer(), ppDeviceFormat), WinNT.HRESULT::class.java) as WinNT.HRESULT
    }

    fun Initialize(shareMode: Int, streamFlags: Int, hnsBufferDuration: Long, hnsPeriodicity: Long, pFormat: Pointer, audioSessionGuid: GUID?): WinNT.HRESULT {
        return _invokeNativeObject(3, arrayOf(this.getPointer(), shareMode, streamFlags, hnsBufferDuration, hnsPeriodicity, pFormat, audioSessionGuid), WinNT.HRESULT::class.java) as WinNT.HRESULT
    }

    fun GetBufferSize(pNumBufferFrames: IntByReference): WinNT.HRESULT {
        return _invokeNativeObject(4, arrayOf(this.getPointer(), pNumBufferFrames), WinNT.HRESULT::class.java) as WinNT.HRESULT
    }

    fun GetService(riid: GUID, ppv: PointerByReference): WinNT.HRESULT {
        return _invokeNativeObject(14, arrayOf(this.getPointer(), riid, ppv), WinNT.HRESULT::class.java) as WinNT.HRESULT
    }

    fun Start(): WinNT.HRESULT {
        return _invokeNativeObject(10, arrayOf(this.getPointer()), WinNT.HRESULT::class.java) as WinNT.HRESULT
    }

    fun Stop(): WinNT.HRESULT {
        return _invokeNativeObject(11, arrayOf(this.getPointer()), WinNT.HRESULT::class.java) as WinNT.HRESULT
    }
}

class IAudioCaptureClient(p: Pointer) : Unknown(p) {
    fun GetBuffer(ppData: PointerByReference, pNumFramesToRead: IntByReference, pdwFlags: IntByReference, pu64DevicePosition: Pointer?, pu64QPCPosition: Pointer?): WinNT.HRESULT {
        return _invokeNativeObject(3, arrayOf(this.getPointer(), ppData, pNumFramesToRead, pdwFlags, pu64DevicePosition, pu64QPCPosition), WinNT.HRESULT::class.java) as WinNT.HRESULT
    }

    fun ReleaseBuffer(numFramesRead: Int): WinNT.HRESULT {
        return _invokeNativeObject(4, arrayOf(this.getPointer(), numFramesRead), WinNT.HRESULT::class.java) as WinNT.HRESULT
    }
}

object WASAPIConstants {
    val CLSID_MMDeviceEnumerator = GUID.fromString("BCDE0395-E52F-467C-8E3D-C4579291692E")
    val IID_IMMDeviceEnumerator = GUID.fromString("A95664D2-9614-4F35-A746-DE8DB63617E6")
    val IID_IAudioClient = GUID.fromString("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2")
    val IID_IAudioCaptureClient = GUID.fromString("C8ADBD64-E71E-48a0-A4DE-185C395CD317")
    
    const val CLSCTX_ALL = 0x17
    const val eRender = 0
    const val eConsole = 0
    const val AUDCLNT_SHAREMODE_SHARED = 0
    const val AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000
    
    const val COINIT_MULTITHREADED = 0x0
    
    val KSDATAFORMAT_SUBTYPE_IEEE_FLOAT = GUID.fromString("00000003-0000-0010-8000-00AA00389B71")
}
