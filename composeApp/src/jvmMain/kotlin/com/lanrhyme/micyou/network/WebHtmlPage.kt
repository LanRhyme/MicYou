package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.Logger
import java.io.IOException

object WebHtmlPage {
    private const val RESOURCE_PATH = "web_client.html"

    private val html: String by lazy {
        val stream = this.javaClass.classLoader.getResourceAsStream(RESOURCE_PATH)
            ?: throw IOException("Resource not found: $RESOURCE_PATH")
        stream.bufferedReader(Charsets.UTF_8).use {
            it.readText()
                .also { text ->
                    Logger.i("WebHtmlPage", "HTML page loaded from resource, length=${text.length}")
                }
        }
    }

    fun getHtml(): String = html
}
