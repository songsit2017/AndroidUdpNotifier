package com.example.senderapp

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ensurePairingConfigured()

        tvLog = findViewById(R.id.tvLog)
        
        val tvVersionInfo = findViewById<TextView>(R.id.tvVersionInfo)
        tvVersionInfo.text = "Version ${BuildConfig.VERSION_NAME} | Created by Songsit2017 x PUPU"

        val btnCheckUpdate = findViewById<Button>(R.id.btnCheckUpdate)
        btnCheckUpdate.setOnClickListener {
            AutoUpdater.checkForUpdates(this, showToastIfUpToDate = true)
        }
        
        // Auto check on startup
        AutoUpdater.checkForUpdates(this, showToastIfUpToDate = false)

        val btnOpenSettings = findViewById<Button>(R.id.btnOpenSettings)
        btnOpenSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        val btnReqLocation = findViewById<Button>(R.id.btnReqLocation)
        btnReqLocation.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                requestPermissions(arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ), 1)
            } else {
                requestPermissions(arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ), 1)
            }
        }

        val btnViewLocation = findViewById<Button>(R.id.btnViewLocation)
        btnViewLocation.setOnClickListener {
            val prefs = getSharedPreferences("AppPrefs", android.content.Context.MODE_PRIVATE)
            val lat = prefs.getFloat("PARK_LAT", 0f)
            val lon = prefs.getFloat("PARK_LON", 0f)
            if (lat != 0f && lon != 0f) {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:$lat,$lon?q=$lat,$lon(ที่จอดรถล่าสุด)"))
                startActivity(intent)
            } else {
                android.widget.Toast.makeText(this, "ยังไม่มีพิกัดที่จอดรถ", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        val btnReqBattery = findViewById<Button>(R.id.btnReqBattery)
        btnReqBattery.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } else {
                    android.widget.Toast.makeText(this, "ปิดโหมดประหยัดพลังงานแล้ว!", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        AppLogger.listener = { message ->
            val currentText = tvLog.text.toString()
            val newText = "$message\n\n$currentText"
            // limit to 1000 chars to avoid memory issues
            tvLog.text = if (newText.length > 2000) newText.substring(0, 2000) else newText
        }
    }

    private fun ensurePairingConfigured() {
        if (SecureUdp.hasPairingCode(this)) return
        val input = EditText(this).apply {
            hint = "อย่างน้อย 12 ตัวอักษร"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("ตั้งรหัสจับคู่ที่ปลอดภัย")
            .setMessage("กรอกรหัสเดียวกันบนโทรศัพท์และจอรถ แนะนำรหัสสุ่มอย่างน้อย 16 ตัว")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("บันทึก", null)
            .create().apply {
                setOnShowListener {
                    getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (SecureUdp.setPairingCode(this@MainActivity, input.text.toString())) dismiss()
                        else input.error = "รหัสต้องยาวอย่างน้อย 12 ตัวอักษร"
                    }
                }
                show()
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.listener = null
    }
}
