package com.lanrhyme.androidmic_md

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
    override val type: PlatformType = PlatformType.Desktop
}

actual fun getPlatform(): Platform = JVMPlatform()
