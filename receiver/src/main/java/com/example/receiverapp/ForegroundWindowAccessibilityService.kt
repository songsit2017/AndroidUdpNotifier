package com.example.receiverapp

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.Display
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
        // The volume panel, status bar and notification shade are drawn by
        // SystemUI over LauncherActivity.  They emit a window-state event but
        // do not mean that the user has left DUDU Launcher.  If we mark them
        // as an external app, no later Launcher event is guaranteed when the
        // transient panel closes, which leaves the location strip hidden.
        if (isTransientSystemOverlay(event)) return
        // DUDU keeps Maps and YouTube alive on virtual displays (6/7) while
        // LauncherActivity remains on the physical display (0). Those window
        // events must not hide the strip on the launcher.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            event.displayId > Display.DEFAULT_DISPLAY
        ) return
        val packageName = event.packageName?.toString() ?: return
        // Adding/removing our TYPE_APPLICATION_OVERLAY also creates a window
        // event. It must not be interpreted as leaving DUDU Launcher.
        if (packageName == applicationContext.packageName) return
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

    private fun isTransientSystemOverlay(event: AccessibilityEvent): Boolean {
        val packageName = event.packageName?.toString().orEmpty()
        if (packageName == "android" || packageName == "com.android.systemui") return true

        val className = event.className?.toString().orEmpty()
        return className.contains("VolumeDialog", ignoreCase = true) ||
            className.contains("VolumePanel", ignoreCase = true) ||
            className.contains("StatusBar", ignoreCase = true) ||
            className.contains("GlobalActions", ignoreCase = true)
    }

    override fun onInterrupt() = Unit

    companion object {
        const val DUDU_PACKAGE = "com.dudu.autoui"
        const val PREF_DUDU_LAUNCHER_FOREGROUND = "PREF_DUDU_LAUNCHER_FOREGROUND"
        const val ACTION_FOREGROUND_WINDOW_CHANGED = "com.example.receiverapp.FOREGROUND_WINDOW_CHANGED"
        const val EXTRA_DUDU_LAUNCHER_FOREGROUND = "dudu_launcher_foreground"
    }
}
