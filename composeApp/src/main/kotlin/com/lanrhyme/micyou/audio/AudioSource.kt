/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/MicYou-Dev/MicYou>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version, with the MicYou Plugin Exception.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

package com.lanrhyme.micyou.audio
import com.lanrhyme.micyou.R

import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.StringRes
import com.lanrhyme.micyou.audio.AndroidAudioSource
import com.lanrhyme.micyou.audio.AudioSourceOption
import com.lanrhyme.micyou.audio.getAudioSourceOptions

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