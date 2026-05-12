package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.Logger
import java.io.IOException

object WebHtmlPage {
    private const val HTML_RESOURCE = "web_client.html"
    private const val JS_RESOURCE = "alpine.min.js"

    private val cachedHtml: String by lazy {
        val stream = this.javaClass.classLoader.getResourceAsStream(HTML_RESOURCE)
            ?: throw IOException("Resource not found: $HTML_RESOURCE")
        stream.bufferedReader(Charsets.UTF_8).use {
            it.readText()
                .also { text ->
                    Logger.i("WebHtmlPage", "HTML page loaded from resource, length=${text.length}")
                }
        }
    }

    private val cachedJs: ByteArray by lazy {
        val stream = this.javaClass.classLoader.getResourceAsStream(JS_RESOURCE)
            ?: throw IOException("Resource not found: $JS_RESOURCE")
        stream.use {
            it.readBytes()
                .also { bytes ->
                    Logger.i("WebHtmlPage", "JS file loaded from resource, length=${bytes.size}")
                }
        }
    }

    fun getHtml(): String = cachedHtml
    fun getJs(): ByteArray = cachedJs
}
