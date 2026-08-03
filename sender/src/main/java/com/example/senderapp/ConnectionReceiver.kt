package com.example.senderapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class ConnectionReceiver : BroadcastReceiver() {
    
    private var wasConnected = true

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        
        if (action == android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED) {
            AppLogger.log("Bluetooth Disconnected. Saving parking location...")
            if (isAutoParkEnabled(context)) saveCurrentLocation(context)
        }
        
        if (action == WifiManager.NETWORK_STATE_CHANGED_ACTION) {
            val info = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
            val isConnected = info?.isConnected == true
            
            if (wasConnected && !isConnected) {
                // WiFi Disconnected! Probably turned off the car.
                AppLogger.log("WiFi Disconnected. Saving parking location...")
                if (isAutoParkEnabled(context)) saveCurrentLocation(context)
            } else if (!wasConnected && isConnected) {
                // WiFi Connected!
                AppLogger.log("WiFi Connected. Checking battery...")
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val batteryAlert = context.getSharedPreferences("SenderPrefs", Context.MODE_PRIVATE).getBoolean("BATTERY_ALERT", true)
                if (batteryAlert && level in 1..20) {
                    val payload = JSONObject().apply {
                        put("type", "battery")
                        put("level", level)
                    }.toString()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val socket = DatagramSocket()
                            socket.broadcast = true
                            val encrypted = SecureUdp.encode(context, payload) ?: return@launch
                            val data = encrypted.toByteArray(Charsets.UTF_8)
                            val packet = DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), 8888)
                            socket.send(packet)
                            socket.close()
                            AppLogger.log("🔋 Low battery alert sent on connect: $level%")
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
            
            wasConnected = isConnected
        }
    }

    private fun isAutoParkEnabled(context: Context): Boolean =
        context.getSharedPreferences("SenderPrefs", Context.MODE_PRIVATE).getBoolean("AUTO_PARK", true)

    private fun saveCurrentLocation(context: Context) {
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            val hasFine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasCoarse = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            if (!hasFine && !hasCoarse) {
                AppLogger.log("Missing location permissions to save parking location.")
                return
            }

            // Get last known location first for speed
            val lastLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) 
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            if (lastLocation != null) {
                saveLocationToPrefs(context, lastLocation)
            }

            // Also request a single update just in case
            val locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    saveLocationToPrefs(context, location)
                    locationManager.removeUpdates(this)
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            if (hasFine) {
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, locationListener, null)
            } else if (hasCoarse) {
                locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, locationListener, null)
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveLocationToPrefs(context: Context, location: Location) {
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putFloat("PARK_LAT", location.latitude.toFloat())
            putFloat("PARK_LON", location.longitude.toFloat())
            apply()
        }
        AppLogger.log("📍 Saved parking location")
    }
}
