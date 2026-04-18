package com.lanrhyme.micyou.audio

/**
 * 音频处理相关的异常类。
 *
 * 用于表示音频处理过程中出现的各种错误情况，
 * 如模型加载失败、音频设备初始化失败、处理错误等。
 */
class AudioProcessingException(
    message: String,
    cause: Throwable? = null,
    val errorType: ErrorType? = null
) : Exception(message, cause) {

    /**
     * 错误类型枚举
     */
    enum class ErrorType {
        /** 模型加载失败 */
        MODEL_LOAD_FAILED,
        /** 模型文件不存在 */
        MODEL_FILE_NOT_FOUND,
        /** ONNX 运行时错误 */
        ONNX_RUNTIME_ERROR,
        /** 音频设备初始化失败 */
        DEVICE_INIT_FAILED,
        /** 音频处理失败 */
        PROCESSING_FAILED,
        /** 无效输入 */
        INVALID_INPUT,
        /** 资源已释放 */
        RESOURCE_DESTROYED
    }

    constructor(message: String, errorType: ErrorType) : this(message, null, errorType)
}