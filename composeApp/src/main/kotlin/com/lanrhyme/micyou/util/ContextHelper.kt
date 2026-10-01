/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/MicYou-Dev/MicYou>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version, with the MicYou Plugin Exception.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

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