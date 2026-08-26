package com.example.receiverapp

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Emits only the package name of the focused window. No text, nodes, or view
 * hierarchy are requested or read. This is more reliable than UsageStats on a
 * DUDU multi-display layout, where Maps and YouTube remain resumed alongside
 * LauncherActivity.
 */
class ForegroundWindowAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        val launcherVisible = packageName == DUDU_PACKAGE
        getSharedPreferences("AppPrefs", MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_DUDU_LAUNCHER_FOREGROUND, launcherVisible)
            .apply()
        sendBroadcast(Intent(ACTION_FOREGROUND_WINDOW_CHANGED).apply {
            setPackage(applicationContext.packageName)
            putExtra(EXTRA_DUDU_LAUNCHER_FOREGROUND, launcherVisible)
        })
    }

    override fun onInterrupt() = Unit

    companion object {
        const val DUDU_PACKAGE = "com.dudu.autoui"
        const val PREF_DUDU_LAUNCHER_FOREGROUND = "PREF_DUDU_LAUNCHER_FOREGROUND"
        const val ACTION_FOREGROUND_WINDOW_CHANGED = "com.example.receiverapp.FOREGROUND_WINDOW_CHANGED"
        const val EXTRA_DUDU_LAUNCHER_FOREGROUND = "dudu_launcher_foreground"
    }
}
