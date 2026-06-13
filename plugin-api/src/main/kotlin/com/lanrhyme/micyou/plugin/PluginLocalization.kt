package com.lanrhyme.micyou.plugin

/**
 * 插件本地化接口
 * 提供插件多语言支持功能
 */
interface PluginLocalization {
    /**
     * 当前语言代码，如 "zh", "en", "ja" 等
     */
    val currentLanguage: String

    /**
     * 获取本地化字符串
     * @param key 字符串键名
     * @param defaultValue 默认值，当找不到对应本地化字符串时返回
     * @return 本地化后的字符串
     */
    fun getString(key: String, defaultValue: String = key): String

    /**
     * 获取带格式的本地化字符串
     * @param key 字符串键名
     * @param formatArgs 格式化参数
     * @return 格式化后的本地化字符串
     */
    fun getString(key: String, vararg formatArgs: Any): String

    /**
     * 切换语言
     * @param languageCode 语言代码，如 "zh", "en" 等
     */
    fun setLanguage(languageCode: String)

    /**
     * 获取支持的语言列表
     * @return 支持的语言代码列表
     */
    fun getSupportedLanguages(): List<String>

    /**
     * 重新加载本地化资源
     * 当插件更新或语言切换时调用
     */
    fun reload()
}

/**
 * 插件本地化提供者接口
 * 插件可以实现此接口来提供自己的本地化资源
 */
interface PluginLocalizationProvider {
    /**
     * 获取插件的本地化字符串
     * @param languageCode 语言代码
     * @param key 字符串键名
     * @return 本地化字符串，如果没有找到返回 null
     */
    fun getLocalizedString(languageCode: String, key: String): String?

    /**
     * 获取插件支持的语言列表
     * @return 语言代码列表
     */
    fun getSupportedLanguages(): List<String>
}
