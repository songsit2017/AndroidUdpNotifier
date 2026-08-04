package com.example.senderapp

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.launch

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
            val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            val ip = prefs.getString("LAST_RECEIVER_IP", null)
            val ageSeconds = if (lastSeen == 0L) null else age / 1000
            findViewById<TextView>(R.id.tvDiagnosticsHealth)?.text = when {
                !SecureUdp.hasPairingCode(this@MainActivity) -> "ยังไม่ได้จับคู่ • สแกน QR จากจอรถก่อน"
                ageSeconds != null && ageSeconds < 20 -> "● เชื่อมต่อปกติ • ตอบกลับ ${ageSeconds} วินาทีที่แล้ว • ${ip ?: "กำลังค้นหา IP"}"
                ageSeconds != null -> "● ขาดการเชื่อมต่อ • พบล่าสุด ${ageSeconds} วินาทีที่แล้ว"
                else -> "● จับคู่แล้ว • กำลังรอสัญญาณจากจอรถ"
            }
            statusHandler.postDelayed(this, 2_000)
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            qrScanner.launch(com.journeyapps.barcodescanner.ScanOptions().apply {
                setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                setPrompt("สแกน QR ที่แสดงบนจอรถ")
                setBeepEnabled(false)
                setOrientationLocked(true)
                setCaptureActivity(PortraitCaptureActivity::class.java)
            })
        } else {
            android.widget.Toast.makeText(this, "กรุณาอนุญาตสิทธิ์กล้องเพื่อสแกน QR", android.widget.Toast.LENGTH_SHORT).show()
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

        findViewById<Button>(R.id.btnTestSystem).setOnClickListener { sendDiagnosticTest() }
        findViewById<Button>(R.id.btnExportDiagnostics).setOnClickListener { Diagnostics.export(this) }

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
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                    android.widget.Toast.makeText(this, "กรุณาเลือก 'อนุญาตตลอดเวลา' (Allow all the time)", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    requestPermissions(arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
                }
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
        bind(R.id.switchAppLine, "APP_LINE")
        bind(R.id.switchAppMessenger, "APP_MESSENGER")
        bind(R.id.switchAppTelegram, "APP_TELEGRAM")
        bind(R.id.switchAppWhatsapp, "APP_WHATSAPP")
        bind(R.id.switchAppGmail, "APP_GMAIL")
        bind(R.id.switchAppShopping, "APP_SHOPPING")
        bind(R.id.switchAppBanking, "APP_BANKING")
    }

    private fun sendDiagnosticTest() {
        if (!SecureUdp.hasPairingCode(this)) {
            android.widget.Toast.makeText(this, "กรุณาจับคู่กับจอรถก่อน", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val destination = prefs.getString("LAST_RECEIVER_IP", null) ?: "255.255.255.255"
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val json = org.json.JSONObject().apply {
                    put("type", "message")
                    put("name", "System Test")
                    put("text", "การเชื่อมต่อ Sender และ Receiver ทำงานปกติ")
                    put("_messageId", java.util.UUID.randomUUID().toString())
                }.toString()
                val encrypted = SecureUdp.encode(this@MainActivity, json) ?: error("Pairing key unavailable")
                java.net.DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val bytes = encrypted.toByteArray(Charsets.UTF_8)
                    socket.send(java.net.DatagramPacket(bytes, bytes.size, java.net.InetAddress.getByName(destination), 8888))
                }
                AppLogger.log("Diagnostic test sent")
                runOnUiThread { android.widget.Toast.makeText(this@MainActivity, "ส่งการทดสอบแล้ว", android.widget.Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                AppLogger.log("Diagnostic test failed: ${e.javaClass.simpleName}")
                runOnUiThread { android.widget.Toast.makeText(this@MainActivity, "ส่งการทดสอบไม่สำเร็จ", android.widget.Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionButtons()
        
        // Broadcast a heartbeat to quickly wake up/discover the receiver and update the connection status
        val lastSeen = getSharedPreferences("AppPrefs", MODE_PRIVATE).getLong("LAST_RECEIVER_SEEN", 0L)
        if (SecureUdp.hasPairingCode(this) && System.currentTimeMillis() - lastSeen > 15_000L) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val json = org.json.JSONObject().apply {
                        put("type", "heartbeat")
                        put("_messageId", java.util.UUID.randomUUID().toString())
                    }.toString()
                    val encrypted = SecureUdp.encode(this@MainActivity, json) ?: return@launch
                    val bytes = encrypted.toByteArray(Charsets.UTF_8)
                    java.net.DatagramSocket().use { socket ->
                        socket.broadcast = true
                        
                        // Try all broadcast addresses
                        val list = mutableListOf<java.net.InetAddress>()
                        try {
                            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                            while (interfaces.hasMoreElements()) {
                                val network = interfaces.nextElement()
                                if (network.isLoopback || !network.isUp) continue
                                for (address in network.interfaceAddresses) {
                                    address.broadcast?.let { list.add(it) }
                                }
                            }
                        } catch (_: Exception) {}
                        if (list.isEmpty()) {
                            list.add(java.net.InetAddress.getByName("255.255.255.255"))
                        }
                        
                        for (address in list) {
                            try {
                                socket.send(java.net.DatagramPacket(bytes, bytes.size, address, 8888))
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
        }
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

        val isNotificationGranted = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        style(findViewById(R.id.btnOpenSettings), isNotificationGranted,
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
            requestCameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
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
