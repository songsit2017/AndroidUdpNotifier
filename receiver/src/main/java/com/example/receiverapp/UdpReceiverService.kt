package com.example.receiverapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import android.speech.tts.TextToSpeech
import java.util.Locale

class UdpReceiverService : Service() {

    private val TAG = "UdpReceiver"
    private val CHANNEL_ID = "UdpReceiverChannel"
    private val PORT = 8888

    private var listenJob: Job? = null
    private var socket: DatagramSocket? = null

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var autoDismissJob: Job? = null
    private var tts: TextToSpeech? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("th", "TH")
            }
        }
        
        startForegroundNotification()
        startUdpListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "UDP Receiver Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Receiver Active")
            .setContentText("Listening for incoming calls and messages...")
            .setSmallIcon(android.R.drawable.ic_dialog_info) 
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun startUdpListener() {
        listenJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                socket = DatagramSocket(PORT)
                val buffer = ByteArray(65535)

                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket?.receive(packet)
                        val jsonString = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        Log.d(TAG, "Received: $jsonString")
                        AppLogger.log("Received data: ${jsonString.take(100)}...")
                        val senderIp = packet.address.hostAddress ?: ""
                        parseAndDisplayData(jsonString, senderIp)
                    } catch (e: Exception) {
                        if (isActive) Log.e(TAG, "Error receiving packet", e)
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) Log.e(TAG, "UDP Listener Error", e)
            }
        }
    }

    private fun parseAndDisplayData(jsonString: String, senderIp: String) {
        try {
            val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val isDndEnabled = prefs.getBoolean("PREF_DND", false)
            if (isDndEnabled) {
                AppLogger.log("🔕 DND Mode is ON. Ignored incoming notification.")
                return
            }

            val jsonObject = JSONObject(jsonString)
            val type = jsonObject.optString("type", "unknown")
            
            if (type == "battery") {
                val isBatteryEnabled = prefs.getBoolean("PREF_BATTERY", true)
                if (isBatteryEnabled) {
                    val level = jsonObject.optInt("level", 20)
                    CoroutineScope(Dispatchers.Main).launch {
                        showFloatingWindow("⚠️ Battery Alert", "แบตเตอรี่มือถือเหลือ $level% โปรดเสียบชาร์จ", null, null, type, null, senderIp)
                    }
                }
                return
            }

            if (type == "media") {
                val isMediaEnabled = prefs.getBoolean("PREF_MEDIA", true)
                if (!isMediaEnabled) return
            }

            val name = jsonObject.optString("name", "Unknown Caller")
            val text = jsonObject.optString("text", "")
            val base64Image = if (jsonObject.isNull("imageBase64")) null else jsonObject.getString("imageBase64")
            val appIconBase64 = if (jsonObject.isNull("appIconBase64")) null else jsonObject.getString("appIconBase64")
            val actionsArray = jsonObject.optJSONArray("actions")
            val replyActionId = if (jsonObject.isNull("replyActionId")) null else jsonObject.getString("replyActionId")

            val isTtsEnabled = prefs.getBoolean("PREF_TTS", true)
            if (isTtsEnabled && type == "message" && text.isNotEmpty()) {
                tts?.speak("ข้อความจาก $name, $text", TextToSpeech.QUEUE_FLUSH, null, null)
            }

            CoroutineScope(Dispatchers.Main).launch {
                showFloatingWindow(name, text, base64Image, appIconBase64, type, actionsArray, replyActionId, senderIp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON", e)
        }
    }

    private fun showFloatingWindow(name: String, text: String, base64Image: String?, appIconBase64: String?, type: String, actionsArray: org.json.JSONArray?, replyActionId: String?, senderIp: String) {
        try {
            removeFloatingWindow()
            val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

            // Wrap context with a theme so AppCompat components (and ?attr/...) can inflate properly
            val themeContext = android.view.ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_Light_DarkActionBar)
            val inflater = LayoutInflater.from(themeContext)
            
            floatingView = inflater.inflate(R.layout.floating_notification, null)

            val nameText = floatingView?.findViewById<TextView>(R.id.nameText)
            val messageText = floatingView?.findViewById<TextView>(R.id.messageText)
            val profileImage = floatingView?.findViewById<ImageView>(R.id.profileImage)
            val appIconBadge = floatingView?.findViewById<ImageView>(R.id.appIconBadge)
            val closeButton = floatingView?.findViewById<ImageButton>(R.id.closeButton)
            val actionsContainer = floatingView?.findViewById<LinearLayout>(R.id.actionsContainer)

            nameText?.text = if (type == "call") "📞 Incoming Call: $name" else name
            messageText?.text = text.ifEmpty { "Incoming $type" }

            if (!base64Image.isNullOrEmpty()) {
                try {
                    val imageBytes = Base64.decode(base64Image, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    profileImage?.setImageBitmap(bitmap)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode base64 image", e)
                }
            }

            if (!appIconBase64.isNullOrEmpty()) {
                try {
                    val imageBytes = Base64.decode(appIconBase64, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    appIconBadge?.setImageBitmap(bitmap)
                    appIconBadge?.visibility = View.VISIBLE
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode app icon", e)
                }
            }

            if (actionsArray != null && actionsArray.length() > 0) {
                actionsContainer?.visibility = View.VISIBLE
                for (i in 0 until actionsArray.length()) {
                    val actionJson = actionsArray.getJSONObject(i)
                    val actionId = actionJson.getString("id")
                    val actionTitle = actionJson.getString("title")

                    val btn = Button(themeContext).apply {
                        this.text = actionTitle
                        this.isAllCaps = false
                        this.setOnClickListener {
                            sendActionCommand(senderIp, actionId)
                            if (actionTitle.contains("Reject", true) || actionTitle.contains("Decline", true) || actionTitle.contains("วาง", true)) {
                                removeFloatingWindow()
                            }
                        }
                    }
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = 16 }
                    
                    actionsContainer?.addView(btn, lp)
                }
            }

            if (replyActionId != null && prefs.getBoolean("PREF_QUICK_REPLY", true)) {
                actionsContainer?.visibility = View.VISIBLE
                val quickReplies = listOf("โอเค", "รับทราบ", "กำลังขับรถ")
                for (replyText in quickReplies) {
                    val btn = Button(themeContext).apply {
                        text = replyText
                        isAllCaps = false
                        setOnClickListener {
                            sendActionCommand(senderIp, replyActionId, replyText)
                            removeFloatingWindow()
                        }
                    }
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = 16 }
                    actionsContainer?.addView(btn, lp)
                }
            }

            closeButton?.setOnClickListener { removeFloatingWindow() }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = 40

        try {
            windowManager?.addView(floatingView, params)
            if (type != "call") {
                autoDismissJob = CoroutineScope(Dispatchers.Main).launch {
                    delay(8000)
                    removeFloatingWindow()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating view", e)
        }
        
        } catch (e: Exception) {
            Log.e(TAG, "Fatal Error in showFloatingWindow: ", e)
            AppLogger.log("Crash avoided in showFloatingWindow: ${e.message}")
        }
    }
    
    private fun sendActionCommand(ip: String, actionId: String, text: String? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = DatagramSocket()
                val json = JSONObject().apply {
                    put("actionId", actionId)
                    if (text != null) put("text", text)
                }.toString()
                val payload = json.toByteArray(Charsets.UTF_8)
                val address = java.net.InetAddress.getByName(ip)
                val packet = DatagramPacket(payload, payload.size, address, 8889)
                socket.send(packet)
                socket.close()
                AppLogger.log("Sent action $actionId to $ip")
            } catch (e: Exception) {
                AppLogger.log("Failed to send action: ${e.message}")
            }
        }
    }

    private fun removeFloatingWindow() {
        try {
            autoDismissJob?.cancel()
            if (floatingView != null) {
                windowManager?.removeView(floatingView)
                floatingView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing view", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        listenJob?.cancel()
        socket?.close()
        tts?.stop()
        tts?.shutdown()
        CoroutineScope(Dispatchers.Main).launch { removeFloatingWindow() }
    }
}
