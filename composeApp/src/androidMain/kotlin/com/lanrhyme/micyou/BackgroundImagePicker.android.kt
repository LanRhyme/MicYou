package com.lanrhyme.micyou

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

actual object BackgroundImagePicker {
    private var launcher: ActivityResultLauncher<Intent>? = null
    private var callback: ((String?) -> Unit)? = null
    
    fun registerLauncher(activity: MainActivity): ActivityResultLauncher<Intent> {
        val l = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = result.data?.data
                if (uri != null) {
                    val savedPath = copyToInternalStorage(uri)
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
    
    actual fun pickImage(onResult: (String?) -> Unit) {
        callback = onResult
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = "image/*"
        }
        launcher?.launch(intent) ?: run {
            onResult(null)
        }
    }
    
    private fun copyToInternalStorage(uri: Uri): String? {
        return try {
            val context = AndroidContext.getContext() ?: return null
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            
            val backgroundDir = File(context.filesDir, "backgrounds")
            if (!backgroundDir.exists()) {
                backgroundDir.mkdirs()
            }
            
            val outputFile = File(backgroundDir, "custom_background.jpg")
            outputFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
            
            outputFile.absolutePath
        } catch (e: Exception) {
            Logger.e("BackgroundImage", "Failed to copy image to internal storage", e)
            null
        }
    }
}
