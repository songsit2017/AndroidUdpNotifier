package com.example.duducompat

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

class DuduCompatActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dudu_compat)

        findViewById<Button>(R.id.btnGrantNotification).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) updateStatus()
    }

    private fun updateStatus() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        findViewById<TextView>(R.id.tvStatus).text = if (granted) {
            "✓ พร้อมส่งการแจ้งเตือนเข้า UI ของ DUDU\n\nเปิด DUDU Notification Bridge ตัวหลักไว้ แล้วทดสอบจากปุ่ม ‘ทดสอบ POPUP และเสียง’"
        } else {
            "ยังไม่ได้อนุญาต Notification\n\nกดปุ่มด้านล่างหนึ่งครั้งเพื่อให้ DUDU แสดงการ์ดแจ้งเตือน"
        }
        findViewById<Button>(R.id.btnGrantNotification).isEnabled = !granted
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 501
    }
}
