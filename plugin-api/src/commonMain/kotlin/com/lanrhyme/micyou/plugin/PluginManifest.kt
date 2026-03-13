package com.lanrhyme.micyou.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val tags: List<String> = emptyList(),
    val platform: PluginPlatform = PluginPlatform.BOTH,
    @SerialName("minApiVersion")
    val minApiVersion: String,
    val permissions: List<String> = emptyList(),
    @SerialName("mainClass")
    val mainClass: String
)
