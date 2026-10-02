package com.pisophone.kiosk.security

import com.pisophone.kiosk.BuildConfig

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
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
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import com.pisophone.kiosk.util.DiagnosticsLog

object KioskUpdateManager {
    private const val TAG = "KioskUpdate"
    private val VERSION_INFO_URL = "${BuildConfig.UPDATE_BASE_URL}/app.json"

    /** What the website publishes next to the APK (written by the build workflow). */
    data class RemoteVersion(val versionCode: Int, val sha256: String)

    internal fun parseRemoteVersion(json: String): RemoteVersion? {
        return try {
            val obj = JSONObject(json)
            val code = obj.optInt("versionCode", -1)
            if (code <= 0) null else RemoteVersion(code, obj.optString("sha256", "").lowercase())
        } catch (e: Exception) {
            null
        }
    }

    /** Only a strictly higher versionCode is an update; installing the same build again does nothing useful. */
    internal fun isUpdateAvailable(remoteVersionCode: Int, localVersionCode: Int): Boolean =
        remoteVersionCode > localVersionCode

    /** An APK is a ZIP archive. A hosting fallback page (HTML) or truncated body fails this at once. */
    internal fun hasZipHeader(file: File): Boolean {
        if (!file.isFile || file.length() < 4) return false
        return try {
            FileInputStream(file).use { input ->
                val head = ByteArray(4)
                input.read(head) == 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                    head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
            }
        } catch (e: IOException) {
            false
        }
    }

    internal fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(65536)
            var n: Int
            while (input.read(buffer).also { n = it } != -1) digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    sealed class UpdateState {
        object Idle : UpdateState()
        data class Downloading(val progress: Float) : UpdateState()
        object Installing : UpdateState()
        object Success : UpdateState()
        data class UpToDate(val message: String) : UpdateState()
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

                val localCode = com.pisophone.kiosk.BuildConfig.VERSION_CODE
                val remote = fetchRemoteVersion()
                if (!isUpdateAvailable(remote.versionCode, localCode)) {
                    _updateState.value = UpdateState.UpToDate(
                        "Already up to date (installed build $localCode, latest published ${remote.versionCode})."
                    )
                    return@launch
                }

                downloadApk(url, apkFile) { progress ->
                    _updateState.value = UpdateState.Downloading(progress)
                }
                try {
                    verifyDownloadedApk(context, apkFile, remote)
                } catch (e: Exception) {
                    apkFile.delete()
                    throw e
                }

                _updateState.value = UpdateState.Installing
                val installed = triggerInstallation(context, apkFile)
                if (!installed) {
                    _updateState.value = UpdateState.Error("Failed to trigger installation.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update failed: ${e.message}", e)
                DiagnosticsLog.add("UPDATE", "failed: ${e.message}")
                _updateState.value = UpdateState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun fetchRemoteVersion(): RemoteVersion {
        val request = Request.Builder().url(VERSION_INFO_URL).header("Cache-Control", "no-cache").build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Could not check the latest version: HTTP status ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            return parseRemoteVersion(body)
                ?: throw IOException("Could not read the published version information.")
        }
    }

    private fun verifyDownloadedApk(context: Context, apkFile: File, remote: RemoteVersion) {
        if (!hasZipHeader(apkFile)) {
            throw IOException("The downloaded file is not an APK (the server may have returned an error page).")
        }
        if (remote.sha256.isNotEmpty() && !sha256Hex(apkFile).equals(remote.sha256, ignoreCase = true)) {
            throw IOException("The downloaded APK does not match the published checksum. Try again.")
        }
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            ?: throw IOException("The downloaded file could not be read as an Android package.")
        if (info.packageName != context.packageName) {
            throw IOException("The downloaded APK is for a different app (${info.packageName}).")
        }
        val downloadedCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
        if (!isUpdateAvailable(downloadedCode, com.pisophone.kiosk.BuildConfig.VERSION_CODE)) {
            throw IOException("The downloaded APK (build $downloadedCode) is not newer than the installed one.")
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
        var committed = false
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            
            val sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)

            session.openWrite("COSU_Install", 0, apkFile.length()).use { out ->
                FileInputStream(apkFile).use { fis ->
                    val buffer = ByteArray(65536)
                    var c: Int
                    while (fis.read(buffer).also { c = it } != -1) {
                        out.write(buffer, 0, c)
                    }
                    session.fsync(out)
                }
            }

            val intent = Intent(context, com.pisophone.kiosk.receiver.KioskDeviceAdminReceiver::class.java).apply {
                action = "com.pisophone.kiosk.ACTION_INSTALL_COMPLETE"
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)

            session.commit(pendingIntent.intentSender)
            committed = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Silent install failed: ${e.message}", e)
            if (!committed) {
                try {
                    session?.abandon()
                } catch (abandonEx: Exception) {
                    Log.w(TAG, "Failed to abandon failed install session: ${abandonEx.message}")
                }
            }
            return false
        } finally {
            try {
                session?.close()
            } catch (_: Exception) {}
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

    fun onInstallSuccess() {
        DiagnosticsLog.add("UPDATE", "install succeeded")
        _updateState.value = UpdateState.Success
    }

    fun onInstallError(message: String) {
        DiagnosticsLog.add("UPDATE", "install failed: $message")
        _updateState.value = UpdateState.Error(message)
    }

    fun resetState() {
        _updateState.value = UpdateState.Idle
    }
}
