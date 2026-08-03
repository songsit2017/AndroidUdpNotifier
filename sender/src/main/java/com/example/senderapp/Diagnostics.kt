package com.example.senderapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object Diagnostics {
    private const val MAX_LOG_BYTES = 64 * 1024
    private val executor = Executors.newSingleThreadExecutor()
    private var appContext: Context? = null

    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                crashFile(context).apply {
                    parentFile?.mkdirs()
                    writeText(sanitize("${timestamp()} ${thread.name}\n${throwable.stackTraceToString()}"))
                }
            } catch (_: Exception) { }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun log(message: String) {
        val context = appContext ?: return
        executor.execute {
            try {
                val file = logFile(context)
                file.parentFile?.mkdirs()
                if (file.length() > MAX_LOG_BYTES) {
                    val tail = file.readText().takeLast(MAX_LOG_BYTES / 2)
                    file.writeText(tail)
                }
                file.appendText("${timestamp()} ${sanitize(message)}\n")
            } catch (_: Exception) { }
        }
    }

    fun export(context: Context) {
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong("LAST_RECEIVER_SEEN", 0L)
        val age = if (lastSeen == 0L) "never" else "${(System.currentTimeMillis() - lastSeen) / 1000}s"
        val report = buildString {
            appendLine("AndroidUdpNotifier Sender diagnostics")
            appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Paired: ${SecureUdp.hasPairingCode(context)}")
            appendLine("Receiver last seen: $age")
            appendLine("Receiver address: ${maskIp(prefs.getString("LAST_RECEIVER_IP", null))}")
            appendLine("\nLast crash:\n${crashFile(context).takeIf { it.isFile }?.readText() ?: "none"}")
            appendLine("\nRecent events:\n${logFile(context).takeIf { it.isFile }?.readText() ?: "none"}")
        }
        shareReport(context, report, "sender-diagnostics.txt")
    }

    private fun shareReport(context: Context, content: String, name: String) {
        val file = File(context.filesDir, "diagnostics/$name")
        file.parentFile?.mkdirs()
        file.writeText(sanitize(content))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share diagnostics"))
    }

    private fun logFile(context: Context) = File(context.filesDir, "diagnostics/events.log")
    private fun crashFile(context: Context) = File(context.filesDir, "diagnostics/last-crash.txt")
    private fun timestamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    private fun maskIp(ip: String?): String = ip?.replace(Regex("(\\d+\\.\\d+\\.)\\d+\\.\\d+"), "$1x.x") ?: "unknown"
    private fun sanitize(value: String): String = value
        .replace(Regex("(?i)(token|secret|key|code|password)\\s*[:=]\\s*\\S+"), "$1=[redacted]")
        .replace(Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")) { maskIp(it.value) }
}

class App : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.initialize(this)
    }
}
