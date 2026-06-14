package com.lanrhyme.micyou.plugin

interface AudioEffectPlugin : Plugin {
    val audioEffectProvider: AudioEffectProvider
    
    val effectPriority: Int get() = 100
    
    override fun onEnable() {
        super.onEnable()
    }
    
    override fun onDisable() {
        super.onDisable()
        audioEffectProvider.release()
    }
}
