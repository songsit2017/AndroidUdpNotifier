package com.example.receiverapp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.TextView
import android.widget.Switch
import android.content.Context
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val OVERLAY_PERMISSION_REQ_CODE = 1234
    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLog = findViewById(R.id.tvLog)

        AppLogger.listener = { message ->
            val currentText = tvLog.text.toString()
            val newText = "$message\n\n$currentText"
            tvLog.text = if (newText.length > 2000) newText.substring(0, 2000) else newText
        }

        setupSettings()

        requestIgnoreBatteryOptimizations()
        checkOverlayPermissionAndStart()
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun checkOverlayPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
        } else {
            startUdpService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startUdpService()
            }
        }
    }

    private fun startUdpService() {
        val serviceIntent = Intent(this, UdpReceiverService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun setupSettings() {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val switchQuickReply = findViewById<Switch>(R.id.switchQuickReply)
        val switchTTS = findViewById<Switch>(R.id.switchTTS)
        val switchMedia = findViewById<Switch>(R.id.switchMedia)
        val switchBattery = findViewById<Switch>(R.id.switchBattery)
        val switchDND = findViewById<Switch>(R.id.switchDND)

        // Load saved or defaults
        switchQuickReply.isChecked = prefs.getBoolean("PREF_QUICK_REPLY", true)
        switchTTS.isChecked = prefs.getBoolean("PREF_TTS", true)
        switchMedia.isChecked = prefs.getBoolean("PREF_MEDIA", true)
        switchBattery.isChecked = prefs.getBoolean("PREF_BATTERY", true)
        switchDND.isChecked = prefs.getBoolean("PREF_DND", false)

        // Save on change
        val listener = { key: String, isChecked: Boolean ->
            prefs.edit().putBoolean(key, isChecked).apply()
        }
        
        switchQuickReply.setOnCheckedChangeListener { _, c -> listener("PREF_QUICK_REPLY", c) }
        switchTTS.setOnCheckedChangeListener { _, c -> listener("PREF_TTS", c) }
        switchMedia.setOnCheckedChangeListener { _, c -> listener("PREF_MEDIA", c) }
        switchBattery.setOnCheckedChangeListener { _, c -> listener("PREF_BATTERY", c) }
        switchDND.setOnCheckedChangeListener { _, c -> listener("PREF_DND", c) }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.listener = null
    }
}
