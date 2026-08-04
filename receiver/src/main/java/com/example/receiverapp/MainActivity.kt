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
import android.widget.CompoundButton
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.view.View
import android.widget.AdapterView
import android.widget.EditText
import android.content.Context
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val OVERLAY_PERMISSION_REQ_CODE = 1234
    private lateinit var tvLog: TextView
    private var qrDialog: android.app.AlertDialog? = null

    private val pairingSuccessReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (qrDialog?.isShowing == true) {
                qrDialog?.dismiss()
                qrDialog = null
                android.widget.Toast.makeText(this@MainActivity, "จับคู่สำเร็จ", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusUpdater = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            val lastSeen = prefs.getLong("LAST_SENDER_SEEN", 0L)
            val ip = prefs.getString("LAST_SENDER_IP", null)
            val age = System.currentTimeMillis() - lastSeen
            
            findViewById<TextView>(R.id.tvConnectionStatus)?.text =
                if (ip != null) "สถานะ: พร้อมใช้งาน (จับคู่แล้ว ✓)" else "สถานะ: รอการจับคู่"
                
            val ageSeconds = if (lastSeen == 0L) null else age / 1000
            findViewById<TextView>(R.id.tvDiagnosticsHealth)?.text = when {
                ageSeconds != null && ageSeconds < 20 -> "● เชื่อมต่อล่าสุดตอนนี้ • ${ip}"
                ageSeconds != null -> "● สแตนด์บาย • ขาดการติดต่อ ${ageSeconds} วินาที • ${ip}"
                else -> "● Receiver พร้อม • ยังไม่เคยพบ Sender"
            }
            statusHandler.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupPairingControls()
        startWatchdog()

        tvLog = findViewById(R.id.tvLog)
        
        val tvVersionInfo = findViewById<TextView>(R.id.tvVersionInfo)
        tvVersionInfo.text = "Version ${BuildConfig.VERSION_NAME} | Created by Songsit2017 x PUPU"

        val btnCheckUpdate = findViewById<Button>(R.id.btnCheckUpdate)
        btnCheckUpdate.setOnClickListener {
            AutoUpdater.checkForUpdates(this, showToastIfUpToDate = true)
        }
        
        // Auto check on startup
        AutoUpdater.checkForUpdates(this, showToastIfUpToDate = false)

        ContextCompat.startForegroundService(this, android.content.Intent(this, UdpReceiverService::class.java))
        
        statusHandler.post(statusUpdater)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pairingSuccessReceiver, android.content.IntentFilter("com.example.receiverapp.PAIRING_SUCCESS"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(pairingSuccessReceiver, android.content.IntentFilter("com.example.receiverapp.PAIRING_SUCCESS"))
        }

        findViewById<Button>(R.id.btnTestSystem).setOnClickListener {
            try {
                ContextCompat.startForegroundService(this, Intent(this, UdpReceiverService::class.java).apply {
                    action = UdpReceiverService.ACTION_DIAGNOSTIC_TEST
                })
            } catch (e: Exception) {
                AppLogger.log("Diagnostic popup failed: ${e.javaClass.simpleName}")
                android.widget.Toast.makeText(this, "ทดสอบ Popup ไม่สำเร็จ กรุณาตรวจสิทธิ์ Overlay", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btnExportDiagnostics).setOnClickListener { Diagnostics.export(this) }

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
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                android.widget.Toast.makeText(this, "Location permission already granted!", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 101)
                    android.widget.Toast.makeText(this, "Requesting Location... (If nothing happens, please allow it in App Settings)", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }

        setupSettings()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 102)
        }

        checkOverlayPermissionAndStart()
    }

    private fun setupPairingControls() {
        try {
            SecureUdp.ensurePairingUri(this)
            findViewById<Button>(R.id.btnShowPairingQr).setOnClickListener { showPairingQr() }
        } catch (e: Exception) {
            android.util.Log.e("ReceiverMain", "Unable to initialize pairing", e)
            android.widget.Toast.makeText(this, "สร้างรหัสจับคู่ไม่สำเร็จ", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun showPairingQr() {
        val value = SecureUdp.ensurePairingUri(this)
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(value, com.google.zxing.BarcodeFormat.QR_CODE, 700, 700)
        val bitmap = android.graphics.Bitmap.createBitmap(matrix.width, matrix.height, android.graphics.Bitmap.Config.RGB_565)
        for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
        val image = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            setPadding(24, 24, 24, 24)
        }
        qrDialog = android.app.AlertDialog.Builder(this)
            .setTitle("สแกนด้วยแอปบนโทรศัพท์")
            .setView(image)
            .setPositiveButton("ปิด", null)
            .show()
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
        try {
            val serviceIntent = Intent(this, UdpReceiverService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Exception) {
            android.util.Log.e("ReceiverMain", "Unable to start receiver service", e)
            android.widget.Toast.makeText(this, "เริ่มระบบรับข้อมูลไม่สำเร็จ: ${e.javaClass.simpleName}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun setupSettings() {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val switchQuickReply = findViewById<CompoundButton>(R.id.switchQuickReply)
        val switchTTS = findViewById<CompoundButton>(R.id.switchTTS)
        val switchTTSMale = findViewById<CompoundButton>(R.id.switchTTSMale)
        val switchGreeting = findViewById<CompoundButton>(R.id.switchGreeting)
        val switchWeatherGreeting = findViewById<CompoundButton>(R.id.switchWeatherGreeting)
        val switchAudioDucking = findViewById<CompoundButton>(R.id.switchAudioDucking)
        val switchAutoReply = findViewById<CompoundButton>(R.id.switchAutoReply)
        val switchMedia = findViewById<CompoundButton>(R.id.switchMedia)
        val switchBattery = findViewById<CompoundButton>(R.id.switchBattery)
        val switchPrivacy = findViewById<CompoundButton>(R.id.switchPrivacy)
        val switchDND = findViewById<CompoundButton>(R.id.switchDND)
        val switchVIP = findViewById<CompoundButton>(R.id.switchVIP)
        val switchETA = findViewById<CompoundButton>(R.id.switchETA)
        val switchFatigue = findViewById<CompoundButton>(R.id.switchFatigue)
        val switchSpeed = findViewById<CompoundButton>(R.id.switchSpeed)
        val btnFindPhone = findViewById<Button>(R.id.btnFindPhone)
        val btnAddGeoReminder = findViewById<Button>(R.id.btnAddGeoReminder)

        btnAddGeoReminder.setOnClickListener {
            showAddGeoReminderDialog()
        }

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
                        val encrypted = SecureUdp.encode(this@MainActivity, json) ?: return@launch
                        val payload = encrypted.toByteArray(Charsets.UTF_8)
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
        switchTTSMale.isChecked = prefs.getBoolean("PREF_TTS_MALE", false)
        switchGreeting.isChecked = prefs.getBoolean("PREF_GREETING", true)
        switchWeatherGreeting.isChecked = prefs.getBoolean("PREF_WEATHER_GREETING", true)
        switchAudioDucking.isChecked = prefs.getBoolean("PREF_AUDIO_DUCKING", true)
        switchAutoReply.isChecked = prefs.getBoolean("PREF_AUTO_REPLY", true)
        switchMedia.isChecked = prefs.getBoolean("PREF_MEDIA", true)
        switchBattery.isChecked = prefs.getBoolean("PREF_BATTERY", true)
        switchPrivacy.isChecked = prefs.getBoolean("PREF_PRIVACY_MODE", false)
        switchDND.isChecked = prefs.getBoolean("PREF_DND", false)
        switchVIP.isChecked = prefs.getBoolean("PREF_VIP_MODE", true)
        switchETA.isChecked = prefs.getBoolean("PREF_SHARE_ETA", true)
        switchFatigue.isChecked = prefs.getBoolean("PREF_FATIGUE_ALERT", true)
        switchSpeed.isChecked = prefs.getBoolean("PREF_SPEED_WARNING", true)

        // Save on change
        val listener = { key: String, isChecked: Boolean ->
            prefs.edit().putBoolean(key, isChecked).apply()
        }
        
        switchQuickReply.setOnCheckedChangeListener { _, c -> listener("PREF_QUICK_REPLY", c) }
        switchTTS.setOnCheckedChangeListener { _, c -> listener("PREF_TTS", c) }
        switchTTSMale.setOnCheckedChangeListener { _, c -> listener("PREF_TTS_MALE", c) }
        switchGreeting.setOnCheckedChangeListener { _, c -> listener("PREF_GREETING", c) }
        switchWeatherGreeting.setOnCheckedChangeListener { _, c -> listener("PREF_WEATHER_GREETING", c) }
        switchAudioDucking.setOnCheckedChangeListener { _, c -> listener("PREF_AUDIO_DUCKING", c) }
        switchAutoReply.setOnCheckedChangeListener { _, c -> listener("PREF_AUTO_REPLY", c) }
        switchMedia.setOnCheckedChangeListener { _, c -> listener("PREF_MEDIA", c) }
        switchBattery.setOnCheckedChangeListener { _, c -> listener("PREF_BATTERY", c) }
        switchPrivacy.setOnCheckedChangeListener { _, c -> listener("PREF_PRIVACY_MODE", c) }
        switchDND.setOnCheckedChangeListener { _, c -> listener("PREF_DND", c) }
        switchVIP.setOnCheckedChangeListener { _, c -> listener("PREF_VIP_MODE", c) }
        switchETA.setOnCheckedChangeListener { _, c -> listener("PREF_SHARE_ETA", c) }
        switchFatigue.setOnCheckedChangeListener { _, c -> listener("PREF_FATIGUE_ALERT", c) }
        switchSpeed.setOnCheckedChangeListener { _, c -> listener("PREF_SPEED_WARNING", c) }

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
        
        val spinnerPopupPosition = findViewById<Spinner>(R.id.spinnerPopupPosition)
        val positions = arrayOf("Top", "Center", "Bottom")
        val posAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, positions)
        posAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPopupPosition.adapter = posAdapter
        
        val currentPosition = prefs.getString("PREF_POPUP_GRAVITY", "Top")
        spinnerPopupPosition.setSelection(positions.indexOf(currentPosition))
        
        spinnerPopupPosition.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                prefs.edit().putString("PREF_POPUP_GRAVITY", positions[position]).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun showAddGeoReminderDialog() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            android.widget.Toast.makeText(this, "กรุณาเปิดสิทธิ์ GPS ก่อนใช้งาน (GRANT LOCATION PERMISSION)", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        val lastLocation = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            
        if (lastLocation == null) {
            android.widget.Toast.makeText(this, "ไม่สามารถหาพิกัด GPS ปัจจุบันได้", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val input = android.widget.EditText(this).apply {
            hint = "เช่น แวะซื้อนมที่โลตัส"
            setPadding(40, 40, 40, 40)
        }
        
        android.app.AlertDialog.Builder(this)
            .setTitle("บันทึกพิกัดแจ้งเตือน")
            .setMessage("พิกัดปัจจุบัน: ${String.format("%.4f, %.4f", lastLocation.latitude, lastLocation.longitude)}\nพิมพ์ข้อความที่ต้องการให้ระบบพูดเตือนเมื่อขับรถมาถึงจุดนี้ในครั้งถัดไป:")
            .setView(input)
            .setPositiveButton("บันทึก") { _, _ ->
                val msg = input.text.toString()
                if (msg.isNotEmpty()) {
                    val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("GEO_REMINDER_LAT", lastLocation.latitude.toString())
                                .putString("GEO_REMINDER_LON", lastLocation.longitude.toString())
                                .putString("GEO_REMINDER_MSG", msg)
                                .putBoolean("GEO_REMINDER_TRIGGERED", false)
                                .apply()
                    android.widget.Toast.makeText(this, "บันทึกเรียบร้อย", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionButtons()
    }

    private fun updatePermissionButtons() {
        val green = android.graphics.Color.parseColor("#16833B")
        val red = android.graphics.Color.parseColor("#D32F2F")
        fun style(id: Int, granted: Boolean, grantedText: String, normalText: String) {
            val button = findViewById<com.google.android.material.button.MaterialButton>(id)
            button.text = if (granted) "✓ $grantedText" else "✕ $normalText"
            val color = if (granted) green else red
            button.setTextColor(color)
            button.strokeColor = android.content.res.ColorStateList.valueOf(color)
        }

        val overlayGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        style(R.id.btnRequestOverlay, overlayGranted, "อนุญาต Overlay แล้ว", "1. อนุญาต Overlay")

        val batteryGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        style(R.id.btnRequestBattery, batteryGranted, "ปิดการจำกัดแบตเตอรี่แล้ว", "2. ปิดการจำกัดแบตเตอรี่")

        val locationGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        style(R.id.btnRequestLocation, locationGranted, "อนุญาต Location แล้ว", "3. อนุญาต Location")

        val allGranted = overlayGranted && batteryGranted && locationGranted
        findViewById<android.view.View>(R.id.cardPermissions)?.visibility = if (allGranted) android.view.View.GONE else android.view.View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        statusHandler.removeCallbacks(statusUpdater)
        try {
            unregisterReceiver(pairingSuccessReceiver)
        } catch (e: Exception) {}
        AppLogger.listener = null
    }
    private fun startWatchdog() {
        val alarmManager = getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = android.content.Intent(this, WatchdogReceiver::class.java)
        
        val pendingIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            android.app.PendingIntent.getBroadcast(this, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE)
        } else {
            android.app.PendingIntent.getBroadcast(this, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        }
        
        val interval = 5L * 60L * 1000L
        alarmManager.setRepeating(
            android.app.AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + interval,
            interval,
            pendingIntent
        )
    }
}
