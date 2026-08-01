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
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.view.View
import android.widget.AdapterView
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

        val btnRequestOverlay = findViewById<Button>(R.id.btnRequestOverlay)
        btnRequestOverlay.setOnClickListener {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
        }

        val btnRequestBattery = findViewById<Button>(R.id.btnRequestBattery)
        btnRequestBattery.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        val btnRequestLocation = findViewById<Button>(R.id.btnRequestLocation)
        btnRequestLocation.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 101)
            }
        }

        setupSettings()

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
        val btnFindPhone = findViewById<Button>(R.id.btnFindPhone)

        btnFindPhone.setOnClickListener {
            val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val ip = prefs.getString("LAST_SENDER_IP", null)
            if (ip != null) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        val socket = java.net.DatagramSocket()
                        val json = org.json.JSONObject().apply {
                            put("actionId", "find_phone")
                        }.toString()
                        val payload = json.toByteArray(Charsets.UTF_8)
                        val address = java.net.InetAddress.getByName(ip)
                        val packet = java.net.DatagramPacket(payload, payload.size, address, 8889)
                        socket.send(packet)
                        socket.close()
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            android.widget.Toast.makeText(this@MainActivity, "🚨 ส่งคำสั่งค้นหามือถือแล้ว!", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } else {
                android.widget.Toast.makeText(this, "ยังไม่เคยได้รับข้อมูลจากมือถือเลย", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

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

        val spinnerTheme = findViewById<Spinner>(R.id.spinnerTheme)
        val themes = arrayOf("Classic", "Honda Type-R", "BMW M", "Tesla")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, themes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTheme.adapter = adapter

        val currentTheme = prefs.getString("PREF_THEME", "Classic")
        spinnerTheme.setSelection(themes.indexOf(currentTheme))

        spinnerTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                prefs.edit().putString("PREF_THEME", themes[position]).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.listener = null
    }
}
