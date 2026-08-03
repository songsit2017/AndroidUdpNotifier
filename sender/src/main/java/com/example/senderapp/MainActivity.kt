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
    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusUpdater = object : Runnable {
        override fun run() {
            val lastSeen = getSharedPreferences("AppPrefs", MODE_PRIVATE).getLong("LAST_RECEIVER_SEEN", 0L)
            val age = System.currentTimeMillis() - lastSeen
            findViewById<TextView>(R.id.tvConnectionStatus)?.text = when {
                !SecureUdp.hasPairingCode(this@MainActivity) -> "สถานะ: ยังไม่ได้จับคู่"
                lastSeen > 0 && age < 20_000 -> "สถานะ: เชื่อมต่อแล้ว ✓"
                else -> "สถานะ: จับคู่แล้ว แต่ยังไม่พบจอรถ"
            }
            statusHandler.postDelayed(this, 2_000)
        }
    }
    private val qrScanner = registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { result ->
        if (result.contents != null && SecureUdp.importPairingUri(this, result.contents)) {
            android.widget.Toast.makeText(this, "จับคู่กับจอรถสำเร็จ", android.widget.Toast.LENGTH_LONG).show()
            findViewById<TextView>(R.id.tvConnectionStatus).text = "สถานะ: จับคู่แล้ว กำลังรอสัญญาณ"
        } else if (result.contents != null) {
            android.widget.Toast.makeText(this, "QR นี้ไม่ใช่รหัสจับคู่ของแอป", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupPairingControls()
        setupFeatureSwitches()

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
            val fineGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!fineGranted) {
                requestPermissions(arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ), 1)
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
            } else {
                android.widget.Toast.makeText(this, "อนุญาต GPS ครบแล้ว", android.widget.Toast.LENGTH_SHORT).show()
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
        statusHandler.post(statusUpdater)
    }

    private fun setupFeatureSwitches() {
        val prefs = getSharedPreferences("SenderPrefs", MODE_PRIVATE)
        fun bind(id: Int, key: String, defaultValue: Boolean = true) {
            val control = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(id)
            control.isChecked = prefs.getBoolean(key, defaultValue)
            control.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
        }
        bind(R.id.switchForwardNotifications, "FORWARD_NOTIFICATIONS")
        bind(R.id.switchSendImages, "SEND_IMAGES")
        bind(R.id.switchBatteryAlert, "BATTERY_ALERT")
        bind(R.id.switchAutoPark, "AUTO_PARK")
    }

    override fun onResume() {
        super.onResume()
        updatePermissionButtons()
    }

    private fun updatePermissionButtons() {
        val green = android.graphics.Color.parseColor("#16833B")
        val red = android.graphics.Color.parseColor("#D32F2F")
        fun style(button: com.google.android.material.button.MaterialButton, granted: Boolean, grantedText: String, normalText: String) {
            button.text = if (granted) "✓ $grantedText" else "✕ $normalText"
            val color = if (granted) green else red
            button.setTextColor(color)
            button.strokeColor = android.content.res.ColorStateList.valueOf(color)
        }

        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        style(findViewById(R.id.btnOpenSettings), listeners.contains(packageName),
            "อนุญาตอ่านการแจ้งเตือนแล้ว", "1. อนุญาตอ่านการแจ้งเตือน")

        val fineLocationGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val backgroundLocationGranted = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val locationGranted = fineLocationGranted && backgroundLocationGranted
        style(findViewById(R.id.btnReqLocation), locationGranted,
            "อนุญาตเข้าถึง GPS แล้ว", "2. อนุญาตเข้าถึง GPS เบื้องหลัง")

        val batteryGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName)
        } else true
        style(findViewById(R.id.btnReqBattery), batteryGranted,
            "ปิดการจำกัดแบตเตอรี่แล้ว", "3. ปิดการจำกัดแบตเตอรี่")
    }

    private fun setupPairingControls() {
        val button = findViewById<Button>(R.id.btnScanPairingQr)
        button.setOnClickListener {
            qrScanner.launch(com.journeyapps.barcodescanner.ScanOptions().apply {
                setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                setPrompt("สแกน QR ที่แสดงบนจอรถ")
                setBeepEnabled(false)
                setOrientationLocked(true)
                setCaptureActivity(PortraitCaptureActivity::class.java)
            })
        }
        button.setOnLongClickListener {
            ensurePairingConfigured()
            true
        }
        findViewById<TextView>(R.id.tvConnectionStatus).text =
            if (SecureUdp.hasPairingCode(this)) "สถานะ: จับคู่แล้ว กำลังรอสัญญาณ" else "สถานะ: แตะเพื่อสแกน QR (กดค้างเพื่อกรอกรหัส)"
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
        statusHandler.removeCallbacks(statusUpdater)
        AppLogger.listener = null
    }
}
