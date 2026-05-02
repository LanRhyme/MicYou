package com.lanrhyme.micyou.audio

/**
 * 音频处理组件（效果器）的接口。
 */
interface AudioEffect {
    /**
     * 处理音频采样。
     * @param input 输入缓冲区。
     * @param channelCount 音频声道数。
     * @return 处理后的缓冲区（可能是同一个数组或新数组）。
     */
    fun process(input: ShortArray, channelCount: Int): ShortArray
    
    /**
     * 重置内部状态（例如清除缓冲区、重置历史记录）。
     */
    fun reset()
    
    /**
     * 释放资源（例如关闭原生句柄）。
     */
    fun release()
}
