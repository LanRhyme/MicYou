package com.lanrhyme.androidmic_md

enum class PlatformType {
    Android, Desktop
}

interface Platform {
    val name: String
    val type: PlatformType
}

expect fun getPlatform(): Platform
