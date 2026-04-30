package com.lanrhyme.micyou

import android.media.MediaRecorder
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.audioSourceCamcorder
import micyou.composeapp.generated.resources.audioSourceMic
import micyou.composeapp.generated.resources.audioSourceUnprocessed
import micyou.composeapp.generated.resources.audioSourceVoiceCommunication
import micyou.composeapp.generated.resources.audioSourceVoicePerformance
import micyou.composeapp.generated.resources.audioSourceVoiceRecognition
import org.jetbrains.compose.resources.StringResource

enum class AndroidAudioSource(val labelRes: StringResource, val sourceId: Int) {
    Mic(Res.string.audioSourceMic, MediaRecorder.AudioSource.MIC),
    VoiceCommunication(Res.string.audioSourceVoiceCommunication, MediaRecorder.AudioSource.VOICE_COMMUNICATION),
    VoiceRecognition(Res.string.audioSourceVoiceRecognition, MediaRecorder.AudioSource.VOICE_RECOGNITION),
    VoicePerformance(Res.string.audioSourceVoicePerformance, MediaRecorder.AudioSource.VOICE_PERFORMANCE),
    Camcorder(Res.string.audioSourceCamcorder, MediaRecorder.AudioSource.CAMCORDER),
    Unprocessed(Res.string.audioSourceUnprocessed, MediaRecorder.AudioSource.UNPROCESSED)
}
