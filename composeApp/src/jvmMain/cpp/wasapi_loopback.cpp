#include <jni.h>
#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <iostream>
#include <vector>
#include <atomic>
#include <thread>

// Header generated for com.lanrhyme.micyou.audio.WasapiLoopbackNative
#ifndef _Included_com_lanrhyme_micyou_audio_WasapiLoopbackNative
#define _Included_com_lanrhyme_micyou_audio_WasapiLoopbackNative
#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jint JNICALL Java_com_lanrhyme_micyou_audio_WasapiLoopbackNative_nativeStart(JNIEnv *, jobject, jint, jint);
JNIEXPORT void JNICALL Java_com_lanrhyme_micyou_audio_WasapiLoopbackNative_nativeStop(JNIEnv *, jobject);

#ifdef __cplusplus
}
#endif
#endif

// Global state for capture
std::atomic<bool> g_capturing(false);
std::thread g_captureThread;
JavaVM* g_jvm = nullptr;
jobject g_callbackObj = nullptr;
jmethodID g_onAudioDataMethod = nullptr;

static int g_callbackCount = 0;
void OnAudioData(const BYTE* data, UINT32 frames, int channels, int sampleRate) {
    if (!g_jvm || !g_callbackObj || !g_onAudioDataMethod) return;

    g_callbackCount++;
    if (g_callbackCount % 100 == 1) {
        // Simple console output for diagnostics
        std::cout << "[WasapiNative] Callback invoked, frames: " << frames << ", total: " << g_callbackCount << std::endl;
    }

    JNIEnv* env = nullptr;
    jint res = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    bool detached = false;
    if (res == JNI_EDETACHED) {
        g_jvm->AttachCurrentThread((void**)&env, NULL);
        detached = true;
    }

    if (env) {
        jsize byteCount = frames * channels * 2; // Assuming 16-bit PCM
        jbyteArray byteArray = env->NewByteArray(byteCount);
        env->SetByteArrayRegion(byteArray, 0, byteCount, (const jbyte*)data);
        
        env->CallVoidMethod(g_callbackObj, g_onAudioDataMethod, byteArray, (jint)sampleRate, (jint)channels);
        
        env->DeleteLocalRef(byteArray);
    }

    if (detached) {
        g_jvm->DetachCurrentThread();
    }
}

void CaptureLoop(int targetSampleRate, int targetChannels) {
    HRESULT hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    if (FAILED(hr)) return;

    IMMDeviceEnumerator* pEnumerator = NULL;
    IMMDevice* pDevice = NULL;
    IAudioClient* pAudioClient = NULL;
    IAudioCaptureClient* pCaptureClient = NULL;
    WAVEFORMATEX* pwfx = NULL;

    try {
        hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), NULL, CLSCTX_ALL, __uuidof(IMMDeviceEnumerator), (void**)&pEnumerator);
        if (FAILED(hr)) throw hr;

        hr = pEnumerator->GetDefaultAudioEndpoint(eRender, eMultimedia, &pDevice);
        if (FAILED(hr)) throw hr;

        hr = pDevice->Activate(__uuidof(IAudioClient), CLSCTX_ALL, NULL, (void**)&pAudioClient);
        if (FAILED(hr)) throw hr;

        hr = pAudioClient->GetMixFormat(&pwfx);
        if (FAILED(hr)) throw hr;

        // Loopback requires SHARED mode and specific format
        REFERENCE_TIME hnsRequestedDuration = 10000000; // 1 second
        hr = pAudioClient->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK, hnsRequestedDuration, 0, pwfx, NULL);
        if (FAILED(hr)) throw hr;

        hr = pAudioClient->GetService(__uuidof(IAudioCaptureClient), (void**)&pCaptureClient);
        if (FAILED(hr)) throw hr;

        hr = pAudioClient->Start();
        if (FAILED(hr)) throw hr;

        UINT32 packetSize = 0;
        while (g_capturing) {
            hr = pCaptureClient->GetNextPacketSize(&packetSize);
            if (FAILED(hr)) break;

            if (packetSize == 0) {
                Sleep(1);
                continue;
            }

            BYTE* pData;
            UINT32 numFramesRead;
            DWORD flags;

            hr = pCaptureClient->GetBuffer(&pData, &numFramesRead, &flags, NULL, NULL);
            if (FAILED(hr)) break;

            if (!(flags & AUDCLNT_BUFFERFLAGS_SILENT)) {
                // Here we should ideally resample and convert to 16-bit PCM if needed.
                // For now, let's assume pwfx is what we get and we just pass it back.
                // Simple conversion to 16-bit if it's float
                if (pwfx->wFormatTag == WAVE_FORMAT_IEEE_FLOAT || 
                   (pwfx->wFormatTag == WAVE_FORMAT_EXTENSIBLE && 
                    ((WAVEFORMATEXTENSIBLE*)pwfx)->SubFormat == KSDATAFORMAT_SUBTYPE_IEEE_FLOAT)) {
                    
                    std::vector<short> shortBuffer(numFramesRead * pwfx->nChannels);
                    float* floatData = (float*)pData;
                    for (UINT32 i = 0; i < numFramesRead * pwfx->nChannels; i++) {
                        float sample = floatData[i];
                        if (sample > 1.0f) sample = 1.0f;
                        if (sample < -1.0f) sample = -1.0f;
                        shortBuffer[i] = (short)(sample * 32767.0f);
                    }
                    OnAudioData((const BYTE*)shortBuffer.data(), numFramesRead, pwfx->nChannels, pwfx->nSamplesPerSec);
                } else if (pwfx->wBitsPerSample == 16) {
                    OnAudioData(pData, numFramesRead, pwfx->nChannels, pwfx->nSamplesPerSec);
                }
            }

            pCaptureClient->ReleaseBuffer(numFramesRead);
        }

        pAudioClient->Stop();
    } catch (HRESULT e) {
        // Log error
    }

    if (pwfx) CoTaskMemFree(pwfx);
    if (pCaptureClient) pCaptureClient->Release();
    if (pAudioClient) pAudioClient->Release();
    if (pDevice) pDevice->Release();
    if (pEnumerator) pEnumerator->Release();

    CoUninitialize();
}

JNIEXPORT jint JNICALL Java_com_lanrhyme_micyou_audio_WasapiLoopbackNative_nativeStart(JNIEnv *env, jobject obj, jint sampleRate, jint channels) {
    if (g_capturing) return 0;

    env->GetJavaVM(&g_jvm);
    g_callbackObj = env->NewGlobalRef(obj);
    jclass cls = env->GetObjectClass(obj);
    g_onAudioDataMethod = env->GetMethodID(cls, "onAudioData", "([BII)V");

    g_capturing = true;
    g_captureThread = std::thread(CaptureLoop, (int)sampleRate, (int)channels);

    return 0;
}

JNIEXPORT void JNICALL Java_com_lanrhyme_micyou_audio_WasapiLoopbackNative_nativeStop(JNIEnv *env, jobject obj) {
    if (!g_capturing) return;

    g_capturing = false;
    if (g_captureThread.joinable()) {
        g_captureThread.join();
    }

    if (g_callbackObj) {
        env->DeleteGlobalRef(g_callbackObj);
        g_callbackObj = nullptr;
    }
}
