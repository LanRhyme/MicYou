package com.lanrhyme.micyou

/**
 * 应用全局常量定义
 */
object Constants {
    // ==================== 音频处理常量 ====================
    /** RNNoise/Ulunas 噪声处理的帧大小 (10ms at 48kHz) */
    const val AUDIO_FRAME_SIZE = 480

    /** Ulunas 模型的 FFT 窗口大小 */
    const val ULUNAS_WINDOW_SIZE = 960

    // ==================== 网络传输常量 ====================
    /** 最大数据包大小限制 (2MB)，防止恶意数据包攻击 */
    const val MAX_PACKET_SIZE = 2 * 1024 * 1024 // 2MB

    // 注意：PACKET_MAGIC 定义在 Protocol.kt 中，使用 PACKET_MAGIC 常量

    // ==================== 超时配置 ====================
    /** 网络服务器停止操作超时时间 (毫秒) */
    const val SERVER_STOP_TIMEOUT_MS = 5000L

    /** 应用退出时恢复默认麦克风的超时时间 (毫秒) */
    const val EXIT_CLEANUP_TIMEOUT_MS = 3000L

    /** TCP 连接超时时间 (毫秒) */
    const val TCP_CONNECTION_TIMEOUT_MS = 10000L

    /** 连接存活检测：超过该时间未收到对端任何数据(pong 每秒一次)则判定连接已死并主动断开 (毫秒) */
    const val CONNECTION_LIVENESS_TIMEOUT_MS = 5000L

    /** 连接存活检测的轮询间隔 (毫秒) */
    const val CONNECTION_LIVENESS_CHECK_INTERVAL_MS = 1000L

    // ==================== 端口配置 ====================
    /** 默认 TCP 端口 (Wi-Fi / USB 模式) */
    const val DEFAULT_TCP_PORT = 6000

    /** 默认 Web/HTTPS 端口 */
    const val DEFAULT_WEB_PORT = 8443

    /** 默认 UDP 端口 (TCP 端口 + UDP_PORT_OFFSET，见 Protocol.kt) */
    const val DEFAULT_UDP_PORT = DEFAULT_TCP_PORT + UDP_PORT_OFFSET

    // ==================== Channel 容量配置 ====================
    /** 音频包处理通道容量。需容纳 ~500ms+ 缓冲以应对 WiFi 抖动；
        小包模式 (1.4KB ≈ 7.3ms/pkt) 需要更多槽位：128 × 7.3ms ≈ 934ms */
    const val AUDIO_PACKET_CHANNEL_CAPACITY = 128

    /** 控制消息发送通道容量 */
    const val MESSAGE_CHANNEL_CAPACITY = 64
}