package com.lanrhyme.micyou.platform

import com.lanrhyme.micyou.Logger
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 蓝牙诊断结果
 */
data class BluetoothDiagnosis(
    val isAvailable: Boolean,
    val adapterPresent: Boolean,
    val adapterAddress: String?,
    val adapterName: String?,
    val serviceRunning: Boolean,
    val blueZInstalled: Boolean,
    val poweredOn: Boolean,
    val discoverable: Boolean,
    val pairedDevices: List<PairedDevice>,
    val issues: List<BluetoothIssue>,
    val suggestions: List<String>
)

data class PairedDevice(
    val address: String,
    val name: String?,
    val isConnected: Boolean
)

enum class BluetoothIssue {
    AdapterNotFound,
    ServiceNotRunning,
    BlueZNotInstalled,
    BluetoothDisabled,
    PermissionDenied,
    RfcommInUse,
    NoPairedDevices
}

/**
 * 蓝牙诊断工具
 * 用于检查系统蓝牙状态和诊断连接问题
 */
object BluetoothDiagnostics {

    /**
     * 执行完整的蓝牙诊断
     */
    fun diagnose(): BluetoothDiagnosis {
        if (!PlatformInfo.isLinux) {
            return BluetoothDiagnosis(
                isAvailable = false,
                adapterPresent = false,
                adapterAddress = null,
                adapterName = null,
                serviceRunning = false,
                blueZInstalled = false,
                poweredOn = false,
                discoverable = false,
                pairedDevices = emptyList(),
                issues = listOf(BluetoothIssue.BlueZNotInstalled),
                suggestions = listOf("Bluetooth diagnostics is only available on Linux with BlueZ")
            )
        }

        val issues = mutableListOf<BluetoothIssue>()
        val suggestions = mutableListOf<String>()

        // 检查 BlueZ 是否安装
        val blueZInstalled = checkBlueZInstalled()
        if (!blueZInstalled) {
            issues.add(BluetoothIssue.BlueZNotInstalled)
            suggestions.add("Install BlueZ: sudo apt install bluez")
        }

        // 检查蓝牙服务状态
        val serviceRunning = checkBluetoothService()
        if (!serviceRunning && blueZInstalled) {
            issues.add(BluetoothIssue.ServiceNotRunning)
            suggestions.add("Start Bluetooth service: sudo systemctl start bluetooth")
        }

        // 检查蓝牙适配器
        val adapterInfo = getAdapterInfo()
        val adapterPresent = adapterInfo != null
        if (!adapterPresent) {
            issues.add(BluetoothIssue.AdapterNotFound)
            suggestions.add("Check if Bluetooth hardware is connected")
        }

        // 检查蓝牙是否启用
        val poweredOn = checkPoweredOn()
        if (!poweredOn && adapterPresent) {
            issues.add(BluetoothIssue.BluetoothDisabled)
            suggestions.add("Enable Bluetooth: sudo hciconfig hci0 up")
        }

        // 检查已配对设备
        val pairedDevices = getPairedDevices()
        if (pairedDevices.isEmpty() && adapterPresent && poweredOn) {
            issues.add(BluetoothIssue.NoPairedDevices)
            suggestions.add("Pair a device first using: bluetoothctl")
        }

        // 检查 RFCOMM 是否被占用
        if (checkRfcommInUse()) {
            issues.add(BluetoothIssue.RfcommInUse)
            suggestions.add("Release RFCOMM: sudo rfcomm release 0")
        }

        val isAvailable = blueZInstalled && serviceRunning && adapterPresent && poweredOn

        return BluetoothDiagnosis(
            isAvailable = isAvailable,
            adapterPresent = adapterPresent,
            adapterAddress = adapterInfo?.first,
            adapterName = adapterInfo?.second,
            serviceRunning = serviceRunning,
            blueZInstalled = blueZInstalled,
            poweredOn = poweredOn,
            discoverable = checkDiscoverable(),
            pairedDevices = pairedDevices,
            issues = issues,
            suggestions = suggestions
        )
    }

    /**
     * 快速检查蓝牙是否可用
     */
    fun isBluetoothAvailable(): Boolean {
        if (!PlatformInfo.isLinux) return false

        return checkBlueZInstalled() &&
               checkBluetoothService() &&
               getAdapterInfo() != null &&
               checkPoweredOn()
    }

    private fun checkBlueZInstalled(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "bluetoothctl"))
            process.waitFor() == 0
        } catch (e: Exception) {
            Logger.w("BluetoothDiagnostics", "Failed to check BlueZ: ${e.message}")
            false
        }
    }

    private fun checkBluetoothService(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("systemctl", "is-active", "bluetooth")
            )
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val status = reader.readLine()?.trim() ?: ""
            process.waitFor()
            status == "active"
        } catch (e: Exception) {
            // 如果 systemctl 不可用，尝试检查 bluetoothd 进程
            try {
                val process = Runtime.getRuntime().exec(arrayOf("pgrep", "bluetoothd"))
                process.waitFor() == 0
            } catch (e2: Exception) {
                Logger.w("BluetoothDiagnostics", "Failed to check Bluetooth service: ${e.message}")
                false
            }
        }
    }

    private fun getAdapterInfo(): Pair<String, String>? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("hciconfig"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            var address: String? = null
            var name: String? = null
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                if (line?.contains("hci") == true) {
                    name = line?.split(":")?.firstOrNull()?.trim()
                    val nextLine = reader.readLine()
                    if (nextLine?.contains("BD Address:") == true) {
                        address = nextLine.split("BD Address:")
                            .getOrNull(1)?.trim()?.split(" ")?.firstOrNull()
                    }
                }
            }
            process.waitFor()

            if (address != null) Pair(address, name ?: "hci0") else null
        } catch (e: Exception) {
            Logger.w("BluetoothDiagnostics", "Failed to get adapter info: ${e.message}")
            null
        }
    }

    private fun checkPoweredOn(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("hciconfig", "hci0"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line?.contains("UP", ignoreCase = true) == true) {
                    process.waitFor()
                    return true
                }
            }
            process.waitFor()
            false
        } catch (e: Exception) {
            Logger.w("BluetoothDiagnostics", "Failed to check powered state: ${e.message}")
            false
        }
    }

    private fun checkDiscoverable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("hciconfig", "hci0"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line?.contains("PSCAN", ignoreCase = true) == true) {
                    process.waitFor()
                    return true
                }
            }
            process.waitFor()
            false
        } catch (e: Exception) {
            Logger.w("BluetoothDiagnostics", "Failed to check discoverable state: ${e.message}")
            false
        }
    }

    private fun getPairedDevices(): List<PairedDevice> {
        return try {
            val devices = mutableListOf<PairedDevice>()
            val process = Runtime.getRuntime().exec(arrayOf("bluetoothctl", "paired-devices"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line?.startsWith("Device") == true) {
                    val parts = line!!.split(" ")
                    if (parts.size >= 2) {
                        val address = parts[1]
                        val name = if (parts.size >= 3) parts.drop(2).joinToString(" ") else null
                        devices.add(PairedDevice(address, name, false))
                    }
                }
            }
            process.waitFor()
            devices
        } catch (e: Exception) {
            Logger.w("BluetoothDiagnostics", "Failed to get paired devices: ${e.message}")
            emptyList()
        }
    }

    private fun checkRfcommInUse(): Boolean {
        return try {
            val rfcommFile = java.io.File("/dev/rfcomm0")
            rfcommFile.exists()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 生成诊断报告字符串
     */
    fun generateReport(diagnosis: BluetoothDiagnosis): String {
        return buildString {
            appendLine("=== Bluetooth Diagnostics Report ===")
            appendLine("Available: ${diagnosis.isAvailable}")
            appendLine("Adapter: ${diagnosis.adapterName ?: "Not found"} (${diagnosis.adapterAddress ?: "N/A"})")
            appendLine("BlueZ Installed: ${diagnosis.blueZInstalled}")
            appendLine("Service Running: ${diagnosis.serviceRunning}")
            appendLine("Powered On: ${diagnosis.poweredOn}")
            appendLine("Discoverable: ${diagnosis.discoverable}")
            appendLine("Paired Devices: ${diagnosis.pairedDevices.size}")

            if (diagnosis.pairedDevices.isNotEmpty()) {
                appendLine("  Devices:")
                diagnosis.pairedDevices.forEach { device ->
                    appendLine("    - ${device.name ?: "Unknown"} (${device.address})")
                }
            }

            if (diagnosis.issues.isNotEmpty()) {
                appendLine("\nIssues Found:")
                diagnosis.issues.forEach { issue ->
                    appendLine("  - ${issue.name}")
                }
            }

            if (diagnosis.suggestions.isNotEmpty()) {
                appendLine("\nSuggestions:")
                diagnosis.suggestions.forEach { suggestion ->
                    appendLine("  $suggestion")
                }
            }
        }
    }
}