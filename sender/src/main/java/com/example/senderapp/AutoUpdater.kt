package com.example.senderapp

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object AutoUpdater {

    private const val TAG = "AutoUpdater"
    private const val GITHUB_API_URL = "https://api.github.com/repos/songsit2017/AndroidUdpNotifier/releases/latest"
    private const val ASSET_NAME = "ADH-Notifier-Server.apk"
    private const val MAX_APK_BYTES = 100L * 1024L * 1024L
    private const val UPDATE_PREFS = "AutoUpdaterPrefs"
    private const val PENDING_APK = "pending_apk"

    fun checkForUpdates(activity: Activity, showToastIfUpToDate: Boolean = false) {
        if (showToastIfUpToDate) {
            android.widget.Toast.makeText(activity, "Checking for updates...", android.widget.Toast.LENGTH_SHORT).show()
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(GITHUB_API_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val tagName = json.getString("tag_name")
                    
                    val currentVersion = "v${BuildConfig.VERSION_NAME}"
                    
                    if (tagName != currentVersion) {
                        // Found new version
                        val assets = json.getJSONArray("assets")
                        var downloadUrl = ""
                        var expectedDigest = ""
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            if (asset.getString("name") == ASSET_NAME) {
                                downloadUrl = asset.getString("browser_download_url")
                                expectedDigest = asset.optString("digest")
                                break
                            }
                        }
                        
                        if (downloadUrl.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                showUpdateDialog(activity, tagName, downloadUrl, expectedDigest)
                            }
                        }
                    } else if (showToastIfUpToDate) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(activity, "App is up to date ($currentVersion)", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    if (showToastIfUpToDate) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(activity, "Failed to check update: HTTP ${connection.responseCode}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates", e)
                if (showToastIfUpToDate) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(activity, "Failed to check for updates", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(activity: Activity, newVersion: String, downloadUrl: String, expectedDigest: String) {
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle("New Update Available")
            .setMessage("Version $newVersion is available. Do you want to download and install it?")
            .setPositiveButton("Update") { _, _ ->
                downloadAndInstall(activity, downloadUrl, newVersion, expectedDigest)
            }
            .setNegativeButton("Later", null)
            .create()
            
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(android.graphics.Color.parseColor("#1976D2"))
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(android.graphics.Color.parseColor("#1976D2"))
        }
        dialog.show()
    }

    private fun downloadAndInstall(context: Context, url: String, version: String, expectedDigest: String) {
        android.widget.Toast.makeText(context, "Downloading update...", android.widget.Toast.LENGTH_LONG).show()
        CoroutineScope(Dispatchers.IO).launch {
            var partial: File? = null
            try {
                val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: error("Download directory is unavailable")
                val destination = File(directory, "update_$version.apk")
                val partialFile = File(directory, "update_$version.apk.part")
                partial = partialFile
                destination.delete()
                partialFile.delete()

                val connection = openSecureDownload(url)
                val declaredLength = connection.contentLengthLong
                if (declaredLength > MAX_APK_BYTES) error("Update is too large")

                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(partialFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            if (downloaded > MAX_APK_BYTES) error("Update is too large")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                connection.disconnect()
                if (downloaded == 0L || (declaredLength >= 0L && downloaded != declaredLength)) {
                    error("Incomplete update download")
                }
                val actualDigest = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val expectedSha256 = expectedDigest.removePrefix("sha256:")
                if (expectedSha256.isNotBlank() && !actualDigest.equals(expectedSha256, ignoreCase = true)) {
                    error("Update checksum mismatch")
                }
                if (!partialFile.renameTo(destination)) error("Could not finalize update")
                withContext(Dispatchers.Main) { installApk(context, destination) }
            } catch (e: Exception) {
                partial?.delete()
                Log.e(TAG, "Update download failed", e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Download failed. Please try again.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openSecureDownload(initialUrl: String): HttpURLConnection {
        var current = URL(initialUrl)
        repeat(6) {
            if (current.protocol != "https") error("Insecure update URL")
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("User-Agent", "AndroidUdpNotifier-Updater")
            val responseCode = connection.responseCode
            if (responseCode in 300..399) {
                val location = connection.getHeaderField("Location") ?: error("Invalid update redirect")
                current = URL(current, location)
                connection.disconnect()
            } else {
                if (responseCode !in 200..299) {
                    connection.disconnect()
                    error("Download failed: HTTP $responseCode")
                }
                return connection
            }
        }
        error("Too many update redirects")
    }

    private fun installApk(context: Context, file: File) {
        try {
            if (!isTrustedUpdate(context, file)) {
                file.delete()
                android.widget.Toast.makeText(context, "Update rejected: invalid app signature", android.widget.Toast.LENGTH_LONG).show()
                return
            }
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            } else {
                Uri.fromFile(file)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            android.widget.Toast.makeText(context, "Failed to start installation", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun isTrustedUpdate(context: Context, file: File): Boolean {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES
        val current = pm.getPackageInfo(context.packageName, flags)
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        val currentSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) current.signingInfo?.apkContentsSigners else current.signatures
        val archiveSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.signingInfo?.apkContentsSigners else archive.signatures
        return !currentSignatures.isNullOrEmpty() && !archiveSignatures.isNullOrEmpty() &&
            currentSignatures.map { it.toCharsString() }.toSet() == archiveSignatures.map { it.toCharsString() }.toSet()
    }
}
