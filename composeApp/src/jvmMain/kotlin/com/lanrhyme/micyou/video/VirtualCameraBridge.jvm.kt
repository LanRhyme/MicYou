package com.lanrhyme.micyou.video

import com.lanrhyme.micyou.Logger
import com.lanrhyme.micyou.PlatformUtils
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

interface VirtualCameraBridge {
    fun init(): Result<Unit>
    fun start(width: Int, height: Int, fps: Int): Result<Unit>
    fun pushFrameBgra(width: Int, height: Int, frameBgra: ByteArray): Result<Unit>
    fun stop(): Result<Unit>
    fun release()
}

class NoOpVirtualCameraBridge : VirtualCameraBridge {
    override fun init(): Result<Unit> = Result.success(Unit)
    override fun start(width: Int, height: Int, fps: Int): Result<Unit> {
        Logger.i("VirtualCameraBridge", "No-op virtual camera start: ${width}x$height@$fps")
        return Result.success(Unit)
    }
    override fun pushFrameBgra(width: Int, height: Int, frameBgra: ByteArray): Result<Unit> = Result.success(Unit)
    override fun stop(): Result<Unit> = Result.success(Unit)
    override fun release() {}
}

class CommandProcessVirtualCameraBridge(
    private val commandBase: List<String>,
    private val displayName: String,
    private val preferredBackend: String? = null,
    private val onStatus: (String) -> Unit = {}
) : VirtualCameraBridge {
    private var process: Process? = null
    private var output: DataOutputStream? = null
    private val started = AtomicBoolean(false)

    override fun init(): Result<Unit> {
        if (commandBase.isEmpty()) {
            return Result.failure(IllegalStateException("Virtual camera helper command is empty"))
        }
        return Result.success(Unit)
    }

    override fun start(width: Int, height: Int, fps: Int): Result<Unit> {
        if (started.get()) return Result.success(Unit)
        return runCatching {
            val fullCommand = buildList {
                addAll(commandBase)
                add("--width=$width")
                add("--height=$height")
                add("--fps=$fps")
                if (!preferredBackend.isNullOrBlank()) {
                    add("--backend=$preferredBackend")
                }
            }
            val builder = ProcessBuilder(fullCommand)
            builder.redirectErrorStream(true)
            val p = builder.start()
            process = p
            output = DataOutputStream(BufferedOutputStream(p.outputStream))
            started.set(true)
            Thread {
                runCatching {
                    p.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            Logger.i("VirtualCamHelper", line)
                            handleHelperLine(line)
                        }
                    }
                }
            }.apply {
                isDaemon = true
                name = "vcam-helper-log"
                start()
            }
            onStatus("Helper: $displayName")
            if (!preferredBackend.isNullOrBlank()) {
                onStatus("Backend: $preferredBackend")
            }
            Logger.i("VirtualCameraBridge", "Virtual camera helper started: $displayName")
        }
    }

    override fun pushFrameBgra(width: Int, height: Int, frameBgra: ByteArray): Result<Unit> {
        if (!started.get()) return Result.failure(IllegalStateException("Virtual camera bridge not started"))
        return runCatching {
            val out = output ?: error("Virtual camera output not available")
            out.writeInt(0x4D595643) // "MYVC"
            out.writeInt(width)
            out.writeInt(height)
            out.writeInt(frameBgra.size)
            out.write(frameBgra)
            out.flush()
        }
    }

    override fun stop(): Result<Unit> {
        return runCatching {
            runCatching { output?.close() }
            output = null
            process?.destroy()
            process = null
            started.set(false)
        }
    }

    override fun release() {
        stop()
    }

    private fun handleHelperLine(line: String) {
        when {
            line.startsWith("VCAM_DEVICE:") -> onStatus("Device: ${line.substringAfter("VCAM_DEVICE:").trim()}")
            line.startsWith("VCAM_BACKEND:") -> onStatus("Backend: ${line.substringAfter("VCAM_BACKEND:").trim()}")
            line.startsWith("ERROR:") -> onStatus(line.trim())
        }
    }
}

object VirtualCameraBridgeFactory {
    fun create(onStatus: (String) -> Unit = {}): VirtualCameraBridge {
        if (!PlatformUtils.isWindows) {
            return NoOpVirtualCameraBridge()
        }

        val envPath = System.getenv("MICYOU_VCAM_HELPER")?.trim().orEmpty()
        val helperCandidates = buildList {
            if (envPath.isNotEmpty()) add(File(envPath))
            add(File("vcam/micyou-vcam-bridge.exe"))
            add(File("micyou-vcam-bridge.exe"))
        }
        val helper = helperCandidates.firstOrNull { it.exists() && it.isFile }
        if (helper != null) {
            Logger.i("VirtualCameraBridge", "Using external virtual camera helper: ${helper.absolutePath}")
            onStatus("Helper: ${helper.absolutePath}")
            return CommandProcessVirtualCameraBridge(
                commandBase = listOf(helper.absolutePath),
                displayName = helper.absolutePath,
                onStatus = onStatus
            )
        }

        val pyCommand = findPythonCommand()
        val pyScript = listOf(
            File("vcam/vcam_helper.py"),
            File("tools/vcam_helper.py")
        ).firstOrNull { it.exists() && it.isFile }
        if (pyCommand != null && pyScript != null) {
            val backend = detectPreferredBackend(pyCommand)
            onStatus("Helper: Python")
            onStatus("Backend(auto): ${backend ?: "default"}")
            Logger.i("VirtualCameraBridge", "Using Python virtual camera helper: ${pyScript.absolutePath}, backend=$backend")
            return CommandProcessVirtualCameraBridge(
                commandBase = pyCommand + pyScript.absolutePath,
                displayName = pyScript.absolutePath,
                preferredBackend = backend,
                onStatus = onStatus
            )
        }

        onStatus("No virtual camera helper available")
        Logger.w(
            "VirtualCameraBridge",
            "Virtual camera helper missing. Provide micyou-vcam-bridge.exe or install python+pyvirtualcam and keep vcam_helper.py."
        )
        return NoOpVirtualCameraBridge()
    }

    private fun detectPreferredBackend(pyCommand: List<String>): String? {
        val code = """
import sys
import pyvirtualcam
backend = sys.argv[1] if len(sys.argv) > 1 and sys.argv[1] else None
try:
    cam = pyvirtualcam.Camera(width=640, height=360, fps=30, fmt=pyvirtualcam.PixelFormat.BGR, backend=backend)
    print("OK|" + (backend or "default") + "|" + cam.device)
    cam.close()
except Exception as e:
    print("ERR|" + str(e))
    raise
""".trimIndent()
        // Prefer UnityCapture for wider compatibility in call apps like QQ.
        val candidates = listOf("unitycapture", "obs", "")
        for (candidate in candidates) {
            val cmd = buildList {
                addAll(pyCommand)
                add("-c")
                add(code)
                add(candidate)
            }
            val result = runCatching {
                val p = ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start()
                val ok = p.waitFor(5, TimeUnit.SECONDS)
                val output = p.inputStream.bufferedReader().readText()
                if (!ok) {
                    p.destroyForcibly()
                    return@runCatching false to output
                }
                (p.exitValue() == 0 && output.contains("OK|")) to output
            }.getOrDefault(false to "")
            if (result.first) {
                return if (candidate.isBlank()) null else candidate
            }
        }
        return null
    }

    private fun findPythonCommand(): List<String>? {
        val launcherCandidates = listOf(
            listOf("python"),
            listOf("py", "-3.10"),
            listOf("py", "-3.11"),
            listOf("py", "-3.12"),
            listOf("py", "-3.13")
        )
        for (cmd in launcherCandidates) {
            if (isCommandUsable(cmd)) return cmd
        }
        val pathCandidates = listOf(
            "C:/Users/Administrator/AppData/Local/Programs/Python/Python310/python.exe",
            "C:/Users/Administrator/AppData/Local/Programs/Python/Python311/python.exe",
            "C:/Users/Administrator/AppData/Local/Programs/Python/Python313/python.exe",
            "C:/Users/Administrator/AppData/Local/Programs/Python/Python314/python.exe"
        )
        for (path in pathCandidates) {
            val file = File(path)
            if (file.exists() && isCommandUsable(listOf(file.absolutePath))) {
                return listOf(file.absolutePath)
            }
        }
        return null
    }

    private fun isCommandUsable(command: List<String>): Boolean {
        return runCatching {
            val p = ProcessBuilder(command + "--version")
                .redirectErrorStream(true)
                .start()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
    }
}
