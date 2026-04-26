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
        val context = ContextHelper.getContext()
        val resources = context?.resources
        val config = resources?.configuration
        config?.setLocale(locale)
        if (context != null && resources != null && config != null) {
            context.createConfigurationContext(config)
            resources.updateConfiguration(config, resources.displayMetrics)
        }
    } catch (e: Exception) {
        Logger.e("Localization", "Failed to set app locale: $languageCode")
    }
}

actual fun readResourceFile(path: String): String? {
    return try {
        val context = ContextHelper.getContext() ?: return null
        val assetManager = context.assets
        val fullPath = "composeResources/micyou.composeapp.generated.resources/files/$path"
        BufferedReader(InputStreamReader(assetManager.open(fullPath), "UTF-8")).use { reader ->
            reader.readText()
        }
    } catch (e: Exception) {
        Logger.e("Localization", "Failed to read resource file: $path - ${e.message}")
        null
    }
}
