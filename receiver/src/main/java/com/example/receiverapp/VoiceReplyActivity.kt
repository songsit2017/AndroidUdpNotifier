package com.example.receiverapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale

class VoiceReplyActivity : Activity() {

    private var actionId: String? = null
    private var senderIp: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        actionId = intent.getStringExtra("actionId")
        senderIp = intent.getStringExtra("senderIp")

        if (actionId != null && senderIp != null) {
            startVoiceRecognition()
        } else {
            finish()
        }
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "th-TH")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "พูดข้อความที่ต้องการตอบกลับ...")
        }
        try {
            startActivityForResult(intent, 100)
        } catch (e: Exception) {
            Toast.makeText(this, "ไม่รองรับการสั่งงานด้วยเสียงบนเครื่องนี้", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            val result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = result?.firstOrNull()
            if (spokenText != null) {
                sendActionCommand(senderIp!!, actionId!!, spokenText)
            }
        }
        finish()
    }

    private fun sendActionCommand(ip: String, actionId: String, text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = DatagramSocket()
                val json = JSONObject().apply {
                    put("actionId", actionId)
                    put("text", text)
                }.toString()
                val encrypted = SecureUdp.encode(this@VoiceReplyActivity, json) ?: return@launch
                val payload = encrypted.toByteArray(Charsets.UTF_8)
                val address = InetAddress.getByName(ip)
                val packet = DatagramPacket(payload, payload.size, address, 8889)
                socket.send(packet)
                socket.close()
                CoroutineScope(Dispatchers.Main).launch {
                    Toast.makeText(this@VoiceReplyActivity, "ส่งข้อความ: $text", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                CoroutineScope(Dispatchers.Main).launch {
                    Toast.makeText(this@VoiceReplyActivity, "ล้มเหลวในการส่งข้อมูล", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
