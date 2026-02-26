package com.lanrhyme.micyou.platform

import com.lanrhyme.micyou.Logger

object VirtualAudioDevice {
    private const val SINK_NAME = "MicYouVirtualSink"
    private const val SOURCE_NAME = "MicYouVirtualMic"
    private const val SINK_MONITOR = "MicYouVirtualSink.monitor"
    
    private var sinkNodeId: String? = null
    private var loopbackProcess: Process? = null
    private var isSetup = false
    
    val virtualSinkName: String get() = SINK_NAME
    val virtualSourceName: String get() = SOURCE_NAME
    val virtualSinkMonitor: String get() = SINK_MONITOR
    
    fun isAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("pw-cli", "--version")
                .redirectErrorStream(true)
                .start()
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }
    
    fun isSetupComplete(): Boolean = isSetup
    
    fun setup(): Boolean {
        if (!PlatformInfo.isLinux) {
            Logger.w("VirtualAudioDevice", "虚拟音频设备仅支持 Linux 平台")
            return false
        }
        
        if (!isAvailable()) {
            Logger.e("VirtualAudioDevice", "PipeWire 不可用")
            return false
        }
        
        Logger.i("VirtualAudioDevice", "开始设置 PipeWire 虚拟音频设备...")
        
        return try {
            cleanup()
            
            if (!createVirtualSink()) {
                Logger.e("VirtualAudioDevice", "创建虚拟 Sink 失败")
                return false
            }
            
            Thread.sleep(500)
            
            if (!createLoopback()) {
                Logger.e("VirtualAudioDevice", "创建回环失败")
                cleanup()
                return false
            }
            
            Thread.sleep(500)
            
            if (!hideVirtualSink()) {
                Logger.w("VirtualAudioDevice", "隐藏虚拟 Sink 失败（非致命错误）")
            }
            
            if (!setDefaultSource()) {
                Logger.w("VirtualAudioDevice", "设置默认源失败（非致命错误）")
            }
            
            isSetup = true
            Logger.i("VirtualAudioDevice", "虚拟音频设备设置完成")
            true
        } catch (e: Exception) {
            Logger.e("VirtualAudioDevice", "设置虚拟音频设备时出错", e)
            cleanup()
            false
        }
    }
    
    private fun createVirtualSink(): Boolean {
        Logger.d("VirtualAudioDevice", "创建虚拟 Sink: $SINK_NAME")
        
        return try {
            val process = ProcessBuilder(
                "pw-cli", "create-node",
                "adapter",
                "factory.name=support.null-audio-sink",
                "node.name=$SINK_NAME",
                "media.class=Audio/Sink",
                "object.linger=true",
                "audio.position=[FL FR]"
            ).redirectErrorStream(true).start()
            
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0 || output.contains("created") || output.contains("bound")) {
                val idMatch = Regex("(\\d+)").find(output)
                sinkNodeId = idMatch?.groupValues?.get(1)
                Logger.i("VirtualAudioDevice", "虚拟 Sink 创建成功 (id: $sinkNodeId)")
                true
            } else {
                Logger.e("VirtualAudioDevice", "创建虚拟 Sink 失败: $output")
                false
            }
        } catch (e: Exception) {
            Logger.e("VirtualAudioDevice", "创建虚拟 Sink 时出错", e)
            false
        }
    }
    
    private fun createLoopback(): Boolean {
        Logger.d("VirtualAudioDevice", "创建回环: $SINK_NAME -> $SOURCE_NAME")
        
        return try {
            val process = ProcessBuilder(
                "pw-loopback",
                "--capture-props={\"node.target\": \"$SINK_NAME\", \"media.class\": \"Stream/Input/Audio\", \"stream.capture.sink\": true}",
                "--playback-props={\"node.description\": \"$SOURCE_NAME\", \"media.class\": \"Audio/Source\"}"
            ).redirectErrorStream(true).start()
            
            loopbackProcess = process
            
            Thread.sleep(200)
            
            if (process.isAlive) {
                Logger.i("VirtualAudioDevice", "回环创建成功 (pid: ${process.pid()})")
                true
            } else {
                val output = process.inputStream.bufferedReader().readText()
                Logger.e("VirtualAudioDevice", "创建回环失败: $output")
                false
            }
        } catch (e: Exception) {
            Logger.e("VirtualAudioDevice", "创建回环时出错", e)
            false
        }
    }
    
    private fun hideVirtualSink(): Boolean {
        Logger.d("VirtualAudioDevice", "隐藏虚拟 Sink: $SINK_NAME")
        
        return try {
            val process = ProcessBuilder(
                "pw-cli", "set-param",
                SINK_NAME,
                "Props",
                "{media.role=Communication device.intended-roles=Communication}"
            ).redirectErrorStream(true).start()
            
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                Logger.i("VirtualAudioDevice", "虚拟 Sink 已隐藏")
                true
            } else {
                Logger.w("VirtualAudioDevice", "隐藏虚拟 Sink 失败: $output")
                false
            }
        } catch (e: Exception) {
            Logger.e("VirtualAudioDevice", "隐藏虚拟 Sink 时出错", e)
            false
        }
    }
    
    private fun setDefaultSource(): Boolean {
        Logger.d("VirtualAudioDevice", "设置默认源: $SOURCE_NAME")
        
        return try {
            val process = ProcessBuilder(
                "pw-cli", "set-default-profile",
                SOURCE_NAME,
                "{name=pro-audio}"
            ).redirectErrorStream(true).start()
            
            process.waitFor()
            
            val setDefaultProcess = ProcessBuilder(
                "wpctl", "set-default",
                "@$SOURCE_NAME"
            ).redirectErrorStream(true).start()
            
            val output = setDefaultProcess.inputStream.bufferedReader().readText()
            val exitCode = setDefaultProcess.waitFor()
            
            if (exitCode == 0) {
                Logger.i("VirtualAudioDevice", "默认源设置成功")
                true
            } else {
                Logger.w("VirtualAudioDevice", "设置默认源失败: $output")
                tryFallbackSetDefaultSource()
            }
        } catch (e: Exception) {
            Logger.e("VirtualAudioDevice", "设置默认源时出错", e)
            tryFallbackSetDefaultSource()
        }
    }
    
    private fun tryFallbackSetDefaultSource(): Boolean {
        return try {
            val process = ProcessBuilder(
                "pactl", "set-default-source",
                SOURCE_NAME
            ).redirectErrorStream(true).start()
            
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                Logger.i("VirtualAudioDevice", "使用 pactl 设置默认源成功")
                true
            } else {
                Logger.w("VirtualAudioDevice", "pactl 设置默认源失败: $output")
                false
            }
        } catch (e: Exception) {
            Logger.e("VirtualAudioDevice", "pactl 设置默认源时出错", e)
            false
        }
    }
    
    fun cleanup() {
        Logger.i("VirtualAudioDevice", "清理虚拟音频设备...")
        
        loopbackProcess?.let { process ->
            try {
                if (process.isAlive) {
                    process.destroy()
                    Logger.d("VirtualAudioDevice", "回环进程已终止")
                }
            } catch (e: Exception) {
                Logger.e("VirtualAudioDevice", "终止回环进程时出错", e)
            }
            loopbackProcess = null
        }
        
        destroyNodeByName(SOURCE_NAME, "虚拟 Source")
        
        if (sinkNodeId != null) {
            destroyNode(sinkNodeId!!, "虚拟 Sink")
            sinkNodeId = null
        } else {
            destroyNodeByName(SINK_NAME, "虚拟 Sink")
        }
        
        isSetup = false
        Logger.i("VirtualAudioDevice", "虚拟音频设备清理完成")
    }
    
    private fun destroyNode(nodeId: String, description: String) {
        try {
            val process = ProcessBuilder("pw-cli", "destroy", nodeId)
                .redirectErrorStream(true)
                .start()
            
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                Logger.d("VirtualAudioDevice", "$description 已销毁 (id: $nodeId)")
            } else {
                Logger.w("VirtualAudioDevice", "销毁 $description 失败: $output")
            }
        } catch (e: Exception) {
            Logger.e("VirtualAudioDevice", "销毁 $description 时出错", e)
        }
    }
    
    private fun destroyNodeByName(nodeName: String, description: String) {
        try {
            val process = ProcessBuilder("pw-cli", "destroy", nodeName)
                .redirectErrorStream(true)
                .start()
            
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0 || output.contains("not found") || output.contains("No such")) {
                Logger.d("VirtualAudioDevice", "$description 已销毁或不存在 (name: $nodeName)")
            } else {
                Logger.w("VirtualAudioDevice", "销毁 $description 失败: $output")
            }
        } catch (e: Exception) {
            Logger.e("VirtualAudioDevice", "销毁 $description 时出错", e)
        }
    }
    
    fun getSinkNodeName(): String = SINK_NAME
    
    fun deviceExists(): Boolean {
        return try {
            val process = ProcessBuilder("pw-cli", "list-objects")
                .redirectErrorStream(true)
                .start()
            
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            
            output.contains(SINK_NAME) && output.contains(SOURCE_NAME)
        } catch (e: Exception) {
            false
        }
    }
}
