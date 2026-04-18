package com.lanrhyme.micyou

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

object PluginFileChooserHelper {
    private var launcher: ActivityResultLauncher<Intent>? = null
    private var callback: ((String?) -> Unit)? = null
    
    fun registerLauncher(activity: MainActivity): ActivityResultLauncher<Intent> {
        val l = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = result.data?.data
                if (uri != null) {
                    val savedPath = copyPluginToInternalStorage(uri)
                    callback?.invoke(savedPath)
                } else {
                    callback?.invoke(null)
                }
            } else {
                callback?.invoke(null)
            }
        }
        launcher = l
        return l
    }
    
    fun pickPluginFile(onResult: (String?) -> Unit) {
        callback = onResult
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/java-archive"))
        }
        launcher?.launch(intent) ?: run {
            onResult(null)
        }
    }
    
    private fun copyPluginToInternalStorage(uri: Uri): String? {
        return try {
            val context = AndroidContext.getContext() ?: return null
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            
            val pluginDir = File(context.cacheDir, "plugin_imports")
            if (!pluginDir.exists()) {
                pluginDir.mkdirs()
            }
            
            val fileName = getFileNameFromUri(uri) ?: "plugin_${System.currentTimeMillis()}.zip"
            val outputFile = File(pluginDir, fileName)
            outputFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
            
            outputFile.absolutePath
        } catch (e: Exception) {
            Logger.e("PluginFileChooser", "Failed to copy plugin file", e)
            null
        }
    }
    
    private fun getFileNameFromUri(uri: Uri): String? {
        val context = AndroidContext.getContext() ?: return null
        var fileName: String? = null
        
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }
        
        return fileName
    }
}

actual fun openPluginFileChooser(onResult: (String?) -> Unit) {
    PluginFileChooserHelper.pickPluginFile(onResult)
}
