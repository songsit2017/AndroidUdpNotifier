package com.example.duducompat

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.core.app.NotificationCompat
import org.json.JSONArray

class DuduBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_POST -> post(context, intent)
            ACTION_CANCEL -> cancel(context, intent)
        }
    }

    private fun post(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        createChannel(context)

        val remoteKey = intent.getStringExtra(EXTRA_REMOTE_KEY).orEmpty().ifBlank {
            listOf(
                intent.getStringExtra(EXTRA_SOURCE_PACKAGE),
                intent.getStringExtra(EXTRA_TITLE),
                intent.getStringExtra(EXTRA_TEXT)
            ).joinToString("|")
        }
        val notificationId = stableId(remoteKey)
        val callbackPackage = intent.getStringExtra(EXTRA_CALLBACK_PACKAGE).orEmpty()
        val sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE).orEmpty()
        val sourceAppName = intent.getStringExtra(EXTRA_SOURCE_APP_NAME).orEmpty()
        val rawTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val sourceLabel = sourceAppName.ifBlank { sourcePackage }
        val displayTitle = when {
            sourceLabel.isBlank() -> rawTitle
            rawTitle.isBlank() -> sourceLabel
            rawTitle.equals(sourceLabel, ignoreCase = true) -> rawTitle
            else -> "$sourceLabel • $rawTitle"
        }
        // DUDU replaces android.title with the connector app label and ignores
        // subText, so the originating app must be included in android.text.
        val displayText = when {
            displayTitle.isBlank() -> text
            text.isBlank() || text.equals(displayTitle, ignoreCase = true) -> displayTitle
            else -> "$displayTitle\n$text"
        }

        val contentIntent = if (callbackPackage.isNotBlank()) {
            PendingIntent.getActivity(
                context,
                notificationId,
                Intent().apply {
                    component = ComponentName(callbackPackage, "com.example.receiverapp.MainActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val extras = Bundle().apply {
            putString(EXTRA_SOURCE_PACKAGE, sourcePackage)
            putString(EXTRA_REMOTE_KEY, remoteKey)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dudu_notification)
            .setContentTitle(displayTitle)
            .setContentText(displayText)
            .setSubText(sourceAppName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayText))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setWhen(intent.getLongExtra(EXTRA_POST_TIME, System.currentTimeMillis()))
            .setShowWhen(true)
            .addExtras(extras)

        if (contentIntent != null) builder.setContentIntent(contentIntent)
        decodeBitmap(intent.getStringExtra(EXTRA_LARGE_ICON))?.let(builder::setLargeIcon)

        val senderIp = intent.getStringExtra(EXTRA_SENDER_IP).orEmpty()
        val actions = runCatching {
            intent.getStringExtra(EXTRA_ACTIONS_JSON)?.let(::JSONArray)
        }.getOrNull()
        if (actions != null) {
            for (index in 0 until minOf(actions.length(), MAX_ACTIONS)) {
                val action = actions.optJSONObject(index) ?: continue
                val actionId = action.optString("id")
                val actionTitle = action.optString("title")
                if (actionId.isBlank() || actionTitle.isBlank()) continue
                builder.addAction(
                    R.drawable.ic_dudu_notification,
                    actionTitle,
                    callbackIntent(context, callbackPackage, senderIp, actionId, remoteKey, false)
                )
            }
        }

        val replyActionId = intent.getStringExtra(EXTRA_REPLY_ACTION_ID).orEmpty()
        if (replyActionId.isNotBlank() && senderIp.isNotBlank()) {
            builder.addAction(
                R.drawable.ic_dudu_notification,
                "ตอบกลับด้วยเสียง",
                callbackIntent(context, callbackPackage, senderIp, replyActionId, remoteKey, true)
            )
        }

        context.getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_TAG, notificationId, builder.build())
    }

    private fun cancel(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val remoteKey = intent.getStringExtra(EXTRA_REMOTE_KEY)
        if (!remoteKey.isNullOrBlank()) {
            manager.cancel(NOTIFICATION_TAG, stableId(remoteKey))
            return
        }

        val sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE)
        if (sourcePackage.isNullOrBlank() || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        manager.activeNotifications
            .filter { status ->
                status.tag == NOTIFICATION_TAG &&
                    status.notification.extras.getString(EXTRA_SOURCE_PACKAGE) == sourcePackage
            }
            .forEach { status -> manager.cancel(status.tag, status.id) }
    }

    private fun callbackIntent(
        context: Context,
        callbackPackage: String,
        senderIp: String,
        actionId: String,
        remoteKey: String,
        voiceReply: Boolean
    ): PendingIntent {
        val intent = Intent(context, DuduActionReceiver::class.java).apply {
            putExtra(EXTRA_CALLBACK_PACKAGE, callbackPackage)
            putExtra(EXTRA_SENDER_IP, senderIp)
            putExtra(EXTRA_ACTION_ID, actionId)
            putExtra(EXTRA_REMOTE_KEY, remoteKey)
            putExtra(EXTRA_VOICE_REPLY, voiceReply)
        }
        return PendingIntent.getBroadcast(
            context,
            stableId("$remoteKey|$actionId|$voiceReply"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "DUDU Phone Notifications", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Phone notifications displayed by DUDU Launcher"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
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

    companion object {
        const val ACTION_POST = "com.example.duducompat.POST"
        const val ACTION_CANCEL = "com.example.duducompat.CANCEL"
        const val BRIDGE_PERMISSION = "com.example.receiverapp.permission.DUDU_BRIDGE"

        const val EXTRA_CALLBACK_PACKAGE = "callbackPackage"
        const val EXTRA_SOURCE_PACKAGE = "sourcePackage"
        const val EXTRA_SOURCE_APP_NAME = "sourceAppName"
        const val EXTRA_REMOTE_KEY = "remoteKey"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_LARGE_ICON = "largeIconBase64"
        const val EXTRA_ACTIONS_JSON = "actionsJson"
        const val EXTRA_REPLY_ACTION_ID = "replyActionId"
        const val EXTRA_SENDER_IP = "senderIp"
        const val EXTRA_ACTION_ID = "actionId"
        const val EXTRA_VOICE_REPLY = "voiceReply"
        const val EXTRA_POST_TIME = "postTime"

        private const val CHANNEL_ID = "DuduPhoneNotifications"
        private const val NOTIFICATION_TAG = "dudu_phone_remote"
        private const val MAX_ACTIONS = 3
        private const val ID_FLOOR = 2000

        private fun stableId(value: String): Int =
            (value.hashCode() and 0x7fffffff).coerceAtLeast(ID_FLOOR)
    }
}
