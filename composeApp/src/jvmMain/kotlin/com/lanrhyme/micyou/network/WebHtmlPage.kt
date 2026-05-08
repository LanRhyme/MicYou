package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.Logger
import java.io.IOException

object WebHtmlPage {
    private const val HOP_LENGTH = 256
    private const val RESOURCE_PATH = "web_audio.html"

    private val htmlTemplate: String by lazy {
        val stream = this.javaClass.classLoader.getResourceAsStream(RESOURCE_PATH)
            ?: throw IOException("Resource not found: $RESOURCE_PATH")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun getHtml(): String {
        return htmlTemplate.replace("__HOP_LENGTH__", HOP_LENGTH.toString())
            .also {
                Logger.i("WebHtmlPage", "HTML page loaded from resource, length=${it.length}")
            }
    }
}
