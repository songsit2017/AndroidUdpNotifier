package com.example.receiverapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("WatchdogReceiver", "Watchdog triggered: checking if UdpReceiverService is running...")
        // setAndAllowWhileIdle() is one-shot.  Queue the next check before
        // starting the service, so a failed background start still has a
        // recovery attempt after the next deep-sleep maintenance window.
        schedule(context)
        val serviceIntent = Intent(context, UdpReceiverService::class.java)

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d("WatchdogReceiver", "UdpReceiverService started/verified by Watchdog.")
            AppLogger.log("Keep-alive check started Receiver Service")
        } catch (e: Exception) {
            Log.e("WatchdogReceiver", "Failed to start UdpReceiverService", e)
            AppLogger.log("Keep-alive check was blocked: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val REQUEST_CODE = 94831
        // Android defers ordinary repeating alarms during deep idle.  This is
        // intentionally one-shot and rescheduled on every delivery so it can
        // run in the next idle-maintenance window without requiring the exact
        // alarm special permission.
        private const val INTERVAL_MS = 15L * 60L * 1000L

        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(context, WatchdogReceiver::class.java)
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + INTERVAL_MS,
                pendingIntent
            )
        }
    }
}
