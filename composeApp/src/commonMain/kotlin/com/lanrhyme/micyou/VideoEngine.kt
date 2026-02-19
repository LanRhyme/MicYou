package com.lanrhyme.micyou

import kotlinx.coroutines.flow.Flow

expect class VideoEngine() {
    val streamState: Flow<VideoStreamState>
    val lastError: Flow<String?>
    val latestFrame: Flow<VideoFrameUi?>
    val stats: Flow<VideoStats>
    val rttMs: Flow<Long?>
    val virtualCameraBinding: Flow<String?>

    suspend fun start(
        ip: String,
        port: Int,
        mode: ConnectionMode,
        isClient: Boolean,
        profile: VideoProfile,
        jpegQuality: Int
    )

    fun updateConfig(profile: VideoProfile, jpegQuality: Int)
    fun switchCamera()
    fun restartVirtualCamera()
    fun requestRttTest()
    fun stop()
}
