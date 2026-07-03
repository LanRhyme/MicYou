package com.lanrhyme.micyou.plugin

interface AudioEffectProvider {
    val id: String
    val name: String
    val description: String
    
    var isEnabled: Boolean
    
    fun process(input: ShortArray, channelCount: Int, sampleRate: Int): ShortArray
    
    fun reset()
    
    fun release()
    
    fun onConfigChanged(config: AudioConfig) {}
}
