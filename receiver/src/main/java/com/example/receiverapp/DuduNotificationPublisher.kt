package com.example.receiverapp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray

/**
 * Publishes phone notifications through Android's real notification pipeline.
 *
 * DUDU 3.7 only renders local notifications from a hard-coded WeChat/QQ
 * allow-list. When the separately signed compatibility connector is installed,
 * this class delegates the final post to that isolated package. Otherwise it
 * posts a normal Android notification and the receiver keeps its overlay
 * fallback so alerts are never silently lost.
 */
class DuduNotificationPublisher(private val context: Context) {

    data class RemoteNotification(
        val sourcePackage: String,
        val sourceAppName: String,
        val remoteKey: String,
        val title: String,
        val text: String,
        val imageBase64: String?,
        val appIconBase64: String?,
        val actions: JSONArray?,
        val replyActionId: String?,
        val senderIp: String,
        val postTime: Long
    )

    private val manager = NotificationManagerCompat.from(context)

    init {
        createChannel()
    }

    /** @return true only when the notification was delegated to DUDU's own UI. */
    fun publish(remote: RemoteNotification): Boolean {
        if (isNativeModeEnabled(context)) {
            val intent = Intent(ACTION_DUDU_POST).apply {
                setPackage(DUDU_COMPAT_PACKAGE)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra(EXTRA_CALLBACK_PACKAGE, context.packageName)
                putExtra(BRIDGE_EXTRA_SOURCE_PACKAGE, remote.sourcePackage)
                putExtra(BRIDGE_EXTRA_SOURCE_APP_NAME, remote.sourceAppName)
                putExtra(BRIDGE_EXTRA_REMOTE_KEY, remote.remoteKey)
                putExtra(EXTRA_TITLE, remote.title)
                putExtra(EXTRA_TEXT, remote.text)
                putExtra(EXTRA_ACTIONS_JSON, remote.actions?.toString())
                putExtra(EXTRA_REPLY_ACTION_ID, remote.replyActionId)
                putExtra(EXTRA_SENDER_IP, remote.senderIp)
                putExtra(EXTRA_POST_TIME, remote.postTime)
                // Keep binder transactions comfortably below Android's 1 MB limit.
                val icon = remote.appIconBase64?.takeIf { it.length <= MAX_BRIDGE_ICON_CHARS }
                putExtra(EXTRA_LARGE_ICON, icon)
            }
            context.sendBroadcast(intent, BRIDGE_PERMISSION)
            AppLogger.log("Published notification through DUDU compatibility connector")
            return true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            AppLogger.log("DUDU native notification skipped: notification permission is missing")
            return false
        }

        val stableKey = remote.remoteKey.ifBlank {
            "${remote.sourcePackage}|${remote.title}|${remote.text}"
        }
        val notificationId = stableId(stableKey)
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val extras = Bundle().apply {
            putString(EXTRA_SOURCE_PACKAGE, remote.sourcePackage)
            putString(EXTRA_SOURCE_APP_NAME, remote.sourceAppName)
            putString(EXTRA_REMOTE_KEY, stableKey)
        }
        val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dudu_notification)
            .setContentTitle(remote.title.ifBlank { remote.sourceAppName })
            .setContentText(remote.text)
            .setSubText(remote.sourceAppName.ifBlank { remote.sourcePackage })
            .setStyle(NotificationCompat.BigTextStyle().bigText(remote.text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setWhen(if (remote.postTime > 0L) remote.postTime else System.currentTimeMillis())
            .setShowWhen(true)
            .addExtras(extras)

        decodeBitmap(remote.imageBase64 ?: remote.appIconBase64)?.let(builder::setLargeIcon)

        remote.actions?.let { actions ->
            for (index in 0 until minOf(actions.length(), MAX_ACTIONS)) {
                val action = actions.optJSONObject(index) ?: continue
                val actionId = action.optString("id")
                val actionTitle = action.optString("title")
                if (actionId.isBlank() || actionTitle.isBlank()) continue

                val actionIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                    this.action = NotificationActionReceiver.ACTION_REMOTE_NOTIFICATION
                    putExtra(NotificationActionReceiver.EXTRA_SENDER_IP, remote.senderIp)
                    putExtra(NotificationActionReceiver.EXTRA_ACTION_ID, actionId)
                    putExtra(NotificationActionReceiver.EXTRA_REMOTE_KEY, stableKey)
                }
                val actionPendingIntent = PendingIntent.getBroadcast(
                    context,
                    stableId("$stableKey|$actionId"),
                    actionIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(R.drawable.ic_dudu_notification, actionTitle, actionPendingIntent)
            }
        }

        if (!remote.replyActionId.isNullOrBlank() && remote.senderIp.isNotBlank()) {
            val replyIntent = Intent(context, VoiceReplyActivity::class.java).apply {
                putExtra("actionId", remote.replyActionId)
                putExtra("senderIp", remote.senderIp)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val replyPendingIntent = PendingIntent.getActivity(
                context,
                stableId("$stableKey|reply"),
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_dudu_notification, "ตอบกลับด้วยเสียง", replyPendingIntent)
        }

        manager.notify(REMOTE_NOTIFICATION_TAG, notificationId, builder.build())
        AppLogger.log("Published Android notification from ${remote.sourcePackage}; DUDU overlay fallback remains active")
        return false
    }

    fun cancel(remoteKey: String?, sourcePackage: String?) {
        if (isDuduBridgeTrusted(context)) {
            context.sendBroadcast(
                Intent(ACTION_DUDU_CANCEL).apply {
                    setPackage(DUDU_COMPAT_PACKAGE)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra(BRIDGE_EXTRA_REMOTE_KEY, remoteKey)
                    putExtra(BRIDGE_EXTRA_SOURCE_PACKAGE, sourcePackage)
                },
                BRIDGE_PERMISSION
            )
        }

        if (!remoteKey.isNullOrBlank()) {
            manager.cancel(REMOTE_NOTIFICATION_TAG, stableId(remoteKey))
            return
        }

        if (sourcePackage.isNullOrBlank() || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        notificationManager.activeNotifications
            .filter { status ->
                status.tag == REMOTE_NOTIFICATION_TAG &&
                    status.notification.extras.getString(EXTRA_SOURCE_PACKAGE) == sourcePackage
            }
            .forEach { status -> notificationManager.cancel(status.tag, status.id) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "DUDU Phone Notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications forwarded securely from the paired phone"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun decodeBitmap(encoded: String?): Bitmap? {
        if (encoded.isNullOrBlank()) return null
        return try {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    private fun stableId(value: String): Int = (value.hashCode() and 0x7fffffff).coerceAtLeast(REMOTE_ID_FLOOR)

    companion object {
        const val PREF_DUDU_NATIVE_MODE = "PREF_DUDU_NATIVE_MODE"
        const val EXTRA_SOURCE_PACKAGE = "dudu.sourcePackage"
        const val EXTRA_SOURCE_APP_NAME = "dudu.sourceAppName"
        const val EXTRA_REMOTE_KEY = "dudu.remoteKey"

        private const val DUDU_PACKAGE = "com.dudu.autoui"
        private const val DUDU_COMPAT_PACKAGE = "com.tencent.mm"
        private const val BRIDGE_PERMISSION = "com.example.receiverapp.permission.DUDU_BRIDGE"
        private const val ACTION_DUDU_POST = "com.example.duducompat.POST"
        private const val ACTION_DUDU_CANCEL = "com.example.duducompat.CANCEL"
        private const val EXTRA_CALLBACK_PACKAGE = "callbackPackage"
        private const val BRIDGE_EXTRA_SOURCE_PACKAGE = "sourcePackage"
        private const val BRIDGE_EXTRA_SOURCE_APP_NAME = "sourceAppName"
        private const val BRIDGE_EXTRA_REMOTE_KEY = "remoteKey"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_LARGE_ICON = "largeIconBase64"
        private const val EXTRA_ACTIONS_JSON = "actionsJson"
        private const val EXTRA_REPLY_ACTION_ID = "replyActionId"
        private const val EXTRA_SENDER_IP = "senderIp"
        private const val EXTRA_POST_TIME = "postTime"
        private const val ALERT_CHANNEL_ID = "DuduPhoneNotifications"
        private const val REMOTE_NOTIFICATION_TAG = "dudu_phone_remote"
        private const val REMOTE_ID_FLOOR = 2000
        private const val MAX_ACTIONS = 3
        private const val MAX_BRIDGE_ICON_CHARS = 300_000

        fun isDuduOsInstalled(context: Context): Boolean = try {
            context.packageManager.getApplicationInfo(DUDU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        fun isDuduBridgeAvailable(context: Context): Boolean {
            if (!isDuduOsInstalled(context) || !isDuduBridgeTrusted(context)) return false
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.packageManager.checkPermission(
                    Manifest.permission.POST_NOTIFICATIONS,
                    DUDU_COMPAT_PACKAGE
                ) == PackageManager.PERMISSION_GRANTED
        }

        private fun isDuduBridgeTrusted(context: Context): Boolean = try {
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo(DUDU_COMPAT_PACKAGE, 0)
            appInfo.enabled && packageManager.checkSignatures(
                context.packageName,
                DUDU_COMPAT_PACKAGE
            ) == PackageManager.SIGNATURE_MATCH
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        fun isNativeModeEnabled(context: Context): Boolean {
            val enabledByUser = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                .getBoolean(PREF_DUDU_NATIVE_MODE, true)
            return enabledByUser && isDuduBridgeAvailable(context)
        }
    }
}
