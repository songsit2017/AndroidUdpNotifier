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

class NotificationSenderService : NotificationListenerService() {

    // Target Packages
    private val TARGET_PACKAGES = listOf(
        "jp.naver.line.android", 
        "com.facebook.orca"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        if (!TARGET_PACKAGES.contains(packageName)) {
            return
        }

        val notification = sbn.notification
        val extras = notification.extras

        // Extract title (Name) and text (Message)
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""

        // Determine if Call or Message. Most VoIP apps use CATEGORY_CALL for incoming calls.
        val isCall = notification.category == Notification.CATEGORY_CALL
        val type = if (isCall) "call" else "message"

        // Extract and compress Image
        val imageBase64 = extractAndCompressImage(notification, this)

        // Build JSON Payload
        val jsonPayload = JSONObject().apply {
            put("type", type)
            put("package", packageName)
            put("name", title)
            put("text", text)
            put("imageBase64", imageBase64 ?: JSONObject.NULL)
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

    private fun sendUdpBroadcast(payload: String) {
        // Run network operation in the background
        CoroutineScope(Dispatchers.IO).launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true

                val payloadBytes = payload.toByteArray(Charsets.UTF_8)
                
                // Using standard broadcast IP address.
                // Depending on the router, calculating the local subnet broadcast address (e.g. 192.168.1.255) is safer, 
                // but 255.255.255.255 often works for simple LAN setups.
                val address = InetAddress.getByName("255.255.255.255")
                val port = 8888
                
                val packet = DatagramPacket(payloadBytes, payloadBytes.size, address, port)
                socket.send(packet)
                
                println("Broadcast Sent: $payload")
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                socket?.close()
            }
        }
    }
}
