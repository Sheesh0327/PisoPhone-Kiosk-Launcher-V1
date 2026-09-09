package com.pisophone.kiosk.security

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

object KioskUpdateManager {
    private const val TAG = "KioskUpdate"

    sealed class UpdateState {
        object Idle : UpdateState()
        data class Downloading(val progress: Float) : UpdateState()
        object Installing : UpdateState()
        object Success : UpdateState()
        data class Error(val message: String) : UpdateState()
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState

    private val httpClient = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)

    fun startUpdate(context: Context, url: String) {
        if (_updateState.value is UpdateState.Downloading || _updateState.value is UpdateState.Installing) {
            return
        }

        _updateState.value = UpdateState.Downloading(0f)
        scope.launch {
            try {
                val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(updateDir, "piso_update.apk")
                if (apkFile.exists()) {
                    apkFile.delete()
                }

                downloadApk(url, apkFile) { progress ->
                    _updateState.value = UpdateState.Downloading(progress)
                }

                _updateState.value = UpdateState.Installing
                val installed = triggerInstallation(context, apkFile)
                if (!installed) {
                    _updateState.value = UpdateState.Error("Failed to trigger installation.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update failed: ${e.message}", e)
                _updateState.value = UpdateState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun downloadApk(url: String, targetFile: File, onProgress: (Float) -> Unit) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to download APK: HTTP status ${response.code}")
            }

            val body = response.body ?: throw IOException("Empty response body")
            val totalBytes = body.contentLength()
            
            body.byteStream().use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            onProgress(totalRead.toFloat() / totalBytes)
                        }
                    }
                    outputStream.flush()
                }
            }
        }
    }

    private fun triggerInstallation(context: Context, apkFile: File): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)

        return if (isDeviceOwner) {
            Log.i(TAG, "App is Device Owner. Triggering silent installation...")
            installSilent(context, apkFile)
        } else {
            Log.i(TAG, "App is not Device Owner. Prompting standard installation...")
            installStandard(context, apkFile)
        }
    }

    private fun installSilent(context: Context, apkFile: File): Boolean {
        var session: PackageInstaller.Session? = null
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            
            val sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)

            val out = session.openWrite("COSU_Install", 0, apkFile.length())
            FileInputStream(apkFile).use { fis ->
                val buffer = ByteArray(65536)
                var c: Int
                while (fis.read(buffer).also { c = it } != -1) {
                    out.write(buffer, 0, c)
                }
                session.fsync(out)
            }
            out.close()

            val intent = Intent(context, com.pisophone.kiosk.receiver.KioskDeviceAdminReceiver::class.java).apply {
                action = "com.pisophone.kiosk.ACTION_INSTALL_COMPLETE"
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)

            session.commit(pendingIntent.intentSender)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Silent install failed: ${e.message}", e)
            return false
        } finally {
            session?.close()
        }
    }

    private fun installStandard(context: Context, apkFile: File): Boolean {
        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Standard install failed: ${e.message}", e)
            false
        }
    }

    fun resetState() {
        _updateState.value = UpdateState.Idle
    }
}
