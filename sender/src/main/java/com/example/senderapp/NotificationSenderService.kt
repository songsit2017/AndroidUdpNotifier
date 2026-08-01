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
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import android.app.PendingIntent

class NotificationSenderService : NotificationListenerService() {
    private var listenJob: kotlinx.coroutines.Job? = null
    private val pendingIntents = ConcurrentHashMap<String, PendingIntent>()

    // Target Packages
    private val TARGET_PACKAGES = listOf(
        "jp.naver.line.android", 
        "com.facebook.orca",
        "org.telegram.messenger",
        "com.google.android.gm",
        "com.whatsapp"
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        AppLogger.log("✅ Service Connected to Android System! Ready to read notifications.")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        AppLogger.log("❌ Service Disconnected from Android System.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        AppLogger.log("🔔 Detected notification from: $packageName")

        if (!TARGET_PACKAGES.contains(packageName)) {
            AppLogger.log("   -> Ignored (not in target list)")
            return
        }

        val notification = sbn.notification

        // 1. Ignore ongoing background services (like "Chat heads active")
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) {
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
        val text = extras.getString(Notification.EXTRA_TEXT)?.trim() ?: ""

        // 3. Ignore completely empty notifications
        if (title.isEmpty() && text.isEmpty()) {
            AppLogger.log("   -> Ignored (Empty title and text)")
            return
        }

        // Determine if Call or Message. Most VoIP apps use CATEGORY_CALL for incoming calls.
        val isCall = notification.category == Notification.CATEGORY_CALL
        val type = if (isCall) "call" else "message"

        // Extract and compress Image
        val imageBase64 = extractAndCompressImage(notification, this)
        val appIconBase64 = getAppIconBase64(packageName, this)

        // Extract Actions
        val actionsArray = org.json.JSONArray()
        notification.actions?.forEach { action ->
            val actionTitle = action.title?.toString()
            val intent = action.actionIntent
            if (actionTitle != null && intent != null) {
                val id = UUID.randomUUID().toString()
                pendingIntents[id] = intent
                val actionJson = JSONObject().apply {
                    put("id", id)
                    put("title", actionTitle)
                }
                actionsArray.put(actionJson)
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
        }.toString()

        // Send via UDP Broadcast
        sendUdpBroadcast(jsonPayload)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Optional: Handle notification removal if needed
    }

    private fun extractAndCompressImage(notification: Notification, context: Context): String? {
        var bitmap: Bitmap? = null

        // Try using the getLargeIcon method (API 23+)
        val largeIcon: Icon? = notification.getLargeIcon()
        if (largeIcon != null) {
            val drawable = largeIcon.loadDrawable(context)
            bitmap = drawableToBitmap(drawable)
        } 
        
        // Fallback for older intents or alternative data structures
        if (bitmap == null) {
            val parcelable = notification.extras.getParcelable<android.os.Parcelable>(Notification.EXTRA_LARGE_ICON)
            if (parcelable is Bitmap) {
                bitmap = parcelable
            } else if (parcelable is Icon) {
                val drawable = parcelable.loadDrawable(context)
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
        if (broadcastList.isEmpty()) {
            broadcastList.add(InetAddress.getByName("255.255.255.255"))
        }
        return broadcastList
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
