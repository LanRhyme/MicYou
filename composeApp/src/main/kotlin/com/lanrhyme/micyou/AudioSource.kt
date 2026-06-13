package com.lanrhyme.micyou

import android.media.MediaRecorder
import androidx.annotation.StringRes

enum class AndroidAudioSource(@StringRes val labelRes: Int, val sourceId: Int) {
    Mic(R.string.audioSourceMic, MediaRecorder.AudioSource.MIC),
    VoiceCommunication(R.string.audioSourceVoiceCommunication, MediaRecorder.AudioSource.VOICE_COMMUNICATION),
    VoiceRecognition(R.string.audioSourceVoiceRecognition, MediaRecorder.AudioSource.VOICE_RECOGNITION),
    VoicePerformance(R.string.audioSourceVoicePerformance, MediaRecorder.AudioSource.VOICE_PERFORMANCE),
    Camcorder(R.string.audioSourceCamcorder, MediaRecorder.AudioSource.CAMCORDER),
    Unprocessed(R.string.audioSourceUnprocessed, MediaRecorder.AudioSource.UNPROCESSED)
}
