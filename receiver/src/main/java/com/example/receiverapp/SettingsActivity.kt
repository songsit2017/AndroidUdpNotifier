package com.example.receiverapp

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Setup Back Button
        findViewById<View>(R.id.btnBack)?.setOnClickListener {
            finish()
        }

        setupSettings()
        populatePairedDevices()
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

        // Load saved or defaults
        switchQuickReply?.isChecked = prefs.getBoolean("PREF_QUICK_REPLY", true)
        switchTTS?.isChecked = prefs.getBoolean("PREF_TTS", true)
        switchTTSMale?.isChecked = prefs.getBoolean("PREF_TTS_MALE", false)
        switchGreeting?.isChecked = prefs.getBoolean("PREF_GREETING", true)
        switchWeatherGreeting?.isChecked = prefs.getBoolean("PREF_WEATHER_GREETING", true)
        switchAudioDucking?.isChecked = prefs.getBoolean("PREF_AUDIO_DUCKING", true)
        switchAutoReply?.isChecked = prefs.getBoolean("PREF_AUTO_REPLY", true)
        switchMedia?.isChecked = prefs.getBoolean("PREF_MEDIA", true)
        switchBattery?.isChecked = prefs.getBoolean("PREF_BATTERY", true)
        switchPrivacy?.isChecked = prefs.getBoolean("PREF_PRIVACY_MODE", false)
        switchDND?.isChecked = prefs.getBoolean("PREF_DND", false)
        switchVIP?.isChecked = prefs.getBoolean("PREF_VIP_MODE", true)
        switchETA?.isChecked = prefs.getBoolean("PREF_SHARE_ETA", true)
        switchFatigue?.isChecked = prefs.getBoolean("PREF_FATIGUE_ALERT", true)
        switchSpeed?.isChecked = prefs.getBoolean("PREF_SPEED_WARNING", true)

        // Save on change
        val listener = { key: String, isChecked: Boolean ->
            prefs.edit().putBoolean(key, isChecked).apply()
        }
        
        switchQuickReply?.setOnCheckedChangeListener { _, c -> listener("PREF_QUICK_REPLY", c) }
        switchTTS?.setOnCheckedChangeListener { _, c -> listener("PREF_TTS", c) }
        switchTTSMale?.setOnCheckedChangeListener { _, c -> listener("PREF_TTS_MALE", c) }
        switchGreeting?.setOnCheckedChangeListener { _, c -> listener("PREF_GREETING", c) }
        switchWeatherGreeting?.setOnCheckedChangeListener { _, c -> listener("PREF_WEATHER_GREETING", c) }
        switchAudioDucking?.setOnCheckedChangeListener { _, c -> listener("PREF_AUDIO_DUCKING", c) }
        switchAutoReply?.setOnCheckedChangeListener { _, c -> listener("PREF_AUTO_REPLY", c) }
        switchMedia?.setOnCheckedChangeListener { _, c -> listener("PREF_MEDIA", c) }
        switchBattery?.setOnCheckedChangeListener { _, c -> listener("PREF_BATTERY", c) }
        switchPrivacy?.setOnCheckedChangeListener { _, c -> listener("PREF_PRIVACY_MODE", c) }
        switchDND?.setOnCheckedChangeListener { _, c -> listener("PREF_DND", c) }
        switchVIP?.setOnCheckedChangeListener { _, c -> listener("PREF_VIP_MODE", c) }
        switchETA?.setOnCheckedChangeListener { _, c -> listener("PREF_SHARE_ETA", c) }
        switchFatigue?.setOnCheckedChangeListener { _, c -> listener("PREF_FATIGUE_ALERT", c) }
        switchSpeed?.setOnCheckedChangeListener { _, c -> listener("PREF_SPEED_WARNING", c) }

        val spinnerTheme = findViewById<Spinner>(R.id.spinnerTheme)
        if (spinnerTheme != null) {
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
        
        val spinnerPopupPosition = findViewById<Spinner>(R.id.spinnerPopupPosition)
        if (spinnerPopupPosition != null) {
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
            
            val editGeoLat = findViewById<android.widget.EditText>(R.id.editGeoLat)
            val editGeoLon = findViewById<android.widget.EditText>(R.id.editGeoLon)
            val editGeoMsg = findViewById<android.widget.EditText>(R.id.editGeoMsg)
            val btnSaveGeo = findViewById<android.widget.Button>(R.id.btnSaveGeo)

            editGeoLat?.setText(prefs.getString("GEO_REMINDER_LAT", ""))
            editGeoLon?.setText(prefs.getString("GEO_REMINDER_LON", ""))
            editGeoMsg?.setText(prefs.getString("GEO_REMINDER_MSG", ""))

            btnSaveGeo?.setOnClickListener {
                prefs.edit().apply {
                    putString("GEO_REMINDER_LAT", editGeoLat?.text.toString())
                    putString("GEO_REMINDER_LON", editGeoLon?.text.toString())
                    putString("GEO_REMINDER_MSG", editGeoMsg?.text.toString())
                    putBoolean("GEO_REMINDER_TRIGGERED", false)
                    apply()
                }
                android.widget.Toast.makeText(this@SettingsActivity, "บันทึกพิกัดเตือนความจำสำเร็จ!", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun populatePairedDevices() {
        val container = findViewById<android.widget.LinearLayout>(R.id.containerPairedDevices) ?: return
        container.removeAllViews()
        val devices = PairedDevices.getDevices(this)
        
        if (devices.isEmpty()) {
            val tv = android.widget.TextView(this).apply {
                text = "ยังไม่มีอุปกรณ์ที่จับคู่"
                setTextColor(android.graphics.Color.GRAY)
                setPadding(0, 8, 0, 8)
            }
            container.addView(tv)
            return
        }

        for ((ip, lastSeen) in devices) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 16, 0, 16)
            }

            val textInfo = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            
            val tvIp = android.widget.TextView(this).apply {
                text = "Device IP: $ip"
                textSize = 16f
            }
            
            val ageSec = (System.currentTimeMillis() - lastSeen) / 1000
            val ageMin = ageSec / 60
            val ageStr = if (ageMin > 0) "${ageMin}m ago" else "${ageSec}s ago"
            val tvSeen = android.widget.TextView(this).apply {
                text = "Last seen: $ageStr"
                textSize = 12f
                setTextColor(android.graphics.Color.GRAY)
            }

            textInfo.addView(tvIp)
            textInfo.addView(tvSeen)

            val btnDelete = android.widget.ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                setBackgroundResource(android.R.color.transparent)
                setColorFilter(android.graphics.Color.parseColor("#FF3B30"))
                setPadding(16, 16, 16, 16)
                setOnClickListener {
                    PairedDevices.removeDevice(this@SettingsActivity, ip)
                    populatePairedDevices() // refresh
                }
            }

            row.addView(textInfo)
            row.addView(btnDelete)
            container.addView(row)
        }
    }
}
