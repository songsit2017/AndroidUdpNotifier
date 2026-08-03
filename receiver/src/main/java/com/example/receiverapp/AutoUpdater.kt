package com.example.receiverapp

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AutoUpdater {

    private const val TAG = "AutoUpdater"
    private const val GITHUB_API_URL = "https://api.github.com/repos/songsit2017/AndroidUdpNotifier/releases/latest"
    private const val ASSET_NAME = "receiver-release.apk"

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
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            if (asset.getString("name") == ASSET_NAME) {
                                downloadUrl = asset.getString("browser_download_url")
                                break
                            }
                        }
                        
                        if (downloadUrl.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                showUpdateDialog(activity, tagName, downloadUrl)
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

    private fun showUpdateDialog(activity: Activity, newVersion: String, downloadUrl: String) {
        AlertDialog.Builder(activity)
            .setTitle("New Update Available")
            .setMessage("Version $newVersion is available. Do you want to download and install it?")
            .setPositiveButton("Update") { _, _ ->
                downloadAndInstall(activity, downloadUrl, newVersion)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAndInstall(context: Context, url: String, version: String) {
        android.widget.Toast.makeText(context, "Downloading update...", android.widget.Toast.LENGTH_LONG).show()
        
        val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update_$version.apk")
        if (destination.exists()) destination.delete()

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Downloading Receiver Update")
            .setDescription("Version $version")
            .setDestinationUri(Uri.fromFile(destination))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(ctxt: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    installApk(context, destination)
                    context.unregisterReceiver(this)
                }
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
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
