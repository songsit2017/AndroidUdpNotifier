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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket

class UdpReceiverService : Service() {

    private val TAG = "UdpReceiver"
    private val CHANNEL_ID = "UdpReceiverChannel"
    private val PORT = 8888

    private var listenJob: Job? = null
    private var socket: DatagramSocket? = null

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var autoDismissJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
                        parseAndDisplayData(jsonString)
                    } catch (e: Exception) {
                        if (isActive) Log.e(TAG, "Error receiving packet", e)
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) Log.e(TAG, "UDP Listener Error", e)
            }
        }
    }

    private fun parseAndDisplayData(jsonString: String) {
        try {
            val jsonObject = JSONObject(jsonString)
            val type = jsonObject.optString("type", "unknown")
            val name = jsonObject.optString("name", "Unknown Caller")
            val text = jsonObject.optString("text", "")
            val base64Image = if (jsonObject.isNull("imageBase64")) null else jsonObject.getString("imageBase64")

            CoroutineScope(Dispatchers.Main).launch {
                showFloatingWindow(name, text, base64Image, type)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON", e)
        }
    }

    private fun showFloatingWindow(name: String, text: String, base64Image: String?, type: String) {
        try {
            removeFloatingWindow()

            // Wrap context with a theme so AppCompat components (and ?attr/...) can inflate properly
            val themeContext = android.view.ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_Light_DarkActionBar)
            val inflater = LayoutInflater.from(themeContext)
            
            floatingView = inflater.inflate(R.layout.floating_notification, null)

        val nameText = floatingView?.findViewById<TextView>(R.id.nameText)
        val messageText = floatingView?.findViewById<TextView>(R.id.messageText)
        val profileImage = floatingView?.findViewById<ImageView>(R.id.profileImage)
        val closeButton = floatingView?.findViewById<ImageButton>(R.id.closeButton)

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
            autoDismissJob = CoroutineScope(Dispatchers.Main).launch {
                delay(8000)
                removeFloatingWindow()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating view", e)
        }
        
        } catch (e: Exception) {
            Log.e(TAG, "Fatal Error in showFloatingWindow: ", e)
            AppLogger.log("Crash avoided in showFloatingWindow: ${e.message}")
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
        CoroutineScope(Dispatchers.Main).launch { removeFloatingWindow() }
    }
}
