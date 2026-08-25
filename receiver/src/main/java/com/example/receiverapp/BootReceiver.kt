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
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            try {
                val serviceIntent = Intent(context, UdpReceiverService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
                WatchdogReceiver.schedule(context)
            } catch (e: Exception) {
                android.util.Log.e("BootReceiver", "Foreground service start was blocked", e)
            }
        }
    }
}
