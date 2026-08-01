package com.example.senderapp

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLog = findViewById(R.id.tvLog)

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

        AppLogger.listener = { message ->
            val currentText = tvLog.text.toString()
            val newText = "$message\n\n$currentText"
            // limit to 1000 chars to avoid memory issues
            tvLog.text = if (newText.length > 2000) newText.substring(0, 2000) else newText
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.listener = null
    }
}
