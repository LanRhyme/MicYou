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
            // 首先检查是否存在 MicYou 特定规则
            val micYouRuleProcess = ProcessBuilder(
                "powershell.exe",
                "-Command",
                "netsh advfirewall firewall show rule name=all | Select-String 'MicYou-$port-$protocol'"
            ).redirectErrorStream(true).start()
    val micYouOutput = micYouRuleProcess.inputStream.bufferedReader().readText()
    val micYouFinished = micYouRuleProcess.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            if (micYouFinished && micYouOutput.contains("MicYou-$port-$protocol")) {
                Logger.d("FirewallManager", "找到 MicYou 特定规则: MicYou-$port-$protocol")
                return true
            }
            
            // 如果没有 MicYou 特定规则，检查是否有任何规则允许该端口
            val protocolNum = if (protocol == Protocol.TCP) "6" else "17"
            val checkProcess = ProcessBuilder(
                "powershell.exe",
                "-Command",
                """
                ${'$'}rules = Get-NetFirewallRule -Action Allow -Direction Inbound -Enabled True -ErrorAction SilentlyContinue | Where-Object {
                    ${'$'}portFilter = Get-NetFirewallPortFilter -AssociatedNetFirewallRule ${'$'}_ -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.LocalPort -eq $port -or ${'$'}_.LocalPort -eq '*' -or ${'$'}_.LocalPort -eq 'Any' }
                    ${'$'}portFilter | Where-Object { ${'$'}_.Protocol -eq $protocolNum -or ${'$'}_.Protocol -eq 'Any' }
                }
                if (${'$'}rules) { Write-Output 'ALLOWED' } else { Write-Output 'BLOCKED' }
                """.trimIndent()
            ).redirectErrorStream(true).start()
    val checkOutput = checkProcess.inputStream.bufferedReader().readText().trim()
    val checkFinished = checkProcess.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            if (!checkFinished) {
                checkProcess.destroyForcibly()
                Logger.w("FirewallManager", "端口检查超时，视为未放行")
                return false
            }
    val isAllowed = checkOutput.contains("ALLOWED")
            Logger.d("FirewallManager", "端口 $port ($protocol) 检查结果: $checkOutput, 允许: $isAllowed")
            isAllowed
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
