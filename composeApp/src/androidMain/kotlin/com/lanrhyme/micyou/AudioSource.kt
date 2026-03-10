package com.lanrhyme.micyou

import android.media.MediaRecorder

enum class AndroidAudioSource(val label: String, val sourceId: Int) {
    Mic("MIC (默认)", MediaRecorder.AudioSource.MIC),
    VoiceCommunication("VOICE_COMMUNICATION (VoIP优化)", MediaRecorder.AudioSource.VOICE_COMMUNICATION),
    VoiceRecognition("VOICE_RECOGNITION (语音识别)", MediaRecorder.AudioSource.VOICE_RECOGNITION),
    VoicePerformance("VOICE_PERFORMANCE (低延迟)", MediaRecorder.AudioSource.VOICE_PERFORMANCE),
    Camcorder("CAMCORDER (摄像机)", MediaRecorder.AudioSource.CAMCORDER),
    Unprocessed("UNPROCESSED (原始音频)", MediaRecorder.AudioSource.UNPROCESSED)
}
