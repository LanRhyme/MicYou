package com.lanrhyme.micyou.platform

import com.lanrhyme.micyou.Logger

object PipeWireManager {
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
        if (!PlatformInfo.isLinux) return false
        
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

    fun isInstalled(): Boolean {
        if (!PlatformInfo.isLinux) return false
        return deviceExists()
    }
    
    fun setup(): Boolean {
        if (!PlatformInfo.isLinux) {
            Logger.w("PipeWireManager", "PipeWire virtual audio device only supports Linux platform")
            return false
        }
        
        if (!isAvailable()) {
            Logger.e("PipeWireManager", "PipeWire is not available")
            return false
        }
        
        Logger.i("PipeWireManager", "Setting up PipeWire virtual audio device...")
        
        return try {
            cleanup()
            
            if (!createVirtualSink()) {
                Logger.e("PipeWireManager", "Failed to create virtual Sink")
                return false
            }
            
            Thread.sleep(500)
            
            if (!createLoopback()) {
                Logger.e("PipeWireManager", "Failed to create loopback")
                cleanup()
                return false
            }
            
            Thread.sleep(500)
            
            if (!hideVirtualSink()) {
                Logger.w("PipeWireManager", "Failed to hide virtual Sink (non-fatal)")
            }
            
            if (!setDefaultSource()) {
                Logger.w("PipeWireManager", "Failed to set default source (non-fatal)")
            }
            
            isSetup = true
            Logger.i("PipeWireManager", "Virtual audio device setup complete")
            true
        } catch (e: Exception) {
            Logger.e("PipeWireManager", "Error setting up virtual audio device", e)
            cleanup()
            false
        }
    }
    
    private fun createVirtualSink(): Boolean {
        Logger.d("PipeWireManager", "Creating virtual Sink: $SINK_NAME")
        
        return try {
            val process = ProcessBuilder(
                "pw-cli", "create-node",
                "adapter",
                "factory.name=support.null-audio-sink",
                "node.name=$SINK_NAME",
                "media.class=Audio/Sink",
                "object.linger=true",
                "audio.position=[FL FR]",
                "monitor.mode=disabled"
            ).redirectErrorStream(true).start()
            
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0 || output.contains("created") || output.contains("bound")) {
                val idMatch = Regex("(\\d+)").find(output)
                sinkNodeId = idMatch?.groupValues?.get(1)
                Logger.i("PipeWireManager", "Virtual Sink created successfully (id: $sinkNodeId)")
                true
            } else {
                Logger.e("PipeWireManager", "Failed to create virtual Sink: $output")
                false
            }
        } catch (e: Exception) {
            Logger.e("PipeWireManager", "Error creating virtual Sink", e)
            false
        }
    }
    
    private fun createLoopback(): Boolean {
        Logger.d("PipeWireManager", "Creating loopback: $SINK_NAME -> $SOURCE_NAME")
        
        return try {
            val process = ProcessBuilder(
                "pw-loopback",
                "--capture-props={\"node.target\": \"$SINK_NAME\", \"media.class\": \"Stream/Input/Audio\", \"stream.capture.sink\": true}",
                "--playback-props={\"node.description\": \"$SOURCE_NAME\", \"media.class\": \"Audio/Source\"}"
            ).redirectErrorStream(true).start()
            
            loopbackProcess = process
            
            Thread.sleep(200)
            
            if (process.isAlive) {
                Logger.i("PipeWireManager", "Loopback created successfully (pid: ${process.pid()})")
                true
            } else {
                val output = process.inputStream.bufferedReader().readText()
                Logger.e("PipeWireManager", "Failed to create loopback: $output")
                false
            }
        } catch (e: Exception) {
            Logger.e("PipeWireManager", "Error creating loopback", e)
            false
        }
    }
    
    private fun hideVirtualSink(): Boolean {
        Logger.d("PipeWireManager", "Hiding virtual Sink: $SINK_NAME")
        
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
                Logger.i("PipeWireManager", "Virtual Sink hidden")
                true
            } else {
                Logger.w("PipeWireManager", "Failed to hide virtual Sink: $output")
                false
            }
        } catch (e: Exception) {
            Logger.e("PipeWireManager", "Error hiding virtual Sink", e)
            false
        }
    }
    
    private fun setDefaultSource(): Boolean {
        Logger.d("PipeWireManager", "Setting default source: $SOURCE_NAME")
        
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
                Logger.i("PipeWireManager", "Default source set successfully")
                true
            } else {
                Logger.w("PipeWireManager", "Failed to set default source: $output")
                tryFallbackSetDefaultSource()
            }
        } catch (e: Exception) {
            Logger.e("PipeWireManager", "Error setting default source", e)
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
                Logger.i("PipeWireManager", "Default source set using pactl")
                true
            } else {
                Logger.w("PipeWireManager", "pactl failed to set default source: $output")
                false
            }
        } catch (e: Exception) {
            Logger.e("PipeWireManager", "Error setting default source with pactl", e)
            false
        }
    }
    
    fun cleanup() {
        Logger.i("PipeWireManager", "Cleaning up virtual audio device...")
        
        loopbackProcess?.let { process ->
            try {
                if (process.isAlive) {
                    process.destroy()
                    Logger.d("PipeWireManager", "Loopback process terminated")
                }
            } catch (e: Exception) {
                Logger.e("PipeWireManager", "Error terminating loopback process", e)
            }
            loopbackProcess = null
        }
        
        destroyNodeByName(SOURCE_NAME, "Virtual Source")
        
        sinkNodeId?.let { id ->
            destroyNode(id, "Virtual Sink")
            sinkNodeId = null
        } ?: destroyNodeByName(SINK_NAME, "Virtual Sink")
        
        isSetup = false
        Logger.i("PipeWireManager", "Virtual audio device cleanup complete")
    }
    
    private fun destroyNode(nodeId: String, description: String) {
        try {
            val process = ProcessBuilder("pw-cli", "destroy", nodeId)
                .redirectErrorStream(true)
                .start()
            
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                Logger.d("PipeWireManager", "$description destroyed (id: $nodeId)")
            } else {
                Logger.w("PipeWireManager", "Failed to destroy $description: $output")
            }
        } catch (e: Exception) {
            Logger.e("PipeWireManager", "Error destroying $description", e)
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
                Logger.d("PipeWireManager", "$description destroyed or not found (name: $nodeName)")
            } else {
                Logger.w("PipeWireManager", "Failed to destroy $description: $output")
            }
        } catch (e: Exception) {
            Logger.e("PipeWireManager", "Error destroying $description", e)
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
