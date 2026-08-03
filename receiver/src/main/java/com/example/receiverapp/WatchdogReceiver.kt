package com.example.receiverapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("WatchdogReceiver", "Watchdog triggered: checking if UdpReceiverService is running...")
        
        val serviceIntent = Intent(context, UdpReceiverService::class.java)
        
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d("WatchdogReceiver", "UdpReceiverService started/verified by Watchdog.")
        } catch (e: Exception) {
            Log.e("WatchdogReceiver", "Failed to start UdpReceiverService", e)
        }
    }
}
