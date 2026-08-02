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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat

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
    private var serviceStartTime = 0L
    private var locationManager: LocationManager? = null
    private var isSpeedWarningActive = false

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("th", "TH")
            }
        }
        
        serviceStartTime = System.currentTimeMillis()
        
        // Fatigue monitoring
        CoroutineScope(Dispatchers.IO).launch {
            while(isActive) {
                delay(60 * 1000)
                if (System.currentTimeMillis() - serviceStartTime > 2 * 60 * 60 * 1000) {
                    val isFatigueEnabled = prefs.getBoolean("PREF_FATIGUE_ALERT", true)
                    if (isFatigueEnabled) {
                        CoroutineScope(Dispatchers.Main).launch {
                            showFatigueWarning()
                        }
                    }
                    serviceStartTime = System.currentTimeMillis()
                }
            }
        }
        
        // Speed monitoring
        try {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val isSpeedWarningEnabled = prefs.getBoolean("PREF_SPEED_WARNING", true)
            
            if (isSpeedWarningEnabled && ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 10f, object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        val speedKmh = location.speed * 3.6f
                        if (speedKmh > 120 && !isSpeedWarningActive) {
                            showSpeedWarning()
                        }
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                })
            }
        } catch(e: Exception) {
            e.printStackTrace()
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
            prefs.edit().putString("LAST_SENDER_IP", senderIp).apply()

            val jsonObject = JSONObject(jsonString)
            val type = jsonObject.optString("type", "unknown")

            val name = jsonObject.optString("name", "Unknown Caller")
            val text = jsonObject.optString("text", "")
            
            val textContentLower = text.lowercase()
            val titleContentLower = name.lowercase()
            val isVipModeEnabled = prefs.getBoolean("PREF_VIP_MODE", true)
            val isVip = isVipModeEnabled && listOf("ด่วน", "ฉุกเฉิน", "สำคัญ", "vip").any { textContentLower.contains(it) || titleContentLower.contains(it) }

            val isDndEnabled = prefs.getBoolean("PREF_DND", false)
            if (isDndEnabled && type != "clipboard" && type != "battery" && !isVip) {
                AppLogger.log("🔕 DND Mode is ON. Ignored incoming notification.")
                return
            }
            if (isVip && type == "message" && isDndEnabled) {
                AppLogger.log("🚨 VIP MESSAGE BYPASSED DND!")
            }
            
            if (type == "clipboard") {
                val textContent = jsonObject.optString("text", "")
                if (textContent.isNotEmpty()) {
                    val urlRegex = "(?i)\\b((?:https?://|www\\d{0,3}[.]|[a-z0-9.\\-]+[.][a-z]{2,4}/)(?:[^\\s()<>]+|\\(([^\\s()<>]+|(\\([^\\s()<>]+\\)))*\\))+(?:\\(([^\\s()<>]+|(\\([^\\s()<>]+\\)))*\\)|[^\\s`!()\\[\\]{};:'\".,<>?«»“”‘’]))".toRegex()
                    val isMapLink = textContent.contains("maps.google.com") || textContent.contains("goo.gl/maps") || textContent.contains("maps.app.goo.gl")
                    
                    if (isMapLink) {
                        val match = urlRegex.find(textContent)
                        if (match != null) {
                            CoroutineScope(Dispatchers.Main).launch {
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(match.value))
                                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    startActivity(intent)
                                    android.widget.Toast.makeText(this@UdpReceiverService, "📍 เปิดนำทางจากมือถือ", android.widget.Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to launch maps from clipboard", e)
                                }
                            }
                            return
                        }
                    }

                    CoroutineScope(Dispatchers.Main).launch {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Copied Text", textContent)
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(this@UdpReceiverService, "📋 วางข้อความลงคลิปบอร์ดรถแล้ว", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                return
            }
            
            if (type == "battery") {
                val isBatteryEnabled = prefs.getBoolean("PREF_BATTERY", true)
                if (isBatteryEnabled) {
                    val level = jsonObject.optInt("level", 20)
                    CoroutineScope(Dispatchers.Main).launch {
                        showFloatingWindow("⚠️ Battery Alert", "แบตเตอรี่มือถือเหลือ $level% โปรดเสียบชาร์จ", null, null, type, null, null, senderIp)
                    }
                }
                return
            }

            if (type == "media") {
                val isMediaEnabled = prefs.getBoolean("PREF_MEDIA", true)
                if (!isMediaEnabled) return
            }

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
            val actionsScrollView = floatingView?.findViewById<HorizontalScrollView>(R.id.actionsScrollView)

            nameText?.text = if (type == "call") "📞 Incoming Call: $name" else name
            
            val isVip = listOf("ด่วน", "ฉุกเฉิน", "สำคัญ", "vip").any { text.lowercase().contains(it) || name.lowercase().contains(it) }
            if (isVip) {
                nameText?.text = "🚨 [VIP] ${nameText?.text}"
                nameText?.setTextColor(android.graphics.Color.parseColor("#FF5252"))
            }
            
            messageText?.text = text.ifEmpty { "Incoming $type" }

            val cardView = floatingView as? androidx.cardview.widget.CardView
            val themePref = prefs.getString("PREF_THEME", "Classic") ?: "Classic"
            
            // Check global night mode explicitly
            val isNightMode = (android.content.res.Resources.getSystem().configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            
            when (themePref) {
                "Classic" -> {
                    if (isNightMode) {
                        cardView?.setCardBackgroundColor(android.graphics.Color.parseColor("#FF000000")) // AMOLED Black
                        nameText?.setTextColor(android.graphics.Color.parseColor("#FFFFFFFF"))
                        messageText?.setTextColor(android.graphics.Color.parseColor("#B3FFFFFF"))
                        closeButton?.setColorFilter(android.graphics.Color.parseColor("#B3FFFFFF"))
                    } else {
                        cardView?.setCardBackgroundColor(android.graphics.Color.parseColor("#F5FFFFFF")) // Minimal White
                        nameText?.setTextColor(android.graphics.Color.parseColor("#FF000000"))
                        messageText?.setTextColor(android.graphics.Color.parseColor("#99000000"))
                        closeButton?.setColorFilter(android.graphics.Color.parseColor("#99000000"))
                    }
                }
                "Honda Type-R" -> cardView?.setCardBackgroundColor(android.graphics.Color.parseColor("#DDCC0000"))
                "BMW M" -> cardView?.setCardBackgroundColor(android.graphics.Color.parseColor("#DD0033A0"))
                "Tesla" -> {
                    cardView?.setCardBackgroundColor(android.graphics.Color.parseColor("#F5FFFFFF"))
                    nameText?.setTextColor(if (isVip) android.graphics.Color.parseColor("#FF5252") else android.graphics.Color.BLACK)
                    messageText?.setTextColor(android.graphics.Color.DKGRAY)
                    closeButton?.setColorFilter(android.graphics.Color.BLACK)
                }
                else -> cardView?.setCardBackgroundColor(android.graphics.Color.parseColor("#E6222222"))
            }

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
                actionsScrollView?.visibility = View.VISIBLE
                for (i in 0 until actionsArray.length()) {
                    val actionJson = actionsArray.getJSONObject(i)
                    val actionId = actionJson.getString("id")
                    val actionTitle = actionJson.getString("title")

                    val btn = Button(themeContext).apply {
                        this.text = actionTitle
                        this.isAllCaps = false
                        this.setOnClickListener {
                            sendActionCommand(senderIp, actionId)
                            val isReject = actionTitle.contains("Reject", true) || actionTitle.contains("Decline", true) || actionTitle.contains("วาง", true) || actionTitle.contains("ปฏิเสธ", true)
                            if (isReject) {
                                if (replyActionId != null && prefs.getBoolean("PREF_QUICK_REPLY", true)) {
                                    sendActionCommand(senderIp, replyActionId, "กำลังขับรถอยู่ เดี๋ยวติดต่อกลับไปนะครับ 🚗")
                                }
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
                actionsScrollView?.visibility = View.VISIBLE
                
                val isLocationRequest = listOf("ถึงไหน", "ใกล้ถึง", "อยู่ไหน").any { text.contains(it) }
                val isShareEtaEnabled = prefs.getBoolean("PREF_SHARE_ETA", true)
                if (isLocationRequest && isShareEtaEnabled) {
                    val shareEtaBtn = Button(themeContext).apply {
                        this.text = "🚗 ส่งพิกัดบอก"
                        this.isAllCaps = false
                        this.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF9800"))
                        this.setTextColor(android.graphics.Color.WHITE)
                        this.setOnClickListener {
                            sendActionCommand(senderIp, "share_eta", replyActionId)
                            removeFloatingWindow()
                        }
                    }
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = 16 }
                    actionsContainer?.addView(shareEtaBtn, lp)
                }

                val voiceBtn = Button(themeContext).apply {
                    this.text = "🎙️ พูดเพื่อตอบ"
                    this.isAllCaps = false
                    this.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#4CAF50"))
                    this.setTextColor(android.graphics.Color.WHITE)
                    this.setOnClickListener {
                        val intent = android.content.Intent(this@UdpReceiverService, VoiceReplyActivity::class.java)
                        intent.putExtra("actionId", replyActionId)
                        intent.putExtra("senderIp", senderIp)
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        removeFloatingWindow()
                    }
                }
                val voiceLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 16 }
                actionsContainer?.addView(voiceBtn, voiceLp)

                val quickReplies = listOf("โอเค", "รับทราบ", "กำลังขับรถ")
                for (replyText in quickReplies) {
                    val btn = Button(themeContext).apply {
                        this.text = replyText
                        this.isAllCaps = false
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

            val isMapLink = text.contains("maps.google.com") || text.contains("goo.gl/maps") || text.contains("maps.app.goo.gl")
            if (isMapLink) {
                actionsContainer?.visibility = View.VISIBLE
                actionsScrollView?.visibility = View.VISIBLE
                val btn = Button(themeContext).apply {
                    this.text = "📍 นำทางไปที่นี่"
                    this.isAllCaps = false
                    this.setOnClickListener {
                        try {
                            val urlRegex = "(?i)\\b((?:https?://|www\\d{0,3}[.]|[a-z0-9.\\-]+[.][a-z]{2,4}/)(?:[^\\s()<>]+|\\(([^\\s()<>]+|(\\([^\\s()<>]+\\)))*\\))+(?:\\(([^\\s()<>]+|(\\([^\\s()<>]+\\)))*\\)|[^\\s`!()\\[\\]{};:'\".,<>?«»“”‘’]))".toRegex()
                            val match = urlRegex.find(text)
                            if (match != null) {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(match.value))
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(intent)
                                removeFloatingWindow()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse maps URL", e)
                        }
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 16 }
                actionsContainer?.addView(btn, lp)
            }

            if (type == "media") {
                actionsContainer?.visibility = View.VISIBLE
                actionsScrollView?.visibility = View.VISIBLE
                val mediaControls = listOf(
                    Triple("⏪", "media_prev", "#2196F3"),
                    Triple("⏯️", "media_play_pause", "#FF9800"),
                    Triple("⏩", "media_next", "#2196F3")
                )
                for ((icon, action, color) in mediaControls) {
                    val btn = Button(themeContext).apply {
                        this.text = icon
                        this.isAllCaps = false
                        backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(color))
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 20f
                        setOnClickListener {
                            sendActionCommand(senderIp, action)
                        }
                    }
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = 16 }
                    actionsContainer?.addView(btn, lp)
                }
            }

            if (type == "fatigue") {
                actionsContainer?.visibility = View.VISIBLE
                actionsScrollView?.visibility = View.VISIBLE
                val btn = Button(themeContext).apply {
                    this.text = "⛽ หาปั๊มน้ำมันใกล้ฉัน"
                    this.isAllCaps = false
                    setOnClickListener {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=ปั๊มน้ำมัน"))
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        removeFloatingWindow()
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 16 }
                actionsContainer?.addView(btn, lp)
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

    private fun showFatigueWarning() {
        showFloatingWindow("☕ เตือนพักสายตา", "คุณขับรถต่อเนื่องมา 2 ชั่วโมงแล้ว แวะพักยืดเส้นยืดสายหน่อยไหมครับ?", null, null, "fatigue", null, null, "")
    }

    private fun showSpeedWarning() {
        isSpeedWarningActive = true
        CoroutineScope(Dispatchers.Main).launch {
            val view = View(this@UdpReceiverService)
            view.setBackgroundColor(Color.parseColor("#66FF0000"))
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            try {
                windowManager?.addView(view, params)
                for(i in 1..6) {
                    view.visibility = if(i%2==0) View.VISIBLE else View.INVISIBLE
                    delay(300)
                }
                windowManager?.removeView(view)
            } catch(e: Exception) {}
            isSpeedWarningActive = false
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
