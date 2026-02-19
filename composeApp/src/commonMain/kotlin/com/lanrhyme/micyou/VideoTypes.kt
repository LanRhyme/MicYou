package com.lanrhyme.micyou

enum class VideoStreamState {
    Idle, Connecting, Streaming, Error
}

enum class VideoProfile(
    val width: Int,
    val height: Int,
    val fps: Int,
    val label: String
) {
    FHD_1080P_30(1920, 1080, 30, "1080p 30fps"),
    HD_720P_24(1280, 720, 24, "720p 24fps"),
    SD_480P_30(854, 480, 30, "480p 30fps")
}

enum class CameraFacing(val value: Int) {
    Back(0),
    Front(1)
}

data class VideoFrameUi(
    val sequenceNumber: Int,
    val timestampMs: Long,
    val width: Int,
    val height: Int,
    val jpegBytes: ByteArray,
    val receivedAtMs: Long
)

data class VideoStats(
    val fps: Int = 0,
    val latencyMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0
)
