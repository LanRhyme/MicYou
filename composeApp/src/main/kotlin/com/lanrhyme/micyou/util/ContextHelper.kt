package com.lanrhyme.micyou.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale as JavaLocale
import com.lanrhyme.micyou.util.ContextHelper

/**
 * Android 应用上下文助手类。
 *
 * 支持动态语言切换：通过 setLocale 设置语言后，getContext 返回的 Context 会自动
 * 使用对应语言的资源配置。
 */
object ContextHelper {
    private var applicationContext: Context? = null
    private var locale: JavaLocale? = null

    fun init(context: Context) {
        applicationContext = context.applicationContext
    }

    fun setLocale(locale: JavaLocale?) {
        this.locale = locale
    }

    fun getContext(): Context? {
        val base = applicationContext ?: return null
        val locale = this.locale
        return if (locale != null) {
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            base.createConfigurationContext(config)
        } else {
            base
        }
    }
}