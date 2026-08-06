package com.example.receiverapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.media.session.MediaSession
import android.media.session.PlaybackState
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
import android.widget.HorizontalScrollView
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
import android.media.AudioManager
import android.media.RingtoneManager
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

class UdpReceiverService : Service() {

    companion object {
        const val ACTION_DIAGNOSTIC_TEST = "com.example.receiverapp.DIAGNOSTIC_TEST"
        val autoReplyTimestamps = mutableMapOf<String, Long>()
    }

    private val TAG = "UdpReceiver"
    private val CHANNEL_ID = "UdpReceiverChannel"
    private val PORT = 8888

    private var listenJob: Job? = null
    private var socket: DatagramSocket? = null

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var autoDismissJob: Job? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingConnectionAnnouncement = false
    private var lastHeartbeatAt = 0L
    private var serviceStartTime = 0L
    
    private var mediaSession: MediaSession? = null
    private var screenOffReceiver: BroadcastReceiver? = null
    private var locationManager: LocationManager? = null
    private var isSpeedWarningActive = false
    private var currentSpeedKmh = 0f
    private val seenMessageIds = ConcurrentHashMap<String, Long>()
    
    private var lastWeatherCheckLocation: Location? = null
    private var lastWeatherCheckTime = 0L
    private var wasBadWeather = false
    private var isFetchingWeather = false

    override fun onCreate() {
        super.onCreate()
        // Android requires a service started with startForegroundService() to
        // promote itself immediately. Car head units can initialize TTS/GPS
        // slowly, so doing that work first may cause the OS to kill the app.
        startForegroundNotification()

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        if (PairedDevices.getDevices(this).isNotEmpty()) {
            pendingConnectionAnnouncement = true
        }
        
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        mediaSession = MediaSession(this, "UdpReceiverSession")
        mediaSession?.setCallback(object : MediaSession.Callback() {
            override fun onSkipToNext() {
                super.onSkipToNext()
                CoroutineScope(Dispatchers.Main).launch { removeFloatingWindow() }
            }
            override fun onSkipToPrevious() {
                super.onSkipToPrevious()
                CoroutineScope(Dispatchers.Main).launch { removeFloatingWindow() }
            }
        })
        val state = PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession?.setPlaybackState(state)

        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF || intent?.action == Intent.ACTION_SHUTDOWN) {
                    val now = System.currentTimeMillis()
                    if (lastHeartbeatAt > 0 && now - lastHeartbeatAt < 30000) {
                        val isTtsEnabled = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getBoolean("PREF_TTS", true)
                        if (isTtsEnabled) {
                            tts?.speak("ระบบกำลังจะปิดการทำงาน อย่าลืมโทรศัพท์มือถือของคุณ$pNa", TextToSpeech.QUEUE_FLUSH, null, "SHUTDOWN_REMINDER")
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        registerReceiver(screenOffReceiver, filter)
        
        try {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale("th", "TH")
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val audioAttrs = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    tts?.setAudioAttributes(audioAttrs)
                }

                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (prefs.getBoolean("PREF_AUDIO_DUCKING", true)) {
                            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        }
                    }
                    override fun onDone(utteranceId: String?) {
                        if (prefs.getBoolean("PREF_AUDIO_DUCKING", true)) {
                            audioManager.abandonAudioFocus(null)
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        if (prefs.getBoolean("PREF_AUDIO_DUCKING", true)) {
                            audioManager.abandonAudioFocus(null)
                        }
                    }
                })

                announceConnectionIfReady()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS is unavailable on this device", e)
            tts = null
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
        
        // Speed and Weather monitoring
        try {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val isSpeedWarningEnabled = prefs.getBoolean("PREF_SPEED_WARNING", true)
            
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 10f, object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        val speedKmh = location.speed * 3.6f
                        currentSpeedKmh = speedKmh
                        if (isSpeedWarningEnabled && speedKmh > 120 && !isSpeedWarningActive) {
                            showSpeedWarning()
                        }
                        
                        // Real-time weather check
                        val timeSinceLastCheck = System.currentTimeMillis() - lastWeatherCheckTime
                        val dist = if (lastWeatherCheckLocation != null) location.distanceTo(lastWeatherCheckLocation!!) else Float.MAX_VALUE
                        
                        if (dist > 10000f || timeSinceLastCheck > 600000L) { // 10km or 10 mins
                            checkWeather(location, false)
                        }
                        
                        val geoLatStr = prefs.getString("GEO_REMINDER_LAT", "")
                        val geoLonStr = prefs.getString("GEO_REMINDER_LON", "")
                        val geoMsg = prefs.getString("GEO_REMINDER_MSG", "")
                        val triggered = prefs.getBoolean("GEO_REMINDER_TRIGGERED", true)
                        
                        val geoLat = geoLatStr?.toDoubleOrNull()
                        val geoLon = geoLonStr?.toDoubleOrNull()
                        
                        if (geoLat != null && geoLon != null && geoMsg!!.isNotEmpty() && !triggered) {
                            val target = Location("").apply {
                                latitude = geoLat
                                longitude = geoLon
                            }
                            if (location.distanceTo(target) < 500) {
                                prefs.edit().putBoolean("GEO_REMINDER_TRIGGERED", true).apply()
                                val isTtsEnabled = prefs.getBoolean("PREF_TTS", true)
                                if (isTtsEnabled) {
                                    tts?.speak("แจ้งเตือนสถานที่: $geoMsg", TextToSpeech.QUEUE_FLUSH, null, "GEO")
                                }
                            }
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
        
        startUdpListener()
    }

    private val p: String
        get() = if (getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getBoolean("PREF_TTS_MALE", false)) "ครับ" else "ค่ะ"
    private val pNa: String
        get() = if (getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getBoolean("PREF_TTS_MALE", false)) "นะครับ" else "นะคะ"

    private fun checkWeather(location: Location?, isStartup: Boolean) {
        if (isFetchingWeather) return
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val isTtsEnabled = prefs.getBoolean("PREF_TTS", true)
        val isGreetingEnabled = prefs.getBoolean("PREF_GREETING", true)
        val isWeatherGreetingEnabled = prefs.getBoolean("PREF_WEATHER_GREETING", true)
        
        if (!isWeatherGreetingEnabled && !isStartup) return
        
        isFetchingWeather = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                var lat = 13.75
                var lon = 100.5167
                var currentLocation = location
                if (currentLocation == null && ContextCompat.checkSelfPermission(this@UdpReceiverService, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    currentLocation = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                }
                
                if (currentLocation != null) {
                    lat = currentLocation.latitude
                    lon = currentLocation.longitude
                    lastWeatherCheckLocation = currentLocation
                }
                lastWeatherCheckTime = System.currentTimeMillis()

                val requestUrl = java.net.URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code")
                val conn = requestUrl.openConnection() as java.net.HttpURLConnection
                val response = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val currentObj = json.getJSONObject("current")
                val temp = currentObj.getDouble("temperature_2m").toInt()
                val weatherCode = currentObj.optInt("weather_code", 0)
                
                var weatherDesc = ""
                var isBadWeather = false
                if (weatherCode in 51..67 || weatherCode in 80..82) {
                    weatherDesc = "มีแนวโน้มฝนตก"
                    isBadWeather = true
                } else if (weatherCode in 95..99) {
                    weatherDesc = "มีพายุฝนฟ้าคะนอง"
                    isBadWeather = true
                } else if (weatherCode == 45 || weatherCode == 48) {
                    weatherDesc = "มีหมอกลงหนา"
                    isBadWeather = true
                }
                
                if (isStartup) {
                    if (isTtsEnabled && isGreetingEnabled) {
                        val msg = if (isBadWeather) {
                            "ระบบเชื่อมต่อพร้อมทำงาน อุณหภูมิวันนี้ $temp องศา $weatherDesc ขับขี่ระมัดระวังด้วย$pNa"
                        } else {
                            "ระบบเชื่อมต่อพร้อมทำงาน อุณหภูมิวันนี้ $temp องศา ขอให้เดินทางโดยสวัสดิภาพ$p"
                        }
                        
                        if (isBadWeather) {
                            try {
                                val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                                val r = RingtoneManager.getRingtone(applicationContext, notification)
                                r.play()
                                delay(1500)
                            } catch (e: Exception) {}
                        }
                        tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "GREETING")
                    }
                } else {
                    if (isBadWeather && !wasBadWeather && isTtsEnabled) {
                        val msg = "แจ้งเตือนสภาพอากาศข้างหน้า: $weatherDesc อุณหภูมิ $temp องศา ขับขี่ระมัดระวังด้วย$pNa"
                        try {
                            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                            val r = RingtoneManager.getRingtone(applicationContext, notification)
                            r.play()
                            delay(1500)
                        } catch (e: Exception) {}
                        tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "WEATHER_ALERT")
                    }
                }
                wasBadWeather = isBadWeather
                
            } catch (e: Exception) {
                if (isStartup && isTtsEnabled && isGreetingEnabled) {
                    tts?.speak("ระบบเชื่อมต่อพร้อมทำงาน ขอให้เดินทางโดยสวัสดิภาพ$p", TextToSpeech.QUEUE_FLUSH, null, "GREETING")
                }
            } finally {
                isFetchingWeather = false
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DIAGNOSTIC_TEST) {
            AppLogger.log("Running local popup diagnostic")
            CoroutineScope(Dispatchers.Main).launch {
                showFloatingWindow(
                    "System Test",
                    "Popup, Overlay และ Receiver Service ทำงานปกติ",
                    null, null, "message", null, null, "127.0.0.1"
                )
                if (getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getBoolean("PREF_TTS", true)) {
                    tts?.speak("ทดสอบระบบ Receiver ทำงานปกติ", TextToSpeech.QUEUE_FLUSH, null, "DIAGNOSTIC_TEST")
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "ADH Notifier Client", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ADH Notifier Client")
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
                        val envelope = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val jsonString = SecureUdp.decode(this@UdpReceiverService, envelope)
                        if (jsonString == null) {
                            Log.w(TAG, "Rejected unauthenticated, stale, or replayed UDP packet")
                            continue
                        }
                        val decoded = JSONObject(jsonString)
                        val messageId = decoded.optString("_messageId")
                        if (messageId.isNotEmpty()) {
                            sendAck(packet.address.hostAddress ?: "", messageId)
                            val previous = seenMessageIds.putIfAbsent(messageId, System.currentTimeMillis())
                            if (previous != null) continue
                            val expiry = System.currentTimeMillis() - 10 * 60 * 1000L
                            seenMessageIds.entries.removeIf { it.value < expiry }
                        }
                        AppLogger.log("Received authenticated data")
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
            val wasPaired = PairedDevices.getDevices(this).containsKey(senderIp)
            if (!wasPaired) {
                pendingConnectionAnnouncement = true
                announceConnectionIfReady()
            }
            sendBroadcast(android.content.Intent("com.example.receiverapp.PAIRING_SUCCESS").setPackage(packageName))

            PairedDevices.addOrUpdateDevice(this, senderIp)

            val jsonObject = JSONObject(jsonString)
            val type = jsonObject.optString("type", "unknown")
            if (type == "heartbeat") {
                lastHeartbeatAt = System.currentTimeMillis()
                return
            }

            val name = jsonObject.optString("name", "Unknown Caller")
            val text = jsonObject.optString("text", "")
            val isGroup = jsonObject.optBoolean("isGroup", false)
            
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
                    val isMapLink = textContent.contains("maps.google.com") || textContent.contains("goo.gl/maps") || textContent.contains("maps.app.goo.gl") || textContent.contains("google.com/maps") || textContent.contains("google.co.th/maps")
                    
                    if (isMapLink) {
                        val match = urlRegex.find(textContent)
                        if (match != null) {
                            CoroutineScope(Dispatchers.Main).launch {
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(match.value))
                                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    intent.setPackage("com.google.android.apps.maps")
                                    try {
                                        startActivity(intent)
                                    } catch (e: android.content.ActivityNotFoundException) {
                                        intent.setPackage(null)
                                        startActivity(intent)
                                    }
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
                        val isTtsEnabled = prefs.getBoolean("PREF_TTS", true)
                        if (isTtsEnabled) {
                            tts?.speak("แจ้งเตือน แบตเตอรี่มือถือเหลือ $level เปอร์เซ็นต์ โปรดเสียบชาร์จด้วย$p", TextToSpeech.QUEUE_ADD, null, "BATTERY")
                        }
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

            if (type == "remove") {
                CoroutineScope(Dispatchers.Main).launch {
                    removeFloatingWindow()
                }
                return
            }

            val isTtsEnabled = prefs.getBoolean("PREF_TTS", true)
            val isPrivacyMode = prefs.getBoolean("PREF_PRIVACY_MODE", false)
            if (isTtsEnabled && type == "message" && text.isNotEmpty()) {
                if (isPrivacyMode && !isVip) {
                    tts?.speak("มีข้อความใหม่จาก $name", TextToSpeech.QUEUE_FLUSH, null, null)
                } else {
                    tts?.speak("ข้อความจาก $name, $text", TextToSpeech.QUEUE_FLUSH, null, null)
                }
            }

            CoroutineScope(Dispatchers.Main).launch {
                showFloatingWindow(name, text, base64Image, appIconBase64, type, actionsArray, replyActionId, senderIp, isGroup)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON", e)
        }
    }

    private fun showFloatingWindow(name: String, text: String, base64Image: String?, appIconBase64: String?, type: String, actionsArray: org.json.JSONArray?, replyActionId: String?, senderIp: String, isGroup: Boolean = false) {
        try {
            // Media player uses its own album-art card layout
            if (type == "media") {
                showMediaFloatingWindow(name, text, base64Image, appIconBase64, senderIp)
                return
            }

            val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val themeContext = android.view.ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar)
            
            val viewToUse = run {
                removeFloatingWindow()
                val inflater = LayoutInflater.from(themeContext)
                inflater.inflate(R.layout.floating_notification, null).also { floatingView = it }
            }

            val nameText = viewToUse.findViewById<TextView>(R.id.nameText)
            val messageText = viewToUse.findViewById<TextView>(R.id.messageText)
            val profileImage = viewToUse.findViewById<ImageView>(R.id.profileImage)
            val appIconBadge = viewToUse.findViewById<ImageView>(R.id.appIconBadge)
            val closeButton = viewToUse.findViewById<ImageButton>(R.id.closeButton)
            val actionsContainer = viewToUse.findViewById<LinearLayout>(R.id.actionsContainer)
            val actionsScrollView = viewToUse.findViewById<HorizontalScrollView>(R.id.actionsScrollView)
            val mediaInfoText = viewToUse.findViewById<TextView>(R.id.mediaInfoText)
            
            actionsContainer?.removeAllViews()

            // For media: show single-line marquee; for others: show stacked name/message
            if (type == "media") {
                nameText?.visibility = View.GONE
                messageText?.visibility = View.GONE
                mediaInfoText?.visibility = View.VISIBLE
                // Format: "Song title  ·  Artist"
                val mediaLine = if (text.isNotEmpty()) "$name  ·  $text" else name
                mediaInfoText?.text = mediaLine
                mediaInfoText?.isSelected = true // enables marquee
            } else {
                nameText?.visibility = View.VISIBLE
                messageText?.visibility = View.VISIBLE
                mediaInfoText?.visibility = View.GONE

                nameText?.text = if (type == "call") "📞 Incoming Call: $name" else name
            }
            
            val isVip = listOf("ด่วน", "ฉุกเฉิน", "สำคัญ", "vip").any { text.lowercase().contains(it) || name.lowercase().contains(it) }
            if (isVip && type != "media") {
                nameText?.text = "🚨 [VIP] ${nameText?.text}"
                nameText?.setTextColor(android.graphics.Color.parseColor("#FF5252"))
            }
            
            val isPrivacyMode = prefs.getBoolean("PREF_PRIVACY_MODE", false)
            if (type != "media") {
                if (isPrivacyMode && !isVip && type == "message") {
                    messageText?.text = "แตะเพื่ออ่านข้อความ"
                } else {
                    messageText?.text = text.ifEmpty { "Incoming $type" }
                }
            }

            val cardView = viewToUse as? androidx.cardview.widget.CardView
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
                    
                    if (base64Image.isNullOrEmpty()) {
                        profileImage?.setImageBitmap(bitmap)
                        appIconBadge?.visibility = View.GONE
                    } else {
                        appIconBadge?.setImageBitmap(bitmap)
                        appIconBadge?.visibility = View.VISIBLE
                    }
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
                                    sendActionCommand(senderIp, replyActionId, "กำลังขับรถอยู่ เดี๋ยวติดต่อกลับนะคะ 🚗")
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
            if (replyActionId != null) {
                val isAutoReplyEnabled = prefs.getBoolean("PREF_AUTO_REPLY", true)
                val now = System.currentTimeMillis()
                val lastReplyTime = autoReplyTimestamps[name] ?: 0L
                // Rate limit: Auto-reply at most once every 5 minutes per sender
                if (isAutoReplyEnabled && currentSpeedKmh > 10f && type == "message" && (now - lastReplyTime > 5 * 60 * 1000) && !isGroup) {
                    autoReplyTimestamps[name] = now
                    
                    CoroutineScope(Dispatchers.IO).launch {
                        val apiKey = BuildConfig.ANTHROPIC_API_KEY
                        var replyMessage: String? = null
                        
                        if (apiKey.isNotEmpty()) {
                            try {
                                val url = java.net.URL("https://api.anthropic.com/v1/messages")
                                val conn = url.openConnection() as java.net.HttpURLConnection
                                conn.requestMethod = "POST"
                                conn.setRequestProperty("x-api-key", apiKey)
                                conn.setRequestProperty("anthropic-version", "2023-06-01")
                                conn.setRequestProperty("content-type", "application/json")
                                conn.doOutput = true
                                
                                val escapedText = org.json.JSONObject.quote(text)
                                val jsonBody = """
                                    {
                                      "model": "claude-3-haiku-20240307",
                                      "max_tokens": 100,
                                      "system": "คุณคือระบบตอบแชทอัตโนมัติของคนที่กำลังขับรถอยู่ ให้ตอบกลับข้อความสั้นๆ สุภาพ เป็นภาษาไทย ว่ากำลังขับรถอยู่และตอบตามบริบท (ถ้าข้อความสั้นหรือไม่มีบริบท ให้ตอบแค่ว่ากำลังขับรถอยู่เดี๋ยวติดต่อกลับ)",
                                      "messages": [
                                        {"role": "user", "content": $escapedText}
                                      ]
                                    }
                                """.trimIndent()
                                
                                conn.outputStream.use { os ->
                                    val input = jsonBody.toByteArray(Charsets.UTF_8)
                                    os.write(input, 0, input.size)
                                }
                                
                                if (conn.responseCode == 200) {
                                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                                    val jsonObject = org.json.JSONObject(response)
                                    val contentArray = jsonObject.optJSONArray("content")
                                    if (contentArray != null && contentArray.length() > 0) {
                                        val generatedText = contentArray.getJSONObject(0).optString("text")
                                        if (generatedText.isNotEmpty()) {
                                            replyMessage = generatedText
                                        }
                                    }
                                } else {
                                    val errorResponse = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                                    Log.e(TAG, "Anthropic API failed with code: ${conn.responseCode}, error: $errorResponse")
                                }
                                conn.disconnect()
                            } catch (e: Exception) {
                                Log.e(TAG, "Anthropic auto-reply failed", e)
                            }
                        }
                        
                        if (replyMessage == null) {
                            val lowerText = text.lowercase()
                            replyMessage = when {
                                listOf("ถึงไหน", "ใกล้ถึง", "อยู่ไหน", "กี่โมง", "รอ").any { lowerText.contains(it) } -> 
                                    "กำลังขับรถอยู่ครับ ใกล้ถึงแล้ว 📍"
                                listOf("โทร", "โทรหา", "โทรกลับ", "ว่างไหม", "คุย").any { lowerText.contains(it) } -> 
                                    "กำลังขับรถอยู่ครับ เดี๋ยวจอดแล้วโทรกลับนะ 📞"
                                listOf("ด่วน", "สำคัญ", "เป็นไร", "เกิดไรขึ้น").any { lowerText.contains(it) } -> 
                                    "กำลังขับรถอยู่ครับ ถ้ามีเรื่องด่วนโทรมาได้เลยครับ 🚨"
                                else -> 
                                    "กำลังขับรถอยู่ เดี๋ยวติดต่อกลับครับ 🚗"
                            }
                        }
                        
                        CoroutineScope(Dispatchers.Main).launch {
                            sendActionCommand(senderIp, replyActionId, replyMessage)
                            AppLogger.log("Auto-replied (Claude Smart) to $name")
                        }
                    }
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

                val quickReplies = listOf("ขับรถอยู่ 🚗", "เดี๋ยวโทรกลับ 📞", "ใกล้ถึงแล้ว 📍")
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

            val isMapLink = text.contains("maps.google.com") || text.contains("goo.gl/maps") || text.contains("maps.app.goo.gl") || text.contains("google.com/maps") || text.contains("google.co.th/maps")
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
                                intent.setPackage("com.google.android.apps.maps")
                                try {
                                    startActivity(intent)
                                } catch (e: android.content.ActivityNotFoundException) {
                                    intent.setPackage(null)
                                    startActivity(intent)
                                }
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
                  actionsContainer?.gravity = android.view.Gravity.CENTER

                  val dp = resources.displayMetrics.density
                  val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                      android.content.res.Configuration.UI_MODE_NIGHT_YES
                  // Secondary icons: dark on light bg, white on dark bg
                  val iconTint = if (isDark) android.graphics.Color.parseColor("#E0E0E0")
                                 else android.graphics.Color.parseColor("#424242")

                  // ── Helper: borderless ripple button (Prev / Next) ──────────────────
                  fun makeIconBtn(iconRes: Int, action: String, sizeDp: Int): android.widget.ImageButton {
                      return android.widget.ImageButton(themeContext).apply {
                          setImageResource(iconRes)
                          setColorFilter(iconTint, android.graphics.PorterDuff.Mode.SRC_IN)
                          background = with(android.util.TypedValue()) {
                              themeContext.theme.resolveAttribute(
                                  android.R.attr.selectableItemBackgroundBorderless, this, true)
                              themeContext.getDrawable(resourceId)
                          }
                          elevation = 0f
                          scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                          val p = (10 * dp).toInt()
                          setPadding(p, p, p, p)
                          setOnClickListener { sendActionCommand(senderIp, action) }
                      }
                  }

                  // ── Helper: filled pill play/pause button (Material You style) ───────
                  fun makePlayBtn(iconRes: Int): android.widget.ImageButton {
                      val accentColor = android.graphics.Color.parseColor("#FF9800")
                      val pillBg = android.graphics.drawable.GradientDrawable().apply {
                          shape = android.graphics.drawable.GradientDrawable.OVAL
                          setColor(accentColor)
                      }
                      // Ripple over the pill
                      val rippleColor = android.content.res.ColorStateList.valueOf(
                          android.graphics.Color.parseColor("#33FFFFFF"))
                      val rippleBg = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                          android.graphics.drawable.RippleDrawable(rippleColor, pillBg, pillBg)
                      } else pillBg

                      return android.widget.ImageButton(themeContext).apply {
                          setImageResource(iconRes)
                          setColorFilter(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
                          background = rippleBg
                          elevation = 2f
                          scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                          val p = (14 * dp).toInt()
                          setPadding(p, p, p, p)
                          setOnClickListener { sendActionCommand(senderIp, "media_play_pause") }
                      }
                  }

                  // ── Build controls row: [⏮  44dp] [▶/⏸  64dp] [⏭  44dp] ─────────
                  val prevBtn = makeIconBtn(R.drawable.ic_media_previous, "media_prev", 44)
                  val playBtn = makePlayBtn(R.drawable.ic_media_play_pause)
                  val nextBtn = makeIconBtn(R.drawable.ic_media_next,     "media_next", 44)

                  fun addBtn(btn: android.widget.ImageButton, sizeDp: Int) {
                      val px = (sizeDp * dp).toInt()
                      val lp = LinearLayout.LayoutParams(px, px).apply {
                          marginStart = (10 * dp).toInt()
                          marginEnd   = (10 * dp).toInt()
                          gravity = android.view.Gravity.CENTER_VERTICAL
                      }
                      actionsContainer?.addView(btn, lp)
                  }

                  addBtn(prevBtn, 44)
                  addBtn(playBtn, 64)
                  addBtn(nextBtn, 44)
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
    
            val pos = prefs.getString("PREF_POPUP_GRAVITY", "Top")
            params.gravity = when (pos) {
                "Center" -> Gravity.CENTER
                "Bottom" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            params.y = if (pos == "Center") 0 else 40
    
            try {
                windowManager?.addView(viewToUse, params)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add floating view", e)
            }
            
            if (type != "call" && type != "media") {
                mediaSession?.isActive = true
                autoDismissJob = CoroutineScope(Dispatchers.Main).launch {
                    delay(8000)
                    removeFloatingWindow()
                }
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
                val encrypted = SecureUdp.encode(this@UdpReceiverService, json) ?: return@launch
                val payload = encrypted.toByteArray(Charsets.UTF_8)
                val address = java.net.InetAddress.getByName(ip)
                val packet = DatagramPacket(payload, payload.size, address, 8889)
                socket.send(packet)
                socket.close()
                AppLogger.log("Sent authenticated action")
            } catch (e: Exception) {
                AppLogger.log("Failed to send action: ${e.message}")
            }
        }
    }

    private fun announceConnectionIfReady() {
        if (!pendingConnectionAnnouncement || !ttsReady) return
        pendingConnectionAnnouncement = false
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("PREF_TTS", true) || !prefs.getBoolean("PREF_GREETING", true)) return

        AppLogger.log("Sender connected; announcing connection")
        if (prefs.getBoolean("PREF_WEATHER_GREETING", true)) {
            checkWeather(null, true)
        } else {
            tts?.speak(
                "ระบบเชื่อมต่อพร้อมทำงาน ขอให้เดินทางโดยสวัสดิภาพ$p",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "GREETING"
            )
        }
    }

    private fun sendAck(ip: String, messageId: String) {
        if (ip.isBlank()) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject().apply {
                    put("type", "ack")
                    put("messageId", messageId)
                }.toString()
                val encrypted = SecureUdp.encode(this@UdpReceiverService, json) ?: return@launch
                val payload = encrypted.toByteArray(Charsets.UTF_8)
                DatagramSocket().use { socket ->
                    socket.send(DatagramPacket(payload, payload.size, java.net.InetAddress.getByName(ip), 8889))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to send acknowledgement", e)
            }
        }
    }

    // ── Dedicated compact media player card (floating_media.xml) ─────────────
    private fun showMediaFloatingWindow(name: String, text: String, base64Image: String?, appIconBase64: String?, senderIp: String) {
        try {
            val isUpdating = floatingView != null && floatingView?.findViewById<android.view.View>(R.id.mediaSongTitle) != null
            val themeContext = android.view.ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar)

            val view = if (isUpdating) {
                autoDismissJob?.cancel()
                floatingView!!
            } else {
                removeFloatingWindow()
                LayoutInflater.from(themeContext).inflate(R.layout.floating_media, null).also { floatingView = it }
            }

            val albumArt     = view.findViewById<ImageView>(R.id.mediaAlbumArt)
            val appIconView  = view.findViewById<ImageView>(R.id.mediaAppIcon)
            val appNameView  = view.findViewById<TextView>(R.id.mediaAppName)
            val songTitle    = view.findViewById<TextView>(R.id.mediaSongTitle)
            val artistName   = view.findViewById<TextView>(R.id.mediaArtistName)
            val prevBtn      = view.findViewById<ImageButton>(R.id.mediaPrevBtn)
            val playBtn      = view.findViewById<ImageButton>(R.id.mediaPlayBtn)
            val nextBtn      = view.findViewById<ImageButton>(R.id.mediaNextBtn)

            // Song info
            val titleStr = name.ifEmpty { "Now Playing" }
            val artistStr = text
            if (artistStr.isNotEmpty()) {
                songTitle?.text = "$titleStr - $artistStr"
            } else {
                songTitle?.text = titleStr
            }
            songTitle?.isSelected = true // start marquee
            artistName?.text = ""

            // Album art (contact/album photo)
            if (!base64Image.isNullOrEmpty()) {
                try {
                    val bytes = Base64.decode(base64Image, Base64.DEFAULT)
                    albumArt?.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    albumArt?.visibility = View.VISIBLE
                } catch (_: Exception) {}
            }

            // App icon badge
            if (!appIconBase64.isNullOrEmpty()) {
                try {
                    val bytes = Base64.decode(appIconBase64, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    appIconView?.setImageBitmap(bmp)
                    appIconView?.visibility = View.VISIBLE
                } catch (_: Exception) {}
            }

            // App name (show package label if available, else hide)
            appNameView?.text = appNameView?.text ?: ""

            // Play button — filled orange circle with ripple
            val accentColor = android.graphics.Color.parseColor("#FF9800")
            val pillBg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(accentColor)
            }
            val ripple = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#33FFFFFF")),
                    pillBg, pillBg)
            } else pillBg
            playBtn?.background = ripple
            playBtn?.setColorFilter(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)

            // Wire controls
            prevBtn?.setOnClickListener { sendActionCommand(senderIp, "media_prev") }
            playBtn?.setOnClickListener { sendActionCommand(senderIp, "media_play_pause") }
            nextBtn?.setOnClickListener { sendActionCommand(senderIp, "media_next") }

            // Swipe left / right / up to dismiss (no close button)
            var swipeStartX = 0f
            var swipeStartY = 0f
            val swipeThresholdPx = (80 * resources.displayMetrics.density) // 80dp
            view.setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        swipeStartX = event.rawX
                        swipeStartY = event.rawY
                        false
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        val dx = event.rawX - swipeStartX
                        val dy = event.rawY - swipeStartY
                        val absDx = kotlin.math.abs(dx)
                        val absDy = kotlin.math.abs(dy)
                        if (absDx > swipeThresholdPx || (absDy > swipeThresholdPx && dy < 0)) {
                            // Swipe detected — animate out then remove
                            val targetX = if (dx > 0) view.width.toFloat() else -view.width.toFloat()
                            view.animate()
                                .translationX(targetX)
                                .alpha(0f)
                                .setDuration(220)
                                .withEndAction { CoroutineScope(Dispatchers.Main).launch { removeFloatingWindow() } }
                                .start()
                            true
                        } else false
                    }
                    else -> false
                }
            }

            if (!isUpdating) {
                val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutFlag,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                    y = 40
                }
                windowManager?.addView(view, params)
            }

            // Media stays visible until explicitly removed
            mediaSession?.isActive = true

        } catch (e: Exception) {
            Log.e(TAG, "showMediaFloatingWindow failed", e)
        }
    }

    private fun removeFloatingWindow() {
        try {
            autoDismissJob?.cancel()
            mediaSession?.isActive = false
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
        screenOffReceiver?.let { unregisterReceiver(it) }
        mediaSession?.release()
        tts?.stop()
        tts?.shutdown()
        CoroutineScope(Dispatchers.Main).launch { removeFloatingWindow() }
    }
}
