package com.example.senderapp

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter

class NotificationSenderService : NotificationListenerService() {
    private var listenJob: kotlinx.coroutines.Job? = null
    private var actionListenJob: kotlinx.coroutines.Job? = null
    data class PendingAction(val intent: PendingIntent, val remoteInputKey: String?, val createdAt: Long = System.currentTimeMillis())
    private val pendingIntents = ConcurrentHashMap<String, PendingAction>()
    private val pendingMessages = ConcurrentHashMap<String, Boolean>()
    private var batteryReceiver: BroadcastReceiver? = null
    private var connectionReceiver: ConnectionReceiver? = null
    private var lastBatteryLevel = -1
    private val actionTtlMs = 5 * 60 * 1000L

    // Target Packages
    private fun getTargetPackages(): List<String> {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val packages = mutableListOf<String>()
        if (prefs.getBoolean("APP_LINE", true)) packages.add("jp.naver.line.android")
        if (prefs.getBoolean("APP_MESSENGER", true)) {
            packages.add("com.facebook.orca")
            packages.add("com.facebook.katana")
            packages.add("com.facebook.lite")
        }
        if (prefs.getBoolean("APP_TELEGRAM", true)) packages.add("org.telegram.messenger")
        if (prefs.getBoolean("APP_WHATSAPP", true)) packages.add("com.whatsapp")
        if (prefs.getBoolean("APP_GMAIL", true)) packages.add("com.google.android.gm")
        
        if (prefs.getBoolean("APP_SHOPPING", true)) {
            packages.add("com.shopee.th")
            packages.add("com.lazada.android")
            packages.add("com.airpay.android") // ShopeePay
            packages.add("com.shopee.ph") // Shopee PH (just in case)
        }
        
        if (prefs.getBoolean("APP_BANKING", true)) {
            packages.add("com.kasikorn.retail.mbanking.jap") // K PLUS
            packages.add("com.scb.phone") // SCB EASY
            packages.add("com.ktb.customer.jai") // Krungthai NEXT
            packages.add("com.bbl.mobilebanking") // Bualuang mBanking
            packages.add("com.krungsri.ndid") // KMA Krungsri
            packages.add("com.gsb.mymogms") // MyMo GSB
            packages.add("com.gsb.mymo") // MyMo GSB
            packages.add("com.ttbbank.tc") // ttb touch
            packages.add("com.kiatnakin.phatra") // KKP Mobile
            packages.add("com.krungsri.uchoose") // UCHOOSE
            packages.add("com.tisco.mobile") // TISCO
        }
        return packages
    }

    override fun onCreate() {
        super.onCreate()
        startActionCommandListener()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        AppLogger.log("✅ Service Connected to Android System! Ready to read notifications.")
        listenJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val prefs = getSharedPreferences("SenderPrefs", Context.MODE_PRIVATE)
                val enabled = prefs.getBoolean("FORWARD_NOTIFICATIONS", true)
                if (enabled && hasLocalNetwork()) {
                    sendUdpBroadcast(JSONObject().apply { put("type", "heartbeat") }.toString(), reliable = false)
                }
                val lastSeen = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                    .getLong("LAST_RECEIVER_SEEN", 0L)
                val recentlyConnected = System.currentTimeMillis() - lastSeen < 2 * 60 * 1000L
                val delayMs = when {
                    !enabled -> 30 * 60_000L
                    recentlyConnected -> 60_000L
                    else -> 5 * 60_000L
                }
                kotlinx.coroutines.delay(delayMs)
            }
        }

        connectionReceiver = ConnectionReceiver()
        val filter = IntentFilter()
        filter.addAction(android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION)
        filter.addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
        androidx.core.content.ContextCompat.registerReceiver(
            this, connectionReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )

        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                    val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
                    
                    if (level != -1 && level != lastBatteryLevel) {
                        lastBatteryLevel = level
                        val enabled = getSharedPreferences("SenderPrefs", Context.MODE_PRIVATE).getBoolean("BATTERY_ALERT", true)
                        if (enabled && !isCharging && (level == 20 || level == 15 || level == 10 || level == 5)) {
                            val payload = JSONObject().apply {
                                put("type", "battery")
                                put("level", level)
                            }.toString()
                            sendUdpBroadcast(payload)
                            AppLogger.log("🔋 Battery alert sent: $level%")
                        }
                    }
                }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun startActionCommandListener() {
        actionListenJob?.cancel()
        actionListenJob = CoroutineScope(Dispatchers.IO).launch {
            var actionSocket: DatagramSocket? = null
            try {
                actionSocket = DatagramSocket(8889)
                val buffer = ByteArray(4096)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    actionSocket.receive(packet)
                    val envelope = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val jsonString = SecureUdp.decode(this@NotificationSenderService, envelope) ?: continue
                    AppLogger.log("Received authenticated action")
                    
                    val json = JSONObject(jsonString)
                    getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit()
                        .putLong("LAST_RECEIVER_SEEN", System.currentTimeMillis()).apply()
                    packet.address.hostAddress?.let { ip ->
                        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit()
                            .putString("LAST_RECEIVER_IP", ip).apply()
                    }
                    if (json.optString("type") == "ack") {
                        pendingMessages.remove(json.optString("messageId"))
                        continue
                    }
                    val actionId = json.optString("actionId")
                    val replyText = json.optString("text", "")
                    
                    if (actionId == "share_eta") {
                        var lat = ""
                        var lon = ""
                        try {
                            val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                            if (androidx.core.content.ContextCompat.checkSelfPermission(this@NotificationSenderService, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                val lastLocation = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                                    ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                                if (lastLocation != null) {
                                    lat = lastLocation.latitude.toString()
                                    lon = lastLocation.longitude.toString()
                                }
                            }
                        } catch (e: Exception) {}

                        val action = pendingIntents.remove(replyText)?.takeIf { System.currentTimeMillis() - it.createdAt <= actionTtlMs }
                        if (action != null && action.remoteInputKey != null) {
                            val etaText = if (lat.isEmpty()) "📍 แชร์พิกัด: ไม่สามารถดึงพิกัดได้ (ยังไม่เปิดสิทธิ์)" else "📍 ตอนนี้อยู่พิกัด: https://maps.google.com/?q=$lat,$lon 🚗💨"
                            val intent = Intent()
                            val bundle = android.os.Bundle()
                            bundle.putCharSequence(action.remoteInputKey, etaText)
                            val remoteInput = android.app.RemoteInput.Builder(action.remoteInputKey).build()
                            android.app.RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
                            action.intent.send(this@NotificationSenderService, 0, intent)
                            AppLogger.log("✅ Sent authenticated location reply")
                        }
                        continue
                    }

                    if (actionId == "find_phone") {
                        AppLogger.log("🚨 Find Phone triggered!")
                        try {
                            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                            audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM), 0)
                            
                            val alarmUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                            val ringtone = android.media.RingtoneManager.getRingtone(this@NotificationSenderService, alarmUri)
                            ringtone.play()
                            
                            // Stop after 10 seconds
                            CoroutineScope(Dispatchers.Main).launch {
                                kotlinx.coroutines.delay(10000)
                                ringtone.stop()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        continue
                    }

                    if (actionId == "media_prev" || actionId == "media_play_pause" || actionId == "media_next") {
                        try {
                            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                            val keyCode = when (actionId) {
                                "media_prev" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                                "media_next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                                else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                            }
                            audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
                            audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
                            AppLogger.log("🎵 Sent media key $keyCode")
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        continue
                    }

                    val action = pendingIntents.remove(actionId)?.takeIf { System.currentTimeMillis() - it.createdAt <= actionTtlMs }
                    if (action != null) {
                        if (replyText.isNotEmpty() && action.remoteInputKey != null) {
                            val intent = Intent()
                            val bundle = android.os.Bundle()
                            bundle.putCharSequence(action.remoteInputKey, replyText)
                            val remoteInput = android.app.RemoteInput.Builder(action.remoteInputKey).build()
                            android.app.RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
                            action.intent.send(this@NotificationSenderService, 0, intent)
                            AppLogger.log("✅ Sent authenticated quick reply")
                        } else {
                            action.intent.send()
                            AppLogger.log("✅ Executed action $actionId")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                actionSocket?.close()
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        AppLogger.log("❌ Service Disconnected from Android System.")
        batteryReceiver?.let { 
            unregisterReceiver(it)
            batteryReceiver = null
        }
        connectionReceiver?.let {
            unregisterReceiver(it)
            connectionReceiver = null
        }
        actionListenJob?.cancel()
        listenJob?.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val senderPrefs = getSharedPreferences("SenderPrefs", Context.MODE_PRIVATE)
        if (!senderPrefs.getBoolean("FORWARD_NOTIFICATIONS", true)) return
        val expiry = System.currentTimeMillis() - actionTtlMs
        pendingIntents.entries.removeIf { it.value.createdAt < expiry }
        val packageName = sbn.packageName
        AppLogger.log("📩 Detected notification from: $packageName")

        val notification = sbn.notification
        val template = notification.extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
        val isMediaStyle = template.contains("MediaStyle") || sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        val isCall = notification.category == Notification.CATEGORY_CALL || 
                     (packageName == "jp.naver.line.android" && (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0 && notification.category == null)

        if (!getTargetPackages().contains(packageName) && !isMediaStyle && !isCall) {
            AppLogger.log("   -> Ignored (not in target list and not media/call)")
            return
        }

        // 1. Ignore ongoing background services (like "Chat heads active") UNLESS it is a Call or Media
        if (!isCall && !isMediaStyle && (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) {
            AppLogger.log("   -> Ignored (Ongoing background event)")
            return
        }

        // 2. Ignore group summaries (prevents duplicate spam)
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            AppLogger.log("   -> Ignored (Group Summary)")
            return
        }

        val extras = notification.extras

        // Extract title (Name) and text (Message)
        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

        // 3. Explicitly ignore Messenger Chat Heads (which sometimes lack FLAG_ONGOING_EVENT)
        if (packageName == "com.facebook.orca") {
            val lowerTitle = title.lowercase()
            val lowerText = text.lowercase()
            if (lowerTitle.contains("chat heads") || lowerText.contains("chat heads") ||
                lowerTitle.contains("แชทเฮด") || lowerText.contains("แชทเฮด") ||
                lowerTitle.contains("กำลังใช้งานแชทเฮด") || lowerText.contains("กำลังใช้งานแชทเฮด") ||
                lowerTitle.contains("active chat heads") || lowerText.contains("active chat heads") ||
                lowerText == "active" || lowerTitle == "active") {
                AppLogger.log("   -> Ignored (Messenger Chat Heads)")
                return
            }
        }

        // Handle MessagingStyle (often used by Line, WhatsApp, Messenger, Telegram)
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null && messages.isNotEmpty()) {
            try {
                val latestMessage = messages.last() as? android.os.Bundle
                if (latestMessage != null) {
                    val msgText = latestMessage.getCharSequence("text")?.toString()?.trim()
                    val senderPerson = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        latestMessage.getParcelable<android.app.Person>("sender_person")?.name?.toString()
                    } else null
                    val sender = senderPerson ?: latestMessage.getCharSequence("sender")?.toString()
                    
                    if (!msgText.isNullOrEmpty()) {
                        text = msgText
                        if (!sender.isNullOrEmpty()) {
                            title = sender
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (packageName == "com.facebook.katana" || packageName == "com.facebook.lite") {
            if (text.isNotEmpty()) {
                text += "\n\n"
            }
            text += "⚠️ ขณะนี้กำลังขับขี่ยานพาหนะ เพื่อความปลอดภัยโปรดระมัดระวังในการใช้งาน"
        }

        // 3. Ignore completely empty notifications
        if (title.isEmpty() && text.isEmpty()) {
            AppLogger.log("   -> Ignored (Empty title and text)")
            return
        }

        // 4. Ignore Messenger Chat Head active notifications
        if (packageName == "com.facebook.orca") {
            val lowerText = text.lowercase()
            val lowerTitle = title.lowercase()
            if (lowerText.contains("chat head") || lowerText.contains("chathead") || 
                lowerTitle.contains("chat head") || lowerTitle.contains("chathead") ||
                lowerText.contains("เริ่มการสนทนา") || lowerTitle.contains("เริ่มการสนทนา") ||
                lowerText.contains("กำลังใช้แชทเฮดอยู่") || lowerTitle.contains("กำลังใช้แชทเฮดอยู่")) {
                AppLogger.log("   -> Ignored (Messenger Chat Head background notification)")
                return
            }
        }

        // Determine if Call or Message.
        var type = if (isCall) "call" else "message"

        // Check if it's a Media notification.
        if (isMediaStyle) {
            type = "media"
        }

        // Extract and compress Image
        val includeImages = senderPrefs.getBoolean("SEND_IMAGES", true)
        val imageBase64 = if (includeImages) extractAndCompressImage(notification, this) else null
        val appIconBase64 = if (includeImages) getAppIconBase64(packageName, this) else null

        // Extract Actions
        val actionsArray = org.json.JSONArray()
        var replyActionId: String? = null

        notification.actions?.forEach { action ->
            var actionTitle = action.title?.toString()
            val intent = action.actionIntent
            val remoteInputs = action.remoteInputs

            if (isMediaStyle && actionTitle != null) {
                actionTitle = when (actionTitle.lowercase(java.util.Locale.ROOT)) {
                    "play", "เล่น" -> "▶️"
                    "pause", "หยุด" -> "⏸️"
                    "next", "ถัดไป", "skip forward" -> "⏭️"
                    "previous", "ก่อนหน้า", "prev", "skip backward" -> "⏮️"
                    else -> actionTitle
                }
            }

            if (actionTitle != null && intent != null) {
                val id = UUID.randomUUID().toString()
                
                if (remoteInputs != null && remoteInputs.isNotEmpty() && replyActionId == null) {
                    val remoteInputKey = remoteInputs[0].resultKey
                    pendingIntents[id] = PendingAction(intent, remoteInputKey)
                    replyActionId = id
                } else {
                    pendingIntents[id] = PendingAction(intent, null)
                    val actionJson = JSONObject().apply {
                        put("id", id)
                        put("title", actionTitle)
                    }
                    actionsArray.put(actionJson)
                }
            }
        }

        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)

        // Build JSON Payload
        val jsonPayload = JSONObject().apply {
            put("type", type)
            put("package", packageName)
            put("name", title)
            put("text", text)
            put("imageBase64", imageBase64 ?: JSONObject.NULL)
            put("appIconBase64", appIconBase64 ?: JSONObject.NULL)
            put("isGroup", isGroup)
            put("actions", actionsArray)
            if (replyActionId != null) {
                put("replyActionId", replyActionId)
            }
        }.toString()

        // Send via UDP Broadcast
        sendUdpBroadcast(jsonPayload)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val template = sbn.notification.extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
        val isMediaStyle = template.contains("MediaStyle") || sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        val isCall = sbn.notification.category == Notification.CATEGORY_CALL || 
                     (packageName == "jp.naver.line.android" && (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0 && sbn.notification.category == null)

        if (isMediaStyle) return // Ignore remove for media to prevent flickering/disappearing

        if (getTargetPackages().contains(packageName) || isCall) {
            val jsonPayload = JSONObject().apply {
                put("type", "remove")
                put("package", packageName)
            }.toString()
            sendUdpBroadcast(jsonPayload)
        }
    }

    private fun extractAndCompressImage(notification: Notification, context: Context): String? {
        var bitmap: Bitmap? = null

        // Try getting avatar from MessagingStyle sender
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val messages = notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                if (messages != null && messages.isNotEmpty()) {
                    val latestMessage = messages.last() as? android.os.Bundle
                    if (latestMessage != null) {
                        val senderPerson = latestMessage.getParcelable<android.app.Person>("sender_person")
                        if (senderPerson?.icon != null) {
                            val drawable = senderPerson.icon!!.loadDrawable(context)
                            bitmap = drawableToBitmap(drawable)
                        }
                    }
                }
                
                if (bitmap == null) {
                    val person = notification.extras.getParcelable<android.app.Person>(Notification.EXTRA_MESSAGING_PERSON)
                    if (person?.icon != null) {
                        val drawable = person.icon!!.loadDrawable(context)
                        bitmap = drawableToBitmap(drawable)
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.log("Failed to extract MessagingStyle icon: ${e.message}")
        }

        // Try using the getLargeIcon method (API 23+)
        if (bitmap == null) {
            try {
                val largeIcon: Icon? = notification.getLargeIcon()
                if (largeIcon != null) {
                    val drawable = largeIcon.loadDrawable(context)
                    bitmap = drawableToBitmap(drawable)
                }
            } catch (e: Exception) {
                AppLogger.log("Failed to extract LargeIcon: ${e.message}")
            }
        } 
        
        // Fallback for EXTRA_LARGE_ICON
        if (bitmap == null) {
            try {
                val parcelable = notification.extras.getParcelable<android.os.Parcelable>(Notification.EXTRA_LARGE_ICON)
                if (parcelable is Bitmap) {
                    bitmap = parcelable
                } else if (parcelable is Icon) {
                    val drawable = parcelable.loadDrawable(context)
                    bitmap = drawableToBitmap(drawable)
                }
            } catch (e: Exception) {
                AppLogger.log("Failed to extract EXTRA_LARGE_ICON: ${e.message}")
            }
        }

        // Fallback for EXTRA_PICTURE
        if (bitmap == null) {
            try {
                val parcelable = notification.extras.getParcelable<android.os.Parcelable>(Notification.EXTRA_PICTURE)
                if (parcelable is Bitmap) {
                    bitmap = parcelable
                } else if (parcelable is Icon) {
                    val drawable = parcelable.loadDrawable(context)
                    bitmap = drawableToBitmap(drawable)
                }
            } catch (e: Exception) {
                AppLogger.log("Failed to extract EXTRA_PICTURE: ${e.message}")
            }
        }

        // Fallback for Person objects (Android P+)
        if (bitmap == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            try {
                val person = notification.extras.getParcelable<android.app.Person>(Notification.EXTRA_MESSAGING_PERSON)
                if (person?.icon != null) {
                    val drawable = person.icon!!.loadDrawable(context)
                    bitmap = drawableToBitmap(drawable)
                }
            } catch (e: Exception) {
                AppLogger.log("Failed to extract EXTRA_MESSAGING_PERSON: ${e.message}")
            }
        }
        
        // Fallback for Call Person (Android S+)
        if (bitmap == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                val person = notification.extras.getParcelable<android.app.Person>("android.callPerson")
                if (person?.icon != null) {
                    val drawable = person.icon!!.loadDrawable(context)
                    bitmap = drawableToBitmap(drawable)
                }
            } catch (e: Exception) {
                AppLogger.log("Failed to extract callPerson: ${e.message}")
            }
        }

        if (bitmap == null) return null

        return try {
            // UDP has size limits. We scale down the image heavily to ensure we don't fragment or drop packets.
            // A 64x64 heavily compressed JPEG is small enough to fit within a standard UDP MTU.
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream)
            
            val imageBytes = outputStream.toByteArray()
            Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getAppIconBase64(packageName: String, context: Context): String? {
        return try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = drawableToBitmap(drawable) ?: return null
            // App icons are small and transparent, use 48x48 PNG
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            val bytes = outputStream.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        if (drawable is BitmapDrawable) return drawable.bitmap

        return try {
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 108
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 108
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val broadcastList = mutableListOf<InetAddress>()
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
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
        
        // ALWAYS add the global broadcast and default Android Hotspot broadcasts
        try {
            broadcastList.add(InetAddress.getByName("255.255.255.255"))
            broadcastList.add(InetAddress.getByName("192.168.43.255"))
            broadcastList.add(InetAddress.getByName("192.168.137.255"))
            broadcastList.add(InetAddress.getByName("192.168.216.255")) // Common in newer Androids
        } catch (e: Exception) {}
        
        // Remove duplicates
        return broadcastList.distinct()
    }

    private fun hasLocalNetwork(): Boolean {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback) continue
                if (network.interfaceAddresses.any { it.broadcast != null }) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun getDeliveryAddresses(): List<InetAddress> {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong("LAST_RECEIVER_SEEN", 0L)
        val ip = prefs.getString("LAST_RECEIVER_IP", null)
        if (!ip.isNullOrBlank() && System.currentTimeMillis() - lastSeen < 5 * 60 * 1000L) {
            try {
                return listOf(InetAddress.getByName(ip))
            } catch (_: Exception) { }
        }
        return getBroadcastAddresses()
    }

    private fun sendUdpBroadcast(payload: String, reliable: Boolean = true) {
        AppLogger.log("Sending encrypted notification")
        
        CoroutineScope(Dispatchers.IO).launch {
            val messageId = UUID.randomUUID().toString()
            val message = try { JSONObject(payload).put("_messageId", messageId).toString() } catch (_: Exception) { payload }
            if (reliable) pendingMessages[messageId] = true
            try {
                repeat(if (reliable) 3 else 1) { attempt ->
                    if (reliable && !pendingMessages.containsKey(messageId)) return@launch
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        val encrypted = SecureUdp.encode(this@NotificationSenderService, message) ?: return@launch
                        val payloadBytes = encrypted.toByteArray(Charsets.UTF_8)
                        for (address in getDeliveryAddresses()) {
                            try {
                                socket.send(DatagramPacket(payloadBytes, payloadBytes.size, address, 8888))
                            } catch (e: Exception) {
                                AppLogger.log("Encrypted broadcast failed: ${e.javaClass.simpleName}")
                            }
                        }
                    }
                    if (reliable && attempt < 2) kotlinx.coroutines.delay(700L * (attempt + 1))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingMessages.remove(messageId)
            }
        }
    }
}
