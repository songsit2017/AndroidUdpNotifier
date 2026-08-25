package com.example.receiverapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMOTE_NOTIFICATION) return
        val senderIp = intent.getStringExtra(EXTRA_SENDER_IP).orEmpty()
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID).orEmpty()
        val remoteKey = intent.getStringExtra(EXTRA_REMOTE_KEY)
        if (senderIp.isBlank() || actionId.isBlank()) return

        if (intent.getBooleanExtra(EXTRA_VOICE_REPLY, false)) {
            context.startActivity(
                Intent(context, VoiceReplyActivity::class.java).apply {
                    putExtra("actionId", actionId)
                    putExtra("senderIp", senderIp)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            )
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val json = JSONObject().apply { put("actionId", actionId) }.toString()
                val encrypted = SecureUdp.encode(context, json) ?: return@launch
                val payload = encrypted.toByteArray(Charsets.UTF_8)
                DatagramSocket().use { socket ->
                    socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(senderIp), 8889))
                }
                DuduNotificationPublisher(context).cancel(remoteKey, null)
                AppLogger.log("Sent DUDU native notification action")
            } catch (error: Exception) {
                AppLogger.log("DUDU native action failed: ${error.javaClass.simpleName}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_REMOTE_NOTIFICATION = "com.example.receiverapp.REMOTE_NOTIFICATION_ACTION"
        const val EXTRA_SENDER_IP = "senderIp"
        const val EXTRA_ACTION_ID = "actionId"
        const val EXTRA_REMOTE_KEY = "remoteKey"
        const val EXTRA_VOICE_REPLY = "voiceReply"
    }
}
