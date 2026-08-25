package com.example.duducompat

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class DuduActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callbackPackage = intent.getStringExtra(DuduBridgeReceiver.EXTRA_CALLBACK_PACKAGE).orEmpty()
        if (callbackPackage.isBlank()) return

        val callback = Intent(ACTION_REMOTE_NOTIFICATION).apply {
            component = ComponentName(
                callbackPackage,
                "com.example.receiverapp.NotificationActionReceiver"
            )
            putExtra(
                DuduBridgeReceiver.EXTRA_SENDER_IP,
                intent.getStringExtra(DuduBridgeReceiver.EXTRA_SENDER_IP)
            )
            putExtra(
                DuduBridgeReceiver.EXTRA_ACTION_ID,
                intent.getStringExtra(DuduBridgeReceiver.EXTRA_ACTION_ID)
            )
            putExtra(
                DuduBridgeReceiver.EXTRA_REMOTE_KEY,
                intent.getStringExtra(DuduBridgeReceiver.EXTRA_REMOTE_KEY)
            )
            putExtra(
                DuduBridgeReceiver.EXTRA_VOICE_REPLY,
                intent.getBooleanExtra(DuduBridgeReceiver.EXTRA_VOICE_REPLY, false)
            )
        }
        context.sendBroadcast(callback, DuduBridgeReceiver.BRIDGE_PERMISSION)
    }

    companion object {
        private const val ACTION_REMOTE_NOTIFICATION =
            "com.example.receiverapp.REMOTE_NOTIFICATION_ACTION"
    }
}
