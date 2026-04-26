package com.lanrhyme.micyou

import java.io.BufferedReader
import java.io.InputStreamReader

import java.util.Locale as JavaLocale

actual fun setAppLocale(languageCode: String) {
    if (languageCode == "system") return
    try {
        val locale = if (languageCode.contains("-r")) {
            val parts = languageCode.split("-r")
            JavaLocale(parts[0], parts[1])
        } else {
            JavaLocale(languageCode)
        }
        JavaLocale.setDefault(locale)
    } catch (e: Exception) {
        Logger.e("Localization", "Failed to set app locale: $languageCode")
    }
}

actual fun readResourceFile(path: String): String? {
    return try {
        val classLoader = Thread.currentThread().contextClassLoader
        val fullPath = "composeResources/micyou.composeapp.generated.resources/files/$path"
        val inputStream = classLoader?.getResourceAsStream(fullPath)
        if (inputStream != null) {
            BufferedReader(InputStreamReader(inputStream, "UTF-8")).use { reader ->
                reader.readText()
            }
        } else {
            null
        }
    } catch (e: Exception) {
        Logger.e("Localization", "Failed to read resource file: $path - ${e.message}")
        null
    }
}
