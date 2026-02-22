package com.lanrhyme.micyou

import com.lanrhyme.micyou.BlackHoleManager
import com.lanrhyme.micyou.platform.PlatformInfo
import com.lanrhyme.micyou.platform.VirtualAudioDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.sound.sampled.AudioSystem

object VBCableManager {
    private const val CABLE_OUTPUT_NAME = "CABLE Output"
    private const val CABLE_INPUT_NAME = "CABLE Input"
    private const val INSTALLER_NAME = "VBCABLE_Setup_x64.exe"

    private val _installProgress = MutableStateFlow<String?>(null)
    val installProgress = _installProgress.asStateFlow()

    fun isVBCableInstalled(): Boolean {
        return when (PlatformInfo.currentOS) {
            PlatformInfo.OS.WINDOWS -> {
                val mixers = AudioSystem.getMixerInfo()
                mixers.any { 
                    it.name.contains(CABLE_OUTPUT_NAME, ignoreCase = true) || 
                    it.name.contains(CABLE_INPUT_NAME, ignoreCase = true) 
                }
            }
            PlatformInfo.OS.LINUX -> {
                VirtualAudioDevice.deviceExists()
            }
            PlatformInfo.OS.MACOS -> {
                BlackHoleManager.isInstalled()
            }
            else -> false
        }
    }

    suspend fun installVBCable() = withContext(Dispatchers.IO) {
        if (isVBCableInstalled()) {
            println("虚拟音频设备已安装。")
            setSystemDefaultMicrophone()
            return@withContext
        }
        
        when (PlatformInfo.currentOS) {
            PlatformInfo.OS.WINDOWS -> installWindowsVBCable()
            PlatformInfo.OS.LINUX -> installLinuxVirtualDevice()
            PlatformInfo.OS.MACOS -> {
                if (BlackHoleManager.isInstalled()) {
                    _installProgress.value = "BlackHole 已安装，请在系统设置中配置"
                    delay(2000)
                    _installProgress.value = null
                } else {
                    _installProgress.value = "请手动安装 BlackHole 虚拟音频驱动"
                    delay(3000)
                    _installProgress.value = "安装说明: existential.audio/blackhole/"
                    delay(3000)
                    _installProgress.value = null
                }
            }
            PlatformInfo.OS.OTHER -> {
                _installProgress.value = "当前操作系统不支持自动安装虚拟音频设备"
                delay(3000)
                _installProgress.value = null
            }
        }
    }
    
    private suspend fun installWindowsVBCable() {
        _installProgress.value = "正在检查安装包..."
        
        var installerFile = extractInstaller()
        
        if (installerFile == null || !installerFile.exists()) {
            println("Installer not found in resources. Attempting to download...")
            _installProgress.value = "正在下载 VB-Cable 驱动..."
            installerFile = downloadAndExtractInstaller()
        }

        if (installerFile == null || !installerFile.exists()) {
            println("VB-Cable installer not found. Please place '$INSTALLER_NAME' in resources or ensure internet access.")
            _installProgress.value = "安装失败：无法下载或找到驱动"
            delay(2000)
            _installProgress.value = null
            return
        }

        println("Installing VB-Cable...")
        _installProgress.value = "正在安装 VB-Cable 驱动..."
        
        try {
            val powerShellCommand = "Start-Process -FilePath '${installerFile.absolutePath}' -ArgumentList '-i -h' -Verb RunAs -Wait"
            println("Executing: $powerShellCommand")

            val processBuilder = ProcessBuilder(
                "powershell.exe",
                "-Command",
                powerShellCommand
            )
            processBuilder.redirectErrorStream(true)
            val process = processBuilder.start()
            
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            println("PowerShell execution finished. Exit code: $exitCode. Output: $output")
            
            delay(2000)
            
            if (isVBCableInstalled()) {
                println("VB-Cable installation verified.")
                _installProgress.value = "安装完成，正在配置.."
                setSystemDefaultMicrophone()
                _installProgress.value = "配置完成"
            } else {
                println("VB-Cable installation could not be verified.")
                _installProgress.value = "安装未完成或被取销"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _installProgress.value = "安装错误: ${e.message}"
        } finally {
            delay(2000)
            _installProgress.value = null
        }
    }
    
    private suspend fun installLinuxVirtualDevice() {
        _installProgress.value = "正在检查Linux音频系统..."
        
        try {
            if (VirtualAudioDevice.deviceExists()) {
                _installProgress.value = "虚拟音频设备已存在，正在配置..."
                delay(1000)
                setSystemDefaultMicrophone()
                _installProgress.value = "配置完成"
                delay(1000)
                _installProgress.value = null
                return
            }
            
            _installProgress.value = "正在创建虚拟音频设备..."
            
            val success = VirtualAudioDevice.setup()
            
            if (success) {
                _installProgress.value = "虚拟设备创建成功，正在配置..."
                delay(1000)
                setSystemDefaultMicrophone()
                _installProgress.value = "配置完成"
                delay(1000)
                _installProgress.value = null
            } else {
                _installProgress.value = "虚拟设备创建失败，请检查系统权限和音频服务"
                delay(3000)
                _installProgress.value = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _installProgress.value = "安装错误: ${e.message}"
            delay(2000)
            _installProgress.value = null
        }
    }

    private fun extractInstaller(): File? {
        try {
            val resourceStream = this::class.java.classLoader.getResourceAsStream(INSTALLER_NAME)
                ?: this::class.java.classLoader.getResourceAsStream("vbcable/$INSTALLER_NAME")
            
            if (resourceStream == null) {
                val localFile = File(INSTALLER_NAME)
                if (localFile.exists()) return localFile
                return null
            }

            val tempFile = File.createTempFile("vbcable_setup", ".exe")
            tempFile.deleteOnExit()
            
            FileOutputStream(tempFile).use { output ->
                resourceStream.copyTo(output)
            }
            return tempFile
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun downloadAndExtractInstaller(): File? {
        val downloadUrl = "https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack43.zip"
        val zipFile = File.createTempFile("vbcable_pack", ".zip")
        val outputDir = File(System.getProperty("java.io.tmpdir"), "vbcable_extracted_${System.currentTimeMillis()}")
        
        println("Downloading VB-Cable driver from $downloadUrl...")
        
        try {
            val url = java.net.URI(downloadUrl).toURL()
            val connection = url.openConnection()
            connection.connect()
            
            connection.getInputStream().use { input ->
                FileOutputStream(zipFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            println("Download complete. Extracting...")
            
            if (!outputDir.exists()) outputDir.mkdirs()
            
            java.util.zip.ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val entryFile = File(outputDir, entry.name)
                    
                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        entryFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(entryFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }
            
            val setupFile = File(outputDir, INSTALLER_NAME)
            if (setupFile.exists()) {
                println("Found installer at ${setupFile.absolutePath}")
                return setupFile
            }
            
            val found = outputDir.walkTopDown().find { it.name.equals(INSTALLER_NAME, ignoreCase = true) }
            if (found != null) {
                println("Found installer at ${found.absolutePath}")
                return found
            }
            
        } catch (e: Exception) {
            println("Failed to download or extract VB-Cable driver: ${e.message}")
            e.printStackTrace()
        } finally {
            zipFile.delete()
        }
        
        return null
    }

    suspend fun setSystemDefaultMicrophone(toCable: Boolean = true) = withContext(Dispatchers.IO) {
        when (PlatformInfo.currentOS) {
            PlatformInfo.OS.WINDOWS -> {
                if (toCable) setWindowsDefaultMicrophone() else restoreWindowsDefaultMicrophone()
            }
            PlatformInfo.OS.LINUX -> {
                if (toCable) Unit else VirtualAudioDevice.cleanup()
            }
            PlatformInfo.OS.MACOS -> {
                if (toCable) setMacOSDefaultMicrophone() else restoreMacOSDefaultMicrophone()
            }
            PlatformInfo.OS.OTHER -> println("当前操作系统不支持设置默认麦克风")
        }
    }

    private suspend fun setMacOSDefaultMicrophone() {
        if (!BlackHoleManager.isSwitchAudioSourceInstalled()) {
            println("macOS: switchaudio-osx 未安装，无法自动设置默认麦克风")
            println("请运行: brew install switchaudio-osx")
            return
        }

        if (!BlackHoleManager.isInstalled()) {
            println("macOS: BlackHole 未安装")
            return
        }

        BlackHoleManager.saveCurrentInputDevice()

        val json = BlackHoleManager.getInputDevicesJson()
        if (json == null) {
            println("macOS: 获取输入设备列表失败")
            return
        }

        val blackHoleDevice = BlackHoleManager.findBlackHoleInJson(json)
        if (blackHoleDevice == null) {
            println("macOS: 未找到 BlackHole 输入设备")
            return
        }
        
        println("macOS: 找到 BlackHole 设备: ${blackHoleDevice.name} (ID: ${blackHoleDevice.id})")

        val success = BlackHoleManager.setDefaultInputDevice(blackHoleDevice.id)
        if (success) {
            println("macOS: 已将默认麦克风设置为 BlackHole")
        } else {
            println("macOS: 设置默认麦克风失败")
        }
    }

    private suspend fun restoreMacOSDefaultMicrophone() {
        val success = BlackHoleManager.restoreOriginalInputDevice()
        if (success) {
            println("macOS: 已恢复原始麦克风设备")
        } else {
            println("macOS: 恢复原始麦克风失败或无记录")
        }
    }

    
    private fun restoreWindowsDefaultMicrophone() {
        println("Windows平台：恢复原始默认麦克风功能暂未实现")
    }
    
    private fun setWindowsDefaultMicrophone() {
        val script = """
${'$'}csharpSource = @"
using System;
using System.Runtime.InteropServices;

namespace AudioSwitcher {
    using System;
    using System.Runtime.InteropServices;

    [StructLayout(LayoutKind.Sequential)]
    public struct PropertyKey {
        public Guid fmtid;
        public uint pid;
    }

    [StructLayout(LayoutKind.Explicit)]
    public struct PropVariant {
        [FieldOffset(0)] public short vt;
        [FieldOffset(2)] public short wReserved1;
        [FieldOffset(4)] public short wReserved2;
        [FieldOffset(6)] public short wReserved3;
        [FieldOffset(8)] public IntPtr pwszVal;
        [FieldOffset(8)] public int iVal;
    }

    [ComImport, Guid("870af99c-171d-4f9e-af0d-e63df40c2bc9")]
    public class PolicyConfigClient { }

    [ComImport, Guid("f8679f50-850a-41cf-9c72-430f290290c8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IPolicyConfig {
        [PreserveSig] int GetMixFormat(string pszDeviceName, out IntPtr ppFormat);
        [PreserveSig] int GetDeviceFormat(string pszDeviceName, int bDefault, out IntPtr ppFormat);
        [PreserveSig] int ResetDeviceFormat(string pszDeviceName);
        [PreserveSig] int SetDeviceFormat(string pszDeviceName, IntPtr pEndpointFormat, IntPtr mixFormat);
        [PreserveSig] int GetProcessingPeriod(string pszDeviceName, int bDefault, out long pmftDefault, out long pmftMinimum);
        [PreserveSig] int SetProcessingPeriod(string pszDeviceName, long pmftDefault);
        [PreserveSig] int GetShareMode(string pszDeviceName, out IntPtr pDeviceShareMode);
        [PreserveSig] int SetShareMode(string pszDeviceName, IntPtr deviceShareMode);
        [PreserveSig] int GetPropertyValue(string pszDeviceName, IntPtr key, out IntPtr value);
        [PreserveSig] int SetPropertyValue(string pszDeviceName, IntPtr key, IntPtr value);
        [PreserveSig] int SetDefaultEndpoint(string pszDeviceName, int role);
        [PreserveSig] int SetEndpointVisibility(string pszDeviceName, int bVisible);
    }

    public class AudioHelper {
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        private delegate int EnumAudioEndpointsDelegate(IntPtr enumerator, int dataFlow, int state, out IntPtr collection);

        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        private delegate int GetCountDelegate(IntPtr collection, out int count);

        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        private delegate int ItemDelegate(IntPtr collection, int index, out IntPtr device);

        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        private delegate int GetIdDelegate(IntPtr device, out IntPtr idStr);

        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        private delegate int OpenPropertyStoreDelegate(IntPtr device, int storageAccess, out IntPtr store);

        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        private delegate int GetValueDelegate(IntPtr store, ref PropertyKey key, out PropVariant variant);

        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        private delegate int ReleaseDelegate(IntPtr unknown);

        private static T GetMethod<T>(IntPtr ptr, int slot) {
            IntPtr vtable = Marshal.ReadIntPtr(ptr);
            IntPtr methodPtr = Marshal.ReadIntPtr(vtable, slot * IntPtr.Size);
            return Marshal.GetDelegateForFunctionPointer<T>(methodPtr);
        }

        private static void Release(IntPtr ptr) {
            if (ptr != IntPtr.Zero) {
                try {
                    var release = GetMethod<ReleaseDelegate>(ptr, 2);
                    release(ptr);
                } catch { }
            }
        }

        public static void SetDefaultCableOutput() {
            IntPtr enumerator = IntPtr.Zero;
            IntPtr collection = IntPtr.Zero;
            
            try {
                Type enumeratorType = Type.GetTypeFromCLSID(new Guid("BCDE0395-E52F-467C-8E3D-C4579291692E"));
                object enumeratorObj = Activator.CreateInstance(enumeratorType);
                enumerator = Marshal.GetIUnknownForObject(enumeratorObj);

                var enumEndpoints = GetMethod<EnumAudioEndpointsDelegate>(enumerator, 3);
                int hr = enumEndpoints(enumerator, 1, 1, out collection);
                if (hr != 0) throw new Exception("EnumAudioEndpoints failed: " + hr);

                var getCount = GetMethod<GetCountDelegate>(collection, 3);
                int count;
                getCount(collection, out count);

                var getItem = GetMethod<ItemDelegate>(collection, 4);

                Guid PKEY_Device_FriendlyName_FmtId = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0");
                uint PKEY_Device_FriendlyName_Pid = 14;

                for (int i = 0; i < count; i++) {
                    IntPtr device = IntPtr.Zero;
                    IntPtr store = IntPtr.Zero;
                    IntPtr idPtr = IntPtr.Zero;
                    
                    try {
                        getItem(collection, i, out device);
                        
                        var openStore = GetMethod<OpenPropertyStoreDelegate>(device, 4);
                        openStore(device, 0, out store);
                        
                        var getValue = GetMethod<GetValueDelegate>(store, 5);
                        
                        PropertyKey key;
                        key.fmtid = PKEY_Device_FriendlyName_FmtId;
                        key.pid = PKEY_Device_FriendlyName_Pid;
                        
                        PropVariant propVar;
                        getValue(store, ref key, out propVar);
                        
                        if (propVar.vt == 31) {
                            string name = Marshal.PtrToStringUni(propVar.pwszVal);
                            
                            if (name != null && name.Contains("CABLE Output")) {
                                var getId = GetMethod<GetIdDelegate>(device, 5);
                                getId(device, out idPtr);
                                string id = Marshal.PtrToStringUni(idPtr);
                                
                                Console.WriteLine("Found device: " + name);
                                
                                try {
                                    IPolicyConfig policyConfig = new PolicyConfigClient() as IPolicyConfig;
                                    policyConfig.SetDefaultEndpoint(id, 0);
                                    policyConfig.SetDefaultEndpoint(id, 1);
                                    policyConfig.SetDefaultEndpoint(id, 2);
                                    Console.WriteLine("Set as default successfully.");
                                } catch (Exception ex) {
                                    Console.WriteLine("Error setting default endpoint: " + ex.Message)
                                }
                                return;
                            }
                        }
                    } finally {
                        if (idPtr != IntPtr.Zero) Marshal.FreeCoTaskMem(idPtr);
                        Release(store);
                        Release(device);
                    }
                }
                Console.WriteLine("CABLE Output not found in active devices.");
            } catch (Exception e) {
                Console.WriteLine("Error in AudioHelper: " + e.Message);
                Console.WriteLine(e.StackTrace);
            } finally {
                Release(collection);
                Release(enumerator);
            }
        }
    }
}
"@

Add-Type -TypeDefinition ${'$'}csharpSource
[AudioSwitcher.AudioHelper]::SetDefaultCableOutput()
""".trimIndent()
        
        try {
            val tempScript = File.createTempFile("setdefaultmic", ".ps1")
            val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            tempScript.writeBytes(bom + script.toByteArray(Charsets.UTF_8))
            
            val process = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-Sta",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                tempScript.absolutePath
            )
            process.redirectErrorStream(true)
            val p = process.start()
            val output = p.inputStream.bufferedReader().readText()
            p.waitFor()
            println("SetDefaultMic Output: $output")
            tempScript.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun uninstallVBCable() {
         println("Uninstall functionality not fully implemented. Please uninstall from Control Panel.")
    }
}
