package com.example.receiverapp

import android.content.Context
import org.json.JSONObject

object PairedDevices {
    private const val PREFS = "AppPrefs"
    private const val KEY_DEVICES = "PAIRED_DEVICES_JSON"

    fun getDevices(context: Context): Map<String, Long> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_DEVICES, "{}") ?: "{}"
        val result = mutableMapOf<String, Long>()
        try {
            val json = JSONObject(jsonStr)
            for (key in json.keys()) {
                result[key] = json.getLong(key)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Migration from old LAST_SENDER_IP
        val oldIp = prefs.getString("LAST_SENDER_IP", null)
        if (oldIp != null && !result.containsKey(oldIp)) {
            val oldSeen = prefs.getLong("LAST_SENDER_SEEN", System.currentTimeMillis())
            result[oldIp] = oldSeen
            saveDevices(context, result)
            prefs.edit().remove("LAST_SENDER_IP").remove("LAST_SENDER_SEEN").apply()
        }
        return result
    }

    fun saveDevices(context: Context, devices: Map<String, Long>) {
        val json = JSONObject()
        for ((ip, seen) in devices) {
            json.put(ip, seen)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICES, json.toString())
            .apply()
    }

    fun addOrUpdateDevice(context: Context, ip: String) {
        val devices = getDevices(context).toMutableMap()
        devices[ip] = System.currentTimeMillis()
        saveDevices(context, devices)
    }

    fun removeDevice(context: Context, ip: String) {
        val devices = getDevices(context).toMutableMap()
        devices.remove(ip)
        saveDevices(context, devices)
    }

    fun getActiveDeviceIp(context: Context): String? {
        val devices = getDevices(context)
        if (devices.isEmpty()) return null
        // Active device is the one with the most recent lastSeen time
        return devices.maxByOrNull { it.value }?.key
    }
}
