# Review of AEC and Two-Way Audio Plan

## Claude's Plan Overview
1.  **Android AEC Switch:** Add `enableAEC` to `AudioEngine.android.kt` along with UI and state management.
2.  **Two-Way Audio (PC to Android):** Capture system audio on JVM via a virtual audio device and send it back to Android using a new `AudioPlaybackMessage`. Play it on Android using `AudioTrack`.
3.  **UI Integration:** Add the speaker mode toggle in the UI.

## My Review Feedback
- **AEC & VOICE_COMMUNICATION:** The plan correctly identifies the need for hardware AEC (`AcousticEchoCanceler`). It's crucial to ensure `MediaRecorder.AudioSource.VOICE_COMMUNICATION` is used when AEC is enabled, as hardware AEC is usually tied to this audio source.
- **Protocol:** Adding `AudioPlaybackMessage` to `MessageWrapper` is exactly the right approach to piggyback on the existing network connection.
- **Playback & Capture Details:**
    - On Android, `AudioTrack` should be configured carefully (e.g., using `STREAM_VOICE_CALL`) to coordinate with the AEC pipeline.
    - On PC, capturing loopback audio might require external dependencies or careful handling depending on the OS (Windows WASAPI loopback, macOS BlackHole, Linux PipeWire).

## Conclusion
The plan is sound. We should proceed with implementation.
