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

    private const val SCRIPT_PATH = "/data/local/tmp/set_topbar.sh"
    private const val SCRIPT_VERSION = "v2"
    private const val VERSION_FILE = "/data/local/tmp/set_topbar.ver"
    private const val SCRIPT_BASE64 = "IyEvc3lzdGVtL2Jpbi9zaApMT0M9IiQxIgpQUkVGPSIvZGF0YS9kYXRhL2NvbS5kdWR1LmF1dG91aS9zaGFyZWRfcHJlZnMvc2V0dGluZ3MueG1sIgoKaWYgWyAhIC1mICIkUFJFRiIgXTsgdGhlbgogICAgZWNobyAic2V0dGluZ3MueG1sIG5vdCBmb3VuZCIKICAgIGV4aXQgMQpmaQoKaWYgWyAteiAiJExPQyIgXTsgdGhlbgogICAgTkVXX0VOVFJZPSc8c3RyaW5nIG5hbWU9IlNEQVRBX0NFTlRFUl9EQVRFX0ZST01BVCI+PC9zdHJpbmc+JwplbHNlCiAgICBORVdfRU5UUlk9IjxzdHJpbmcgbmFtZT1cIlNEQVRBX0NFTlRFUl9EQVRFX0ZST01BVFwiPickTE9DJzwvc3RyaW5nPiIKZmkKCmF3ayAtdiByZXA9IiRORVdfRU5UUlkiICcKQkVHSU4geyBoYXNfbG9jPTA7IGhhc19wcm9tcHQ9MCB9Ci88c3RyaW5nIG5hbWU9IlNEQVRBX0NFTlRFUl9EQVRFX0ZST01BVCIvIHsgaGFzX2xvYz0xOyBwcmludCAiICAgICIgcmVwOyBuZXh0IH0KLzxpbnQgbmFtZT0iU0RBVEFfUFJPTVBUX1VTRV9UWVBFIi8geyBoYXNfcHJvbXB0PTE7IHByaW50ICIgICAgPGludCBuYW1lPVwiU0RBVEFfUFJPTVBUX1VTRV9UWVBFXCIgdmFsdWU9XCIxXCIgLz4iOyBuZXh0IH0KLzxcL21hcD4vIHsKICAgIGlmICghaGFzX2xvYykgcHJpbnQgIiAgICAiIHJlcDsKICAgIGlmICghaGFzX3Byb21wdCkgcHJpbnQgIiAgICA8aW50IG5hbWU9XCJTREFUQV9QUk9NUFRfVVNFX1RZUEVcIiB2YWx1ZT1cIjFcIiAvPiI7CiAgICBwcmludDsKICAgIG5leHQKfQp7IHByaW50IH0KJyAiJFBSRUYiID4gL2RhdGEvbG9jYWwvdG1wL3NldHRpbmdzX3RtcC54bWwKCmlmIFsgLXMgL2RhdGEvbG9jYWwvdG1wL3NldHRpbmdzX3RtcC54bWwgXTsgdGhlbgogICAgY3AgL2RhdGEvbG9jYWwvdG1wL3NldHRpbmdzX3RtcC54bWwgIiRQUkVGIgogICAgY2hvd24gc3lzdGVtOnN5c3RlbSAiJFBSRUYiCiAgICBjaG1vZCA2NjAgIiRQUkVGIgogICAgYW0gZm9yY2Utc3RvcCBjb20uZHVkdS5hdXRvdWkKICAgIGFtIHN0YXJ0IC1uIGNvbS5kdWR1LmF1dG91aS8udWkuYWN0aXZpdHkubGF1bmNoZXIuTGF1bmNoZXJBY3Rpdml0eQogICAgZWNobyAiVVBEQVRFRF9BTkRfUkVMT0FERUQiCmVsc2UKICAgIGVjaG8gIkVSUk9SOiBnZW5lcmF0ZWQgZmlsZSBlbXB0eSIKICAgIGV4aXQgMQpmaQo="

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

    fun ensureScriptInstalled() {
        try {
            val check = executeCommandAsRoot("[ -s $SCRIPT_PATH ] && [ -f $VERSION_FILE ] && cat $VERSION_FILE || echo NO")
            if (check.trim() != SCRIPT_VERSION) {
                AppLogger.log("Installing $SCRIPT_PATH ($SCRIPT_VERSION) via ADB root")
                executeCommandAsRoot("echo '$SCRIPT_BASE64' | base64 -d > $SCRIPT_PATH && chmod 755 $SCRIPT_PATH && echo '$SCRIPT_VERSION' > $VERSION_FILE")
            }
        } catch (e: Exception) {
            AppLogger.log("ensureScriptInstalled error: ${e.message}")
        }
    }

    fun updateTopBarLocation(context: Context, locationText: String, force: Boolean = false) {
        val trimmed = locationText.trim()
        if (!force && trimmed == lastUpdatedLocation) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                ensureScriptInstalled()
                val cleanText = trimmed.replace("'", "").replace("\"", "")
                AppLogger.log("DuduTopBarBridge: setting location -> '$cleanText'")

                val res = executeCommandAsRoot("$SCRIPT_PATH '$cleanText'")
                AppLogger.log("DuduTopBarBridge result: $res")

                if (res.contains("UPDATED_AND_RELOADED")) {
                    lastUpdatedLocation = trimmed
                    context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString(PREF_DUDU_TOPBAR_LAST_TEXT, trimmed)
                        .apply()
                }
            } catch (e: Exception) {
                AppLogger.log("updateTopBarLocation failed: ${e.message}")
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
