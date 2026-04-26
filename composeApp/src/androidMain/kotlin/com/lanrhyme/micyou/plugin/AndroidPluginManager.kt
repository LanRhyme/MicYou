package com.lanrhyme.micyou.plugin

import android.content.Context
import android.content.SharedPreferences
import com.lanrhyme.micyou.ContextHelper
import com.lanrhyme.micyou.Logger
import dalvik.system.DexClassLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

class AndroidPluginManager(
    private val pluginsDir: File,
    private val pluginHost: PluginHost,
    private val appLanguageProvider: () -> String = { "en" },
    private val appStringProvider: ((String) -> String)? = null
) {
    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val plugins: StateFlow<List<PluginInfo>> = _plugins.asStateFlow()

    private val loadedPlugins = mutableMapOf<String, Plugin>()
    private val classLoaders = mutableMapOf<String, DexClassLoader>()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        pluginsDir.mkdirs()
        cleanupTempDirectories()
        scanPlugins()
        loadEnabledPlugins()
    }

    private fun loadEnabledPlugins() {
        Logger.i("AndroidPluginManager", "Loading enabled plugins...")
        _plugins.value.filter { it.isEnabled }.forEach { pluginInfo ->
            try {
                enablePlugin(pluginInfo.manifest.id)
                Logger.i("AndroidPluginManager", "Auto-loaded plugin: ${pluginInfo.manifest.id}")
            } catch (e: Exception) {
                Logger.e("AndroidPluginManager", "Failed to auto-load plugin: ${pluginInfo.manifest.id}", e)
            }
        }
    }

    private fun cleanupTempDirectories() {
        pluginsDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("temp_") }?.forEach { tempDir ->
            try {
                tempDir.deleteRecursively()
                Logger.d("AndroidPluginManager", "Cleaned up temp directory: ${tempDir.name}")
            } catch (e: Exception) {
                Logger.w("AndroidPluginManager", "Failed to cleanup temp directory: ${tempDir.name}: ${e.message}")
            }
        }
    }

    fun scanPlugins() {
        Logger.i("AndroidPluginManager", "Scanning plugins in: ${pluginsDir.absolutePath}")
    val pluginList = mutableListOf<PluginInfo>()
    val dirs = pluginsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith("temp_") }
        Logger.d("AndroidPluginManager", "Found ${dirs?.size ?: 0} plugin directories")
        dirs?.forEach { pluginDir ->
            Logger.d("AndroidPluginManager", "Checking directory: ${pluginDir.name}")
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
                    Logger.i("AndroidPluginManager", "Loaded plugin: ${manifest.id}")
                } catch (e: Exception) {
                    Logger.e("AndroidPluginManager", "Failed to load plugin manifest from ${pluginDir.name}", e)
                }
            } else {
                Logger.w("AndroidPluginManager", "No plugin.json found in ${pluginDir.name}")
            }
        }
        _plugins.value = pluginList
        Logger.i("AndroidPluginManager", "Scan complete, found ${pluginList.size} plugins")
    }

    fun importPlugin(pluginFile: File): Result<PluginInfo> {
        Logger.i("AndroidPluginManager", "Importing plugin from: ${pluginFile.absolutePath}")
        return try {
            val tempDir = File(pluginsDir, "temp_${System.currentTimeMillis()}")
            tempDir.mkdirs()
            Logger.d("AndroidPluginManager", "Created temp directory: ${tempDir.absolutePath}")

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
            Logger.d("AndroidPluginManager", "Extracted zip file to temp directory")
    var manifestFile = File(tempDir, "plugin.json")
    var pluginRootDir = tempDir

            if (!manifestFile.exists()) {
                Logger.d("AndroidPluginManager", "plugin.json not in root, searching recursively")
    val found = findPluginManifest(tempDir)
                if (found != null) {
                    manifestFile = found.second
                    pluginRootDir = found.first
                    Logger.d("AndroidPluginManager", "Found plugin.json in: ${pluginRootDir.name}")
                }
            }

            if (!manifestFile.exists()) {
                Logger.e("AndroidPluginManager", "plugin.json not found in plugin package")
                tempDir.deleteRecursively()
                return Result.failure(Exception("plugin.json not found - make sure the plugin package contains a valid plugin.json file"))
            }
    val manifest = json.decodeFromString<PluginManifest>(manifestFile.readText())
            Logger.i("AndroidPluginManager", "Found plugin: ${manifest.id} - ${manifest.name} v${manifest.version}")
    val targetDir = File(pluginsDir, manifest.id.replace(".", "_"))

            if (targetDir.exists()) {
                val existingManifestFile = File(targetDir, "plugin.json")
                if (existingManifestFile.exists()) {
                    try {
                        val existingManifest = json.decodeFromString<PluginManifest>(existingManifestFile.readText())
    val comparison = compareVersions(manifest.version, existingManifest.version)
                        
                        if (comparison > 0) {
                            Logger.i("AndroidPluginManager", "Updating plugin ${manifest.id} from v${existingManifest.version} to v${manifest.version}")
                            disablePlugin(manifest.id)
                            targetDir.deleteRecursively()
                            Logger.d("AndroidPluginManager", "Removed old version of plugin ${manifest.id}")
                        } else if (comparison == 0) {
                            Logger.w("AndroidPluginManager", "Plugin ${manifest.id} v${manifest.version} already installed")
                            tempDir.deleteRecursively()
                            return Result.failure(Exception("Plugin ${manifest.id} v${manifest.version} already installed"))
                        } else {
                            Logger.w("AndroidPluginManager", "Plugin ${manifest.id} v${existingManifest.version} is newer than v${manifest.version}")
                            tempDir.deleteRecursively()
                            return Result.failure(Exception("A newer version (v${existingManifest.version}) of plugin ${manifest.id} is already installed"))
                        }
                    } catch (e: Exception) {
                        Logger.e("AndroidPluginManager", "Failed to parse existing plugin manifest: ${e.message}")
                        tempDir.deleteRecursively()
                        return Result.failure(Exception("Failed to check existing plugin version"))
                    }
                } else {
                    Logger.w("AndroidPluginManager", "Plugin directory exists but no manifest found, removing...")
                    targetDir.deleteRecursively()
                }
            }
    val dexFile = File(pluginRootDir, "plugin.dex")
    val hasDexInRoot = dexFile.exists()
            
            if (!hasDexInRoot) {
                val dexFilesInDir = pluginRootDir.listFiles()?.filter { it.extension == "dex" }
                if (!dexFilesInDir.isNullOrEmpty()) {
                    val sourceDex = dexFilesInDir.first()
                    sourceDex.copyTo(dexFile, overwrite = true)
                    Logger.d("AndroidPluginManager", "Renamed ${sourceDex.name} to plugin.dex")
                }
            }
    val renamed = pluginRootDir.renameTo(targetDir)
            if (!renamed) {
                Logger.w("AndroidPluginManager", "renameTo failed, using copy instead")
                targetDir.mkdirs()
                pluginRootDir.copyRecursively(targetDir, overwrite = true)
                pluginRootDir.deleteRecursively()
            }
            if (tempDir.exists() && tempDir != targetDir && tempDir != pluginRootDir) {
                tempDir.deleteRecursively()
            }
            Logger.i("AndroidPluginManager", "Moved plugin to: ${targetDir.absolutePath}")
            scanPlugins()
    val pluginInfo = _plugins.value.find { it.manifest.id == manifest.id }
                ?: return Result.failure(Exception("Failed to find imported plugin"))

            Logger.i("AndroidPluginManager", "Successfully imported plugin: ${manifest.id}")
            Result.success(pluginInfo)
        } catch (e: Exception) {
            Logger.e("AndroidPluginManager", "Failed to import plugin: ${e.message}", e)
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
    val dexFile = File(pluginDir, "plugin.dex")
            if (!dexFile.exists()) {
                return Result.failure(Exception("plugin.dex not found"))
            }
    val context = ContextHelper.getContext()
                ?: return Result.failure(Exception("Context not available"))
    val optimizedDexDir = context.codeCacheDir
            val classLoader = DexClassLoader(
                dexFile.absolutePath,
                optimizedDexDir.absolutePath,
                null,
                context.classLoader
            )
            classLoaders[pluginId] = classLoader

            val pluginClass = classLoader.loadClass(info.manifest.mainClass)
    val plugin = pluginClass.getDeclaredConstructor().newInstance() as Plugin

            val pluginDataDir = File(pluginDir, "data")
            pluginDataDir.mkdirs()
    val pluginContext = AndroidPluginStorage(
                pluginId = pluginId,
                dataDir = pluginDataDir,
                pluginInstallDir = pluginDir,
                host = pluginHost,
                appLanguageProvider = appLanguageProvider,
                appStringProvider = appStringProvider
            )

            plugin.onLoad(pluginContext)
            plugin.onEnable()

            loadedPlugins[pluginId] = plugin
            setPluginEnabled(pluginId, true)
            scanPlugins()

            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("AndroidPluginManager", "Failed to enable plugin: $pluginId", e)
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
            classLoaders.remove(pluginId)

            setPluginEnabled(pluginId, false)
            scanPlugins()

            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("AndroidPluginManager", "Failed to disable plugin: $pluginId", e)
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
            Logger.e("AndroidPluginManager", "Failed to delete plugin: $pluginId", e)
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
        val context = ContextHelper.getContext() ?: return true
        val prefs = context.getSharedPreferences("micyou_plugins", Context.MODE_PRIVATE)
        return prefs.getBoolean("${pluginId}_enabled", true)
    }

    private fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        val context = ContextHelper.getContext() ?: return
        val prefs = context.getSharedPreferences("micyou_plugins", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("${pluginId}_enabled", enabled).apply()
    }

    private fun findPluginManifest(dir: File): Pair<File, File>? {
        val manifestFile = File(dir, "plugin.json")
        if (manifestFile.exists()) {
            return Pair(dir, manifestFile)
        }
        
        dir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
            val result = findPluginManifest(subDir)
            if (result != null) {
                return result
            }
        }
        
        return null
    }

    private fun compareVersions(version1: String, version2: String): Int {
        val parts1 = version1.split(".")
    val parts2 = version2.split(".")
    val maxLength = maxOf(parts1.size, parts2.size)
        
        for (i in 0 until maxLength) {
            val v1 = parts1.getOrNull(i)?.toIntOrNull() ?: 0
            val v2 = parts2.getOrNull(i)?.toIntOrNull() ?: 0
            
            if (v1 != v2) {
                return v1 - v2
            }
        }
        
        return 0
    }
}
