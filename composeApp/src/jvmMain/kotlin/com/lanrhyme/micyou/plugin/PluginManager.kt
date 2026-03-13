package com.lanrhyme.micyou.plugin

import com.lanrhyme.micyou.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLClassLoader
import java.util.prefs.Preferences
import java.util.zip.ZipFile

class PluginManager(private val pluginsDir: File) {
    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val plugins: StateFlow<List<PluginInfo>> = _plugins.asStateFlow()

    private val loadedPlugins = mutableMapOf<String, Plugin>()
    private val classLoaders = mutableMapOf<String, URLClassLoader>()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        pluginsDir.mkdirs()
        scanPlugins()
    }

    fun scanPlugins() {
        val pluginList = mutableListOf<PluginInfo>()
        pluginsDir.listFiles()?.filter { it.isDirectory }?.forEach { pluginDir ->
            val manifestFile = File(pluginDir, "plugin.json")
            if (manifestFile.exists()) {
                try {
                    val manifest = json.decodeFromString<PluginManifest>(manifestFile.readText())
                    val iconFile = File(pluginDir, "icon.png")
                    pluginList.add(
                        PluginInfo(
                            manifest = manifest,
                            isEnabled = isPluginEnabled(manifest.id),
                            isLoaded = loadedPlugins.containsKey(manifest.id),
                            installPath = pluginDir.absolutePath,
                            iconPath = if (iconFile.exists()) iconFile.absolutePath else null
                        )
                    )
                } catch (e: Exception) {
                    Logger.e("PluginManager", "Failed to load plugin manifest from ${pluginDir.name}", e)
                }
            }
        }
        _plugins.value = pluginList
    }

    fun importPlugin(pluginFile: File): Result<PluginInfo> {
        return try {
            val tempDir = File(pluginsDir, "temp_${System.currentTimeMillis()}")
            tempDir.mkdirs()

            ZipFile(pluginFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val file = File(tempDir, entry.name)
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        zip.getInputStream(entry).copyTo(file.outputStream())
                    }
                }
            }

            val manifestFile = File(tempDir, "plugin.json")
            if (!manifestFile.exists()) {
                tempDir.deleteRecursively()
                return Result.failure(Exception("plugin.json not found"))
            }

            val manifest = json.decodeFromString<PluginManifest>(manifestFile.readText())
            val targetDir = File(pluginsDir, manifest.id.replace(".", "_"))

            if (targetDir.exists()) {
                tempDir.deleteRecursively()
                return Result.failure(Exception("Plugin ${manifest.id} already installed"))
            }

            tempDir.renameTo(targetDir)
            scanPlugins()

            val pluginInfo = _plugins.value.find { it.manifest.id == manifest.id }
                ?: return Result.failure(Exception("Failed to find imported plugin"))

            Result.success(pluginInfo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun enablePlugin(pluginId: String): Result<Unit> {
        return try {
            val info = _plugins.value.find { it.manifest.id == pluginId }
                ?: return Result.failure(Exception("Plugin not found: $pluginId"))

            if (loadedPlugins.containsKey(pluginId)) {
                return Result.success(Unit)
            }

            val pluginDir = File(info.installPath)
            val jarFile = File(pluginDir, "plugin.jar")
            if (!jarFile.exists()) {
                return Result.failure(Exception("plugin.jar not found"))
            }

            val classLoader = URLClassLoader(arrayOf(jarFile.toURI().toURL()), javaClass.classLoader)
            classLoaders[pluginId] = classLoader

            val pluginClass = classLoader.loadClass(info.manifest.mainClass)
            val plugin = pluginClass.getDeclaredConstructor().newInstance() as Plugin

            val pluginDataDir = File(pluginDir, "data")
            pluginDataDir.mkdirs()
            val context = PluginStorage(pluginId, pluginDataDir)

            plugin.onLoad(context)
            plugin.onEnable()

            loadedPlugins[pluginId] = plugin
            setPluginEnabled(pluginId, true)
            scanPlugins()

            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("PluginManager", "Failed to enable plugin: $pluginId", e)
            Result.failure(e)
        }
    }

    fun disablePlugin(pluginId: String): Result<Unit> {
        return try {
            val plugin = loadedPlugins[pluginId]
                ?: return Result.success(Unit)

            plugin.onDisable()
            plugin.onUnload()

            loadedPlugins.remove(pluginId)
            classLoaders[pluginId]?.close()
            classLoaders.remove(pluginId)

            setPluginEnabled(pluginId, false)
            scanPlugins()

            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("PluginManager", "Failed to disable plugin: $pluginId", e)
            Result.failure(e)
        }
    }

    fun deletePlugin(pluginId: String): Result<Unit> {
        return try {
            disablePlugin(pluginId)

            val info = _plugins.value.find { it.manifest.id == pluginId }
                ?: return Result.failure(Exception("Plugin not found: $pluginId"))

            File(info.installPath).deleteRecursively()
            setPluginEnabled(pluginId, false)
            scanPlugins()

            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("PluginManager", "Failed to delete plugin: $pluginId", e)
            Result.failure(e)
        }
    }

    fun getPlugin(pluginId: String): Plugin? = loadedPlugins[pluginId]

    fun getPluginSettingsProvider(pluginId: String): PluginSettingsProvider? {
        return getPlugin(pluginId) as? PluginSettingsProvider
    }

    fun getPluginUIProvider(pluginId: String): PluginUIProvider? {
        return getPlugin(pluginId) as? PluginUIProvider
    }

    fun getPluginsByTag(tag: String): List<PluginInfo> {
        return _plugins.value.filter { it.manifest.tags.contains(tag) }
    }

    fun getPluginsByPlatform(platform: PluginPlatform): List<PluginInfo> {
        return _plugins.value.filter { it.manifest.platform == platform }
    }

    private fun isPluginEnabled(pluginId: String): Boolean {
        val prefs = Preferences.userRoot().node("micyou/plugins")
        return prefs.getBoolean("${pluginId}_enabled", false)
    }

    private fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        val prefs = Preferences.userRoot().node("micyou/plugins")
        prefs.putBoolean("${pluginId}_enabled", enabled)
    }
}
