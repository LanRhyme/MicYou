package com.lanrhyme.micyou.plugin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PluginPlatform {
    @SerialName("mobile")
    MOBILE,
    @SerialName("desktop")
    DESKTOP,
    @SerialName("both")
    BOTH
}
