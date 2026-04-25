package com.lanrhyme.micyou.platform

import com.lanrhyme.micyou.Logger
import java.util.concurrent.TimeUnit

object FirewallManager {
    private const val COMMAND_TIMEOUT_SECONDS = 2L
    
    /** 防火墙协议类型 */
    enum class Protocol {
        TCP, UDP;
        
        override fun toString(): String = name
    }
    
    fun isFirewallEnabled(): Boolean {
        if (!PlatformInfo.isWindows) {
            return true
        }
        
        return try {
            val process = ProcessBuilder(
                "powershell.exe",
                "-Command",
                "(Get-NetFirewallProfile -Profile Domain,Public,Private | Where-Object {\$_.Enabled -eq \$true}).Count -gt 0"
            ).redirectErrorStream(true).start()
            
            val output = process.inputStream.bufferedReader().readText().trim()
            val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            
            output.toBoolean()
        } catch (e: Exception) {
            Logger.e("FirewallManager", "检查防火墙状态失败", e)
            false
        }
    }
    
    fun isPortAllowed(port: Int, protocol: Protocol = Protocol.TCP): Boolean {
        if (!PlatformInfo.isWindows) {
            return true
        }
        
        if (!isFirewallEnabled()) {
            Logger.d("FirewallManager", "防火墙已禁用，跳过端口检查")
            return true
        }
        
        return try {
            val process = ProcessBuilder(
                "powershell.exe",
                "-Command",
                "netsh advfirewall firewall show rule name=all | Select-String 'MicYou-$port-$protocol'"
            ).redirectErrorStream(true).start()
            
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            if (!finished) {
                process.destroyForcibly()
                Logger.w("FirewallManager", "端口检查超时，视为未放行")
                return false
            }
            
            output.contains("MicYou-$port-$protocol")
        } catch (e: Exception) {
            Logger.e("FirewallManager", "检查防火墙规则失败", e)
            false
        }
    }
    
    fun addFirewallRule(port: Int, protocol: Protocol = Protocol.TCP): Boolean {
        if (!PlatformInfo.isWindows) {
            return true
        }
        
        if (!isFirewallEnabled()) {
            Logger.d("FirewallManager", "防火墙已禁用，无需添加规则")
            return true
        }
        
        if (isPortAllowed(port, protocol)) {
            Logger.d("FirewallManager", "防火墙规则已存在: MicYou-$port-$protocol")
            return true
        }
        
        return try {
            val command = """
                New-NetFirewallRule -DisplayName "MicYou-$port-$protocol" -Direction Inbound -LocalPort $port -Protocol $protocol -Action Allow
            """.trimIndent()
            
            val process = ProcessBuilder(
                "powershell.exe",
                "-Command",
                command
            ).redirectErrorStream(true).start()
            
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            if (!finished) {
                process.destroyForcibly()
                Logger.w("FirewallManager", "添加防火墙规则超时，使用netsh重试")
                return tryNetshFallback(port, protocol)
            }
            
            val exitCode = process.exitValue()
            
            if (exitCode == 0) {
                Logger.i("FirewallManager", "防火墙规则添加成功: MicYou-$port-$protocol")
                true
            } else {
                Logger.e("FirewallManager", "防火墙规则添加失败 (exit=$exitCode): $output")
                tryNetshFallback(port, protocol)
            }
        } catch (e: Exception) {
            Logger.e("FirewallManager", "添加防火墙规则时出错", e)
            tryNetshFallback(port, protocol)
        }
    }
    
    private fun tryNetshFallback(port: Int, protocol: Protocol = Protocol.TCP): Boolean {
        return try {
            val process = ProcessBuilder(
                "netsh", "advfirewall", "firewall", "add", "rule",
                "name=MicYou-$port-$protocol",
                "dir=in",
                "action=allow",
                "protocol=$protocol",
                "localport=$port"
            ).redirectErrorStream(true).start()
            
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            if (!finished) {
                process.destroyForcibly()
                Logger.e("FirewallManager", "netsh添加防火墙规则超时")
                return false
            }
            
            val exitCode = process.exitValue()
            
            if (exitCode == 0) {
                Logger.i("FirewallManager", "防火墙规则添加成功: MicYou-$port-$protocol")
                true
            } else {
                Logger.e("FirewallManager", "防火墙规则添加失败 (exit=$exitCode): $output")
                false
            }
        } catch (e: Exception) {
            Logger.e("FirewallManager", "添加防火墙规则时出错", e)
            false
        }
    }
    
    fun removeFirewallRule(port: Int): Boolean {
        if (!PlatformInfo.isWindows) {
            return true
        }
        
        // 移除 TCP 和 UDP 规则
        var success = true
        for (protocol in Protocol.values()) {
            try {
                val process = ProcessBuilder(
                    "powershell.exe",
                    "-Command",
                    "Remove-NetFirewallRule -DisplayName 'MicYou-$port-$protocol'"
                ).redirectErrorStream(true).start()
                
                process.waitFor()
                val exitCode = process.exitValue()
                
                if (exitCode == 0) {
                    Logger.i("FirewallManager", "防火墙规则已移除: MicYou-$port-$protocol")
                } else {
                    val output = process.inputStream.bufferedReader().readText()
                    Logger.w("FirewallManager", "移除防火墙规则失败 (exit=$exitCode): MicYou-$port-$protocol, 输出: $output")
                    success = false
                }
            } catch (e: Exception) {
                Logger.e("FirewallManager", "移除防火墙规则时出错: MicYou-$port-$protocol", e)
                success = false
            }
        }
        return success
    }
}
