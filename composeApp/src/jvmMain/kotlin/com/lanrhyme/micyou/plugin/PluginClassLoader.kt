package com.lanrhyme.micyou.plugin

import java.net.URL
import java.net.URLClassLoader

class PluginClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
    private val pluginId: String,
    private val securityManager: PluginSecurityManager
) : URLClassLoader(urls, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            var c = findLoadedClass(name)

            if (c == null) {
                if (name.startsWith("com.lanrhyme.micyou.plugin.") &&
                    !name.startsWith("com.lanrhyme.micyou.plugin.api.")) {
                    c = findClass(name)
                } else {
                    try {
                        c = parent.loadClass(name)
                    } catch (e: ClassNotFoundException) {
                        c = findClass(name)
                    }
                }
            }

            if (resolve) {
                resolveClass(c)
            }

            return c
        }
    }
}
