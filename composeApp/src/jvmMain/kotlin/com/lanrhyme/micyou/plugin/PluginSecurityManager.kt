package com.lanrhyme.micyou.plugin

import java.io.File
import java.io.FilePermission
import java.net.NetPermission
import java.security.Permission

class PluginSecurityManager(
    private val pluginId: String,
    private val pluginDir: File,
    private val permissions: Set<String>
) {
    private val allowedPaths = mutableSetOf<String>()

    init {
        allowedPaths.add(pluginDir.absolutePath)
        allowedPaths.add(File(pluginDir, "data").absolutePath)
        allowedPaths.add(File(pluginDir, "assets").absolutePath)
    }

    fun checkPermission(perm: Permission): Boolean {
        return when (perm) {
            is FilePermission -> checkFilePermission(perm)
            is NetPermission -> checkNetworkPermission(perm)
            is RuntimePermission -> checkRuntimePermission(perm)
            else -> true
        }
    }

    private fun checkFilePermission(perm: FilePermission): Boolean {
        if (!permissions.contains("storage")) {
            return false
        }
    val path = perm.name
        val action = perm.actions

        for (allowedPath in allowedPaths) {
            if (path.startsWith(allowedPath)) {
                return true
            }
        }

        return false
    }

    private fun checkNetworkPermission(perm: NetPermission): Boolean {
        return permissions.contains("network")
    }

    private fun checkRuntimePermission(perm: RuntimePermission): Boolean {
        val name = perm.name
        return when {
            name.startsWith("accessDeclaredMembers") -> true
            name.startsWith("createClassLoader") -> false
            name.startsWith("getClassLoader") -> true
            name.startsWith("exitVM") -> false
            name.startsWith("setSecurityManager") -> false
            else -> true
        }
    }

    fun addAllowedPath(path: String) {
        allowedPaths.add(path)
    }

    companion object {
        private val pluginSecurityManagers = mutableMapOf<String, PluginSecurityManager>()

        fun register(pluginId: String, pluginDir: File, permissions: Set<String>): PluginSecurityManager {
            val manager = PluginSecurityManager(pluginId, pluginDir, permissions)
            pluginSecurityManagers[pluginId] = manager
            return manager
        }

        fun unregister(pluginId: String) {
            pluginSecurityManagers.remove(pluginId)
        }

        fun get(pluginId: String): PluginSecurityManager? = pluginSecurityManagers[pluginId]

        fun checkPluginPermission(pluginId: String, perm: Permission): Boolean {
            val manager = pluginSecurityManagers[pluginId] ?: return false
            return manager.checkPermission(perm)
        }
    }
}
