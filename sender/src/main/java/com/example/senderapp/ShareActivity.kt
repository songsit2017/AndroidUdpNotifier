package com.example.senderapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null) {
                sendClipboardToCar(sharedText)
            }
        }
        
        // Finish immediately so we don't show any UI
        finish()
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val broadcastList = mutableListOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
                        broadcastList.add(broadcast)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (broadcastList.isEmpty()) {
            broadcastList.add(InetAddress.getByName("255.255.255.255"))
        }
        return broadcastList
    }

    private fun sendClipboardToCar(text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true
                
                val plaintext = JSONObject().apply {
                    put("type", "clipboard")
                    put("text", text)
                }.toString()
                val encrypted = SecureUdp.encode(this@ShareActivity, plaintext)
                    ?: throw IllegalStateException("Pairing code is not configured")
                val payload = encrypted.toByteArray(Charsets.UTF_8)
                
                val port = 8888
                val broadcastAddresses = getBroadcastAddresses()
                for (address in broadcastAddresses) {
                    try {
                        val packet = DatagramPacket(payload, payload.size, address, port)
                        socket.send(packet)
                        AppLogger.log("Encrypted shared text sent")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                CoroutineScope(Dispatchers.Main).launch {
                    Toast.makeText(this@ShareActivity, "ส่งข้อความไปที่จอรถแล้ว 🚗", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                CoroutineScope(Dispatchers.Main).launch {
                    Toast.makeText(this@ShareActivity, "ล้มเหลวในการส่งข้อมูล", Toast.LENGTH_SHORT).show()
                }
            } finally {
                socket?.close()
            }
        }
    }
}
