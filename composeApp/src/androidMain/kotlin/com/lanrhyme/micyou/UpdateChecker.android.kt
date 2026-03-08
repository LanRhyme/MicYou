package com.lanrhyme.micyou

import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

actual suspend fun writeToFile(path: String, writer: suspend ((ByteArray, Int, Int) -> Unit) -> Unit) {
    val file = File(path)
    file.parentFile?.mkdirs()
    FileOutputStream(file).use { fos ->
        writer { buffer, offset, length ->
            fos.write(buffer, offset, length)
        }
    }
}

actual fun findPlatformAsset(assets: List<GitHubAsset>): GitHubAsset? {
    return assets.find { it.name.contains("Android") && it.name.endsWith(".apk") }
}

actual fun getUpdateDownloadPath(fileName: String): String {
    val context = ContextHelper.getContext()
    val downloadDir = context?.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
    return File(downloadDir, fileName).absolutePath
}

actual fun installUpdate(filePath: String) {
    val context = ContextHelper.getContext() ?: run {
        Logger.e("UpdateInstaller", "Context not available")
        return
    }

    try {
        val file = File(filePath)
        if (!file.exists()) {
            Logger.e("UpdateInstaller", "APK file not found: $filePath")
            return
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Logger.e("UpdateInstaller", "Failed to install APK", e)
    }
}
