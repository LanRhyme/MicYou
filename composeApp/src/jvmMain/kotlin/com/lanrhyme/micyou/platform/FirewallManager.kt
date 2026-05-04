package com.lanrhyme.micyou.platform

import com.lanrhyme.micyou.Logger
import java.util.concurrent.TimeUnit

object FirewallManager {
    private const val COMMAND_TIMEOUT_SECONDS = 2L
    private const val ELEVATED_TIMEOUT_SECONDS = 15L

    enum class Protocol {
        TCP, UDP;

        override fun toString(): String = name
    }

    fun isFirewallEnabled(): Boolean {
        return when {
            PlatformInfo.isWindows -> isWindowsFirewallEnabled()
            PlatformInfo.isLinux -> isLinuxFirewallEnabled()
            else -> false
        }
    }

    fun isPortAllowed(port: Int, protocol: Protocol = Protocol.TCP): Boolean {
        return when {
            PlatformInfo.isWindows -> isWindowsPortAllowed(port, protocol)
            PlatformInfo.isLinux -> isLinuxPortAllowed(port, protocol)
            else -> true
        }
    }

    fun addFirewallRule(port: Int, protocol: Protocol = Protocol.TCP): Boolean {
        if (!isFirewallEnabled()) {
            Logger.d("FirewallManager", "防火墙已禁用，跳过端口检查")
            return true
        }

        if (isPortAllowed(port, protocol)) {
            Logger.d("FirewallManager", "防火墙规则已存在: MicYou-$port-$protocol")
            return true
        }

        return when {
            PlatformInfo.isWindows -> addWindowsFirewallRule(port, protocol)
            PlatformInfo.isLinux -> addLinuxFirewallRule(port, protocol)
            else -> true
        }
    }

    fun removeFirewallRule(port: Int): Boolean {
        return when {
            PlatformInfo.isWindows -> removeWindowsFirewallRules(port)
            PlatformInfo.isLinux -> removeLinuxFirewallRules(port)
            else -> true
        }
    }

    private fun isWindowsFirewallEnabled(): Boolean {
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

    private fun isWindowsPortAllowed(port: Int, protocol: Protocol): Boolean {
        if (!isWindowsFirewallEnabled()) {
            Logger.d("FirewallManager", "防火墙已禁用，跳过端口检查")
            return true
        }

        return try {
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

    private fun addWindowsFirewallRule(port: Int, protocol: Protocol): Boolean {
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
                return tryWindowsNetshFallback(port, protocol)
            }
            val exitCode = process.exitValue()

            if (exitCode == 0) {
                Logger.i("FirewallManager", "防火墙规则添加成功: MicYou-$port-$protocol")
                true
            } else {
                Logger.e("FirewallManager", "防火墙规则添加失败 (exit=$exitCode): $output")
                tryWindowsNetshFallback(port, protocol)
            }
        } catch (e: Exception) {
            Logger.e("FirewallManager", "添加防火墙规则时出错", e)
            tryWindowsNetshFallback(port, protocol)
        }
    }

    private fun tryWindowsNetshFallback(port: Int, protocol: Protocol): Boolean {
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

    private fun removeWindowsFirewallRules(port: Int): Boolean {
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

    private fun isIptablesAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("iptables", "--version")
                .redirectErrorStream(true).start()
            process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun isLinuxFirewallEnabled(): Boolean {
        if (!isIptablesAvailable()) {
            Logger.d("FirewallManager", "iptables 不可用，视为无防火墙限制")
            return false
        }

        return try {
            val process = ProcessBuilder(
                "iptables", "-L", "INPUT", "-n"
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                Logger.w("FirewallManager", "检查 iptables 状态超时")
                return false
            }

            if (process.exitValue() != 0) {
                Logger.w("FirewallManager", "无法读取 iptables 规则（权限不足），视为无限制")
                return false
            }

            val lines = output.lines()
            val hasDefaultDrop = lines.any {
                it.trimStart().startsWith("Chain INPUT") && it.contains("DROP", ignoreCase = true)
            }
            val hasRejectRules = lines.any {
                it.contains("REJECT", ignoreCase = true)
            }

            val isRestricted = hasDefaultDrop || hasRejectRules
            Logger.d("FirewallManager", "Linux 防火墙状态: defaultDrop=$hasDefaultDrop, hasReject=$hasRejectRules, restricted=$isRestricted")
            isRestricted
        } catch (e: Exception) {
            Logger.e("FirewallManager", "检查 Linux 防火墙状态失败", e)
            false
        }
    }

    private fun isLinuxPortAllowed(port: Int, protocol: Protocol): Boolean {
        if (!isLinuxFirewallEnabled()) {
            Logger.d("FirewallManager", "Linux 防火墙未启用限制规则，跳过端口检查")
            return true
        }

        val ruleName = "MicYou-$port-$protocol"

        return try {
            val process = ProcessBuilder(
                "iptables", "-C", "INPUT",
                "-p", protocol.name.lowercase(),
                "--dport", port.toString(),
                "-j", "ACCEPT",
                "-m", "comment", "--comment", ruleName
            ).redirectErrorStream(true).start()
            val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                Logger.w("FirewallManager", "检查 iptables 规则超时")
                return false
            }

            val allowed = process.exitValue() == 0
            Logger.d("FirewallManager", "端口 $port ($protocol) iptables 检查: allowed=$allowed")
            allowed
        } catch (e: Exception) {
            Logger.e("FirewallManager", "检查 iptables 规则失败", e)
            false
        }
    }

    private fun addLinuxFirewallRule(port: Int, protocol: Protocol): Boolean {
        val ruleName = "MicYou-$port-$protocol"
        val protoStr = protocol.name.lowercase()

        val ruleArgs = listOf(
            "iptables", "-I", "INPUT",
            "-p", protoStr,
            "--dport", port.toString(),
            "-j", "ACCEPT",
            "-m", "comment", "--comment", ruleName
        )

        val directSuccess = runIptablesCommand(ruleArgs, COMMAND_TIMEOUT_SECONDS, "添加")
        if (directSuccess) {
            Logger.i("FirewallManager", "iptables 规则添加成功: $ruleName")
            return true
        }

        Logger.d("FirewallManager", "直接添加失败，尝试 pkexec 提权添加: $ruleName")
        val pkexecArgs = listOf("pkexec") + ruleArgs
        val pkexecSuccess = runIptablesCommand(pkexecArgs, ELEVATED_TIMEOUT_SECONDS, "添加(pkexec)")
        if (pkexecSuccess) {
            Logger.i("FirewallManager", "iptables 规则添加成功(pkexec): $ruleName")
            return true
        }

        Logger.e("FirewallManager", "无法添加 iptables 规则: $ruleName，请尝试手动执行: sudo ${ruleArgs.joinToString(" ")}")
        return false
    }

    private fun removeLinuxFirewallRules(port: Int): Boolean {
        var success = true
        for (protocol in Protocol.values()) {
            val ruleName = "MicYou-$port-$protocol"
            val protoStr = protocol.name.lowercase()

            val ruleArgs = listOf(
                "iptables", "-D", "INPUT",
                "-p", protoStr,
                "--dport", port.toString(),
                "-j", "ACCEPT",
                "-m", "comment", "--comment", ruleName
            )

            val result = runIptablesCommand(ruleArgs, COMMAND_TIMEOUT_SECONDS, "移除")
            if (result) {
                Logger.i("FirewallManager", "iptables 规则已移除: $ruleName")
            } else {
                Logger.w("FirewallManager", "移除 iptables 规则失败: $ruleName（可能已被手动移除）")
            }
        }
        return success
    }

    private fun runIptablesCommand(args: List<String>, timeoutSeconds: Long, action: String): Boolean {
        return try {
            val process = ProcessBuilder(args)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                Logger.w("FirewallManager", "iptables $action 规则超时")
                return false
            }

            val exitCode = process.exitValue()
            if (exitCode == 0) {
                true
            } else {
                Logger.w("FirewallManager", "iptables $action 规则失败 (exit=$exitCode): ${output.take(200)}")
                false
            }
        } catch (e: Exception) {
            Logger.e("FirewallManager", "iptables $action 规则时出错", e)
            false
        }
    }
}
