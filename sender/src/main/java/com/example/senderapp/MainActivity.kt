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
