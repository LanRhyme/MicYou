package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.R

import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.StringRes

enum class AndroidAudioSource(@StringRes val labelRes: Int, val sourceId: Int) {
    Mic(R.string.audioSourceMic, MediaRecorder.AudioSource.MIC),
    VoiceCommunication(R.string.audioSourceVoiceCommunication, MediaRecorder.AudioSource.VOICE_COMMUNICATION),
    VoiceRecognition(R.string.audioSourceVoiceRecognition, MediaRecorder.AudioSource.VOICE_RECOGNITION),

    // Use raw integer 9 for VOICE_PERFORMANCE to avoid NoClassDefFoundError on API < 29
    VoicePerformance(R.string.audioSourceVoicePerformance, 9),
    Camcorder(R.string.audioSourceCamcorder, MediaRecorder.AudioSource.CAMCORDER),
    Unprocessed(R.string.audioSourceUnprocessed, MediaRecorder.AudioSource.UNPROCESSED)
}

data class AudioSourceOption(
    val name: String,
    @StringRes val labelRes: Int? = null,
    val label: String? = null
)

fun getAudioSourceOptions(): List<AudioSourceOption> {
    return AndroidAudioSource.entries
        .filter { it.sourceId != 9 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q }
        .map { AudioSourceOption(it.name, it.labelRes) }
}
