package com.example.receiverapp

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

object DuduTopBarBridge {
    const val PREF_DUDU_TOPBAR_LOCATION_ENABLED = "PREF_DUDU_TOPBAR_LOCATION_ENABLED"
    const val PREF_DUDU_TOPBAR_LAST_TEXT = "PREF_DUDU_TOPBAR_LAST_TEXT"
    const val PREF_DUDU_OBD_MAC = "PREF_DUDU_OBD_MAC"

    private const val A_CNXN = 0x4e584e43
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_CLSE = 0x45534c43
    private const val A_WRTE = 0x45545257
    private const val A_VERSION = 0x01000000
    private const val MAX_PAYLOAD = 1048576

    private const val ADB_HOST = "127.0.0.1"
    private const val ADB_PORT = 5555

    private const val SCRIPT_PATH = "/data/local/tmp/update_dudu_topbar_location.js"
    private val locationLock = Any()


    @Volatile
    private var lastUpdatedLocation: String? = null

    private data class AdbHeader(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val dataLength: Int,
        val crc32: Int,
        val magic: Int
    )

    private fun buildPacket(command: Int, arg0: Int, arg1: Int, data: ByteArray): ByteArray {
        val bb = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0
        for (b in data) {
            sum += (b.toInt() and 0xFF)
        }
        bb.putInt(command)
        bb.putInt(arg0)
        bb.putInt(arg1)
        bb.putInt(data.size)
        bb.putInt(sum)
        bb.putInt(command xor -0x1)
        bb.put(data)
        return bb.array()
    }

    private fun readHeader(input: DataInputStream): AdbHeader {
        val bytes = ByteArray(24)
        input.readFully(bytes)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return AdbHeader(
            command = bb.int,
            arg0 = bb.int,
            arg1 = bb.int,
            dataLength = bb.int,
            crc32 = bb.int,
            magic = bb.int
        )
    }

    private fun openAdbConnection(): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress(ADB_HOST, ADB_PORT), 4000)
        socket.soTimeout = 5000
        val out = socket.getOutputStream()
        val inStream = DataInputStream(socket.getInputStream())

        val cnxnBody = "host::\u0000".toByteArray(Charsets.UTF_8)
        out.write(buildPacket(A_CNXN, A_VERSION, MAX_PAYLOAD, cnxnBody))
        out.flush()

        val respHeader = readHeader(inStream)
        if (respHeader.dataLength > 0) {
            val payload = ByteArray(respHeader.dataLength)
            inStream.readFully(payload)
        }
        return socket
    }

    @Synchronized
    fun executeCommandAsRoot(cmd: String): String {
        var isRoot = false
        try {
            val s = openAdbConnection()
            val out = s.getOutputStream()
            val inStream = DataInputStream(s.getInputStream())

            val idCmd = "shell:id\u0000".toByteArray(Charsets.UTF_8)
            out.write(buildPacket(A_OPEN, 1, 0, idCmd))
            out.flush()

            val sb = StringBuilder()
            while (true) {
                val h = try { readHeader(inStream) } catch (_: Exception) { break }
                if (h.command == A_WRTE) {
                    val data = ByteArray(h.dataLength)
                    inStream.readFully(data)
                    sb.append(String(data, Charsets.UTF_8))
                    out.write(buildPacket(A_OKAY, h.arg1, h.arg0, ByteArray(0)))
                    out.flush()
                } else if (h.command == A_CLSE) {
                    break
                }
            }
            s.close()
            if (sb.contains("uid=0")) {
                isRoot = true
            }
        } catch (e: Exception) {
            AppLogger.log("Adb id check warning: ${e.message}")
        }

        if (!isRoot) {
            try {
                val s = openAdbConnection()
                val out = s.getOutputStream()
                val rootCmd = "root:\u0000".toByteArray(Charsets.UTF_8)
                out.write(buildPacket(A_OPEN, 1, 0, rootCmd))
                out.flush()
                Thread.sleep(1500)
                s.close()
            } catch (e: Exception) {
                AppLogger.log("Adb request root warning: ${e.message}")
            }
        }

        val s = openAdbConnection()
        val out = s.getOutputStream()
        val inStream = DataInputStream(s.getInputStream())

        val targetCmd = "shell:$cmd\u0000".toByteArray(Charsets.UTF_8)
        out.write(buildPacket(A_OPEN, 1, 0, targetCmd))
        out.flush()

        val result = StringBuilder()
        while (true) {
            val h = try { readHeader(inStream) } catch (_: Exception) { break }
            if (h.command == A_WRTE) {
                val data = ByteArray(h.dataLength)
                inStream.readFully(data)
                result.append(String(data, Charsets.UTF_8))
                out.write(buildPacket(A_OKAY, h.arg1, h.arg0, ByteArray(0)))
                out.flush()
            } else if (h.command == A_CLSE) {
                break
            }
        }
        s.close()
        return result.toString().trim()
    }

    fun locationLabel(road: String, subDistrict: String): String =
        road.trim().ifEmpty { subDistrict.trim().takeIf { it.isNotEmpty() }?.let { "ตำบล $it" }.orEmpty() }

    fun updateTopBarLocation(context: Context, locationText: String, force: Boolean = false) {
        val trimmed = locationText.trim()
        if (!force && trimmed == lastUpdatedLocation) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            synchronized(locationLock) {
                try {
                    val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                    if (!force && (!prefs.getBoolean(PREF_DUDU_TOPBAR_LOCATION_ENABLED, true) ||
                            trimmed == lastUpdatedLocation)) return@synchronized
                    val metrics = context.resources.displayMetrics
                    val paint = android.text.TextPaint().apply { textSize = metrics.widthPixels / 60f }
                    val cleaned = trimmed.filterNot { it.isISOControl() || it == '\'' }
                    val label = android.text.TextUtils.ellipsize(
                        cleaned, paint, metrics.widthPixels * .14f, android.text.TextUtils.TruncateAt.END
                    ).toString()
                    val legacy = executeCommandAsRoot(
                        "cat /data/local/tmp/dudu-topbar-original-date 2>/dev/null"
                    ).takeIf { it.startsWith("<string name=\"SDATA_DATE_FROMAT\">") }.orEmpty()
                    val script = context.assets.open("update_dudu_topbar_location.js").bufferedReader().use {
                        it.readText()
                            .replace("ORIGINAL_DATE_XML", org.json.JSONObject.quote(legacy))
                            .replace("LOCATION_LABEL", org.json.JSONObject.quote(label))
                    }
                    val encoded = android.util.Base64.encodeToString(
                        script.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
                    )
                    val result = executeCommandAsRoot(
                        "test -x /data/local/tmp/dudu-frida-inject && " +
                            "echo '$encoded' | base64 -d > $SCRIPT_PATH && " +
                            "timeout 3 /data/local/tmp/dudu-frida-inject -p " +
                            "\"$(pidof -s com.dudu.autoui)\" -s $SCRIPT_PATH; rm -f $SCRIPT_PATH"
                    )
                    if (result.lineSequence().any { it == "DUDU_TOPBAR_UPDATED" }) {
                        lastUpdatedLocation = trimmed
                        prefs.edit().putString(PREF_DUDU_TOPBAR_LAST_TEXT, trimmed).apply()
                    } else {
                        AppLogger.log("DUDU TopBar live update unavailable")
                    }
                } catch (e: Exception) {
                    AppLogger.log("updateTopBarLocation failed: ${e.message}")
                }
            }
        }
    }

    fun configureObdAddress(macAddress: String) {
        val cleanMac = macAddress.trim()
        if (cleanMac.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefPath = "/data/data/com.dudu.autoui/shared_prefs/settings.xml"
                val cmd = "awk -v mac=\"$cleanMac\" '" +
                        "/<string name=\\\"SDATA_OBD_ADDRESS\\\"/ { found=1; print \"    <string name=\\\"SDATA_OBD_ADDRESS\\\">\" mac \"</string>\"; next } " +
                        "{ print } " +
                        "END { if (!found) print \"    <string name=\\\"SDATA_OBD_ADDRESS\\\">\" mac \"</string>\" }' " +
                        "$prefPath > /data/local/tmp/settings_obd.xml && " +
                        "cp /data/local/tmp/settings_obd.xml $prefPath && " +
                        "chown system:system $prefPath && chmod 660 $prefPath && " +
                        "am force-stop com.dudu.autoui && " +
                        "am start -n com.dudu.autoui/.ui.activity.launcher.LauncherActivity"
                val res = executeCommandAsRoot(cmd)
                AppLogger.log("configureObdAddress result: $res")
            } catch (e: Exception) {
                AppLogger.log("configureObdAddress failed: ${e.message}")
            }
        }
    }
}
