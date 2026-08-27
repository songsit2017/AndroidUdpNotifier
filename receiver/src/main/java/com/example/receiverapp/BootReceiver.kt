package com.example.receiverapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_USER_UNLOCKED
        ) {
            try {
                // Schedule before starting the service.  If the vendor power
                // manager stops it while resuming from a long sleep, the alarm
                // remains available as a second path back into the app.
                WatchdogReceiver.schedule(context)
                val serviceIntent = Intent(context, UdpReceiverService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
                AppLogger.log("Startup receiver handled: $action")
            } catch (e: Exception) {
                android.util.Log.e("BootReceiver", "Foreground service start was blocked", e)
                AppLogger.log("Startup receiver could not start service: ${e.javaClass.simpleName}")
            }
        }
    }
}
