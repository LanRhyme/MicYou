package com.lanrhyme.micyou.plugin

import com.lanrhyme.micyou.Logger
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import micyou.composeapp.generated.resources.*
import micyou.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.getString

class AndroidPluginLocalization(
    private val pluginId: String,
    private val pluginDir: File,
    private val appLanguageProvider: () -> String
) : PluginLocalization {

    private val json = Json { ignoreUnknownKeys = true }
    private val stringCache = ConcurrentHashMap<String, Map<String, String>>()
    private var currentLang: String = ""

    override val currentLanguage: String
        get() = currentLang.ifEmpty { appLanguageProvider() }

    init {
        reload()
    }

    override fun getString(key: String, defaultValue: String): String {
        val lang = currentLanguage
        val strings = stringCache[lang] ?: stringCache["en"] ?: emptyMap()
        return strings[key] ?: defaultValue
    }

    override fun getString(key: String, vararg formatArgs: Any): String {
        val template = getString(key, key)
        return try {
            template.format(*formatArgs)
        } catch (e: Exception) {
            Logger.w("AndroidPluginLocalization", "Failed to format string: $key")
            template
        }
    }

    override fun setLanguage(languageCode: String) {
        currentLang = languageCode
        reload()
    }

    override fun getSupportedLanguages(): List<String> {
        val i18nDir = File(pluginDir, "i18n")
        if (!i18nDir.exists() || !i18nDir.isDirectory) {
            return emptyList()
        }

        return i18nDir.listFiles { file ->
            file.isFile && file.name.startsWith("strings_") && file.name.endsWith(".json")
        }?.map { file ->
            file.name.removePrefix("strings_").removeSuffix(".json")
        } ?: emptyList()
    }

    override fun reload() {
        stringCache.clear()
    val i18nDir = File(pluginDir, "i18n")
        if (!i18nDir.exists() || !i18nDir.isDirectory) {
            return
        }

        i18nDir.listFiles { file ->
            file.isFile && file.name.startsWith("strings_") && file.name.endsWith(".json")
        }?.forEach { file ->
            val langCode = file.name.removePrefix("strings_").removeSuffix(".json")
            try {
                val content = file.readText()
    val strings = json.decodeFromString<Map<String, String>>(content)
                stringCache[langCode] = strings
                Logger.d("AndroidPluginLocalization", "Loaded strings for $pluginId/$langCode: ${strings.size} entries")
            } catch (e: Exception) {
                Logger.e("AndroidPluginLocalization", "Failed to load strings for $pluginId/$langCode", e)
            }
        }
    }
}

class AndroidAppPluginLocalization(
    private val stringProvider: (String) -> String,
    private val languageProvider: () -> String
) : PluginLocalization {
    override val currentLanguage: String
        get() = languageProvider()

    override fun getString(key: String, defaultValue: String): String {
        return try {
            stringProvider(key)
        } catch (e: Exception) {
            defaultValue
        }
    }

    override fun getString(key: String, vararg formatArgs: Any): String {
        val template = getString(key, key)
        return try {
            template.format(*formatArgs)
        } catch (e: Exception) {
            template
        }
    }

    override fun setLanguage(languageCode: String) {
        Logger.w("AndroidAppPluginLocalization", "Plugins cannot change app language directly")
    }

    override fun getSupportedLanguages(): List<String> {
        return listOf("zh", "en", "zh-TW", "zh-HK", "ja", "ko", "de", "fr", "es", "ru")
    }

    override fun reload() {
    }
}
