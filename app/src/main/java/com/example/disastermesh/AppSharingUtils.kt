package com.example.disastermesh

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object AppSharingUtils {
    /**
     * Shares the APK of the current app via Bluetooth or other available methods.
     * This allows users to distribute the app even when the internet is down.
     */
    fun shareApp(context: Context) {
        try {
            val app = context.applicationInfo
            val filePath = app.sourceDir
            val originalFile = File(filePath)
            
            // Use externalCacheDir for better sharing compatibility
            val tempDir = File(context.externalCacheDir, "shared_apps")
            tempDir.mkdirs()
            val tempFile = File(tempDir, "DisasterMesh_Offline.apk")
            originalFile.copyTo(tempFile, overwrite = true)

            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                tempFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            context.startActivity(Intent.createChooser(shareIntent, "Share App with Survivor"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
