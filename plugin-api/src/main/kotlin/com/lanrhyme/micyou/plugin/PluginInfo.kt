package com.lanrhyme.micyou.plugin

data class PluginInfo(
    val manifest: PluginManifest,
    val isEnabled: Boolean = false,
    val isLoaded: Boolean = false,
    val installPath: String,
    val iconPath: String? = null
)
