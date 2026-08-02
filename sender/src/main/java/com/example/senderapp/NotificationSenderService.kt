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
    data class PendingAction(val intent: PendingIntent, val remoteInputKey: String?)
    private val pendingIntents = ConcurrentHashMap<String, PendingAction>()
    private var batteryReceiver: BroadcastReceiver? = null
    private var connectionReceiver: ConnectionReceiver? = null
    private var lastBatteryLevel = -1

    // Target Packages
    private val TARGET_PACKAGES = listOf(
        "jp.naver.line.android", 
        "com.facebook.orca",
        "com.facebook.katana",
        "com.facebook.lite",
        "org.telegram.messenger",
        "com.google.android.gm",
        "com.whatsapp"
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        AppLogger.log("✅ Service Connected to Android System! Ready to read notifications.")
        startActionCommandListener()

        connectionReceiver = ConnectionReceiver()
        registerReceiver(connectionReceiver, IntentFilter(android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION))

        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                    if (level != -1 && level != lastBatteryLevel) {
                        lastBatteryLevel = level
                        if (level == 20 || level == 15 || level == 10 || level == 5) {
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
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun startActionCommandListener() {
        actionListenJob = CoroutineScope(Dispatchers.IO).launch {
            var actionSocket: DatagramSocket? = null
            try {
                actionSocket = DatagramSocket(8889)
                val buffer = ByteArray(4096)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    actionSocket.receive(packet)
                    val jsonString = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    AppLogger.log("Received action: $jsonString")
                    
                    val json = JSONObject(jsonString)
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

                        val action = pendingIntents[replyText]
                        if (action != null && action.remoteInputKey != null) {
                            val etaText = if (lat.isEmpty()) "📍 แชร์พิกัด: ไม่สามารถดึงพิกัดได้ (ยังไม่เปิดสิทธิ์)" else "📍 ตอนนี้อยู่พิกัด: https://maps.google.com/?q=$lat,$lon 🚗💨"
                            val intent = Intent()
                            val bundle = android.os.Bundle()
                            bundle.putCharSequence(action.remoteInputKey, etaText)
                            val remoteInput = android.app.RemoteInput.Builder(action.remoteInputKey).build()
                            android.app.RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
                            action.intent.send(this@NotificationSenderService, 0, intent)
                            AppLogger.log("✅ Sent ETA Reply: $etaText")
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

                    val action = pendingIntents[actionId]
                    if (action != null) {
                        if (replyText.isNotEmpty() && action.remoteInputKey != null) {
                            val intent = Intent()
                            val bundle = android.os.Bundle()
                            bundle.putCharSequence(action.remoteInputKey, replyText)
                            val remoteInput = android.app.RemoteInput.Builder(action.remoteInputKey).build()
                            android.app.RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
                            action.intent.send(this@NotificationSenderService, 0, intent)
                            AppLogger.log("✅ Sent Quick Reply: $replyText")
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
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        AppLogger.log("📩 Detected notification from: $packageName")

        val notification = sbn.notification
        val template = notification.extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
        val isMediaStyle = template.contains("MediaStyle")
        val isCall = notification.category == Notification.CATEGORY_CALL || 
                     (packageName == "jp.naver.line.android" && (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0 && notification.category == null)

        if (!TARGET_PACKAGES.contains(packageName) && !isMediaStyle && !isCall) {
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
        val title = extras.getString(Notification.EXTRA_TITLE)?.trim() ?: ""
        var text = extras.getString(Notification.EXTRA_TEXT)?.trim() ?: ""

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
                lowerText.contains("เริ่มการสนทนา") || lowerTitle.contains("เริ่มการสนทนา")) {
                AppLogger.log("   -> Ignored (Messenger Chat Head background notification)")
                return
            }
        }

        // Determine if Call or Message.
        var type = if (isCall) "call" else "message"

        // Check if it's a Media notification
        val template = extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
        if (template.contains("MediaStyle")) {
            type = "media"
        }

        // Extract and compress Image
        val imageBase64 = extractAndCompressImage(notification, this)
        val appIconBase64 = getAppIconBase64(packageName, this)

        // Extract Actions
        val actionsArray = org.json.JSONArray()
        var replyActionId: String? = null

        notification.actions?.forEach { action ->
            val actionTitle = action.title?.toString()
            val intent = action.actionIntent
            val remoteInputs = action.remoteInputs

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

        // Build JSON Payload
        val jsonPayload = JSONObject().apply {
            put("type", type)
            put("package", packageName)
            put("name", title)
            put("text", text)
            put("imageBase64", imageBase64 ?: JSONObject.NULL)
            put("appIconBase64", appIconBase64 ?: JSONObject.NULL)
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
        val isMediaStyle = template.contains("MediaStyle")
        val isCall = sbn.notification.category == Notification.CATEGORY_CALL || 
                     (packageName == "jp.naver.line.android" && (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0 && sbn.notification.category == null)

        if (TARGET_PACKAGES.contains(packageName) || isMediaStyle || isCall) {
            val jsonPayload = JSONObject().apply {
                put("type", "remove")
                put("package", packageName)
            }.toString()
            sendUdpBroadcast(jsonPayload)
        }
    }

    private fun extractAndCompressImage(notification: Notification, context: Context): String? {
        var bitmap: Bitmap? = null

        // Try using the getLargeIcon method (API 23+)
        val largeIcon: Icon? = notification.getLargeIcon()
        if (largeIcon != null) {
            val drawable = largeIcon.loadDrawable(context)
            bitmap = drawableToBitmap(drawable)
        } 
        
        // Fallback for EXTRA_LARGE_ICON
        if (bitmap == null) {
            val parcelable = notification.extras.getParcelable<android.os.Parcelable>(Notification.EXTRA_LARGE_ICON)
            if (parcelable is Bitmap) {
                bitmap = parcelable
            } else if (parcelable is Icon) {
                val drawable = parcelable.loadDrawable(context)
                bitmap = drawableToBitmap(drawable)
            }
        }

        // Fallback for EXTRA_PICTURE
        if (bitmap == null) {
            val parcelable = notification.extras.getParcelable<android.os.Parcelable>(Notification.EXTRA_PICTURE)
            if (parcelable is Bitmap) {
                bitmap = parcelable
            } else if (parcelable is Icon) {
                val drawable = parcelable.loadDrawable(context)
                bitmap = drawableToBitmap(drawable)
            }
        }

        // Fallback for Person objects (Android P+)
        if (bitmap == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val person = notification.extras.getParcelable<android.app.Person>(Notification.EXTRA_MESSAGING_PERSON)
            if (person?.icon != null) {
                val drawable = person.icon!!.loadDrawable(context)
                bitmap = drawableToBitmap(drawable)
            }
        }
        
        // Fallback for Call Person (Android S+)
        if (bitmap == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val person = notification.extras.getParcelable<android.app.Person>("android.callPerson")
            if (person?.icon != null) {
                val drawable = person.icon!!.loadDrawable(context)
                bitmap = drawableToBitmap(drawable)
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
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth.takeIf { it > 0 } ?: 1,
                drawable.intrinsicHeight.takeIf { it > 0 } ?: 1,
                Bitmap.Config.ARGB_8888
            )
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

    private fun sendUdpBroadcast(payload: String) {
        AppLogger.log("Intercepted: $payload")
        
        CoroutineScope(Dispatchers.IO).launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true
                val payloadBytes = payload.toByteArray(Charsets.UTF_8)
                val port = 8888
                
                val broadcastAddresses = getBroadcastAddresses()
                for (address in broadcastAddresses) {
                    try {
                        val packet = DatagramPacket(payloadBytes, payloadBytes.size, address, port)
                        socket.send(packet)
                        println("Broadcast Sent to $address: $payload")
                        AppLogger.log("Broadcast Sent to $address")
                    } catch (e: Exception) {
                        e.printStackTrace()
                        AppLogger.log("Broadcast failed to $address: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                socket?.close()
            }
        }
    }
}
