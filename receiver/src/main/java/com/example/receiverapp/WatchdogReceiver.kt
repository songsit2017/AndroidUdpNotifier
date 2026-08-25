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

    companion object {
        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(context, WatchdogReceiver::class.java)
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                0,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val interval = 5L * 60L * 1000L
            alarmManager.setInexactRepeating(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + interval,
                interval,
                pendingIntent
            )
        }
    }
}
