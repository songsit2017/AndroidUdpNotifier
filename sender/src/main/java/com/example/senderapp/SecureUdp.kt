package com.example.senderapp

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator

/**
 * Authenticated UDP envelope. Both devices must be configured with the same
 * high-entropy pairing code (at least 12 characters).
 */
object SecureUdp {
    private const val PREFS = "SecurityPrefs"
    private const val PAIRING_CODE = "pairing_code_encrypted"
    private const val STORAGE_KEY_ALIAS = "udp_pairing_storage_v1"
    private const val VERSION = 1
    private const val MAX_CLOCK_SKEW_MS = 120_000L
    private val random = SecureRandom()
    private val seenNonces = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 512
        }
    )
    @Volatile private var cachedCodeDigest: String? = null
    @Volatile private var cachedKey: SecretKey? = null

    fun hasPairingCode(context: Context): Boolean =
        readCode(context)?.length?.let { it >= 12 } == true

    fun setPairingCode(context: Context, code: String): Boolean {
        val normalized = code.trim()
        if (normalized.length < 12) return false
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, storageKey())
        val encrypted = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PAIRING_CODE, stored).apply()
        cachedCodeDigest = null
        cachedKey = null
        seenNonces.clear()
        return true
    }

    fun importPairingUri(context: Context, value: String): Boolean {
        val uri = android.net.Uri.parse(value)
        if (uri.scheme != "audp" || uri.host != "pair") return false
        return setPairingCode(context, uri.getQueryParameter("code") ?: return false)
    }

    fun encode(context: Context, plaintext: String): String? {
        val key = key(context) ?: return null
        val timestamp = System.currentTimeMillis()
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD("$VERSION|$timestamp".toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return JSONObject().apply {
            put("v", VERSION)
            put("ts", timestamp)
            put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
            put("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }.toString()
    }

    fun decode(context: Context, envelope: String): String? {
        return try {
            val json = JSONObject(envelope)
            val version = json.getInt("v")
            val timestamp = json.getLong("ts")
            if (version != VERSION || kotlin.math.abs(System.currentTimeMillis() - timestamp) > MAX_CLOCK_SKEW_MS) return null
            val nonceText = json.getString("nonce")
            synchronized(seenNonces) {
                if (seenNonces.containsKey(nonceText)) return null
            }
            val key = key(context) ?: return null
            val nonce = Base64.decode(nonceText, Base64.NO_WRAP)
            if (nonce.size != 12) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD("$version|$timestamp".toByteArray(Charsets.UTF_8))
            val plaintext = cipher.doFinal(Base64.decode(json.getString("data"), Base64.NO_WRAP))
            synchronized(seenNonces) { seenNonces[nonceText] = timestamp }
            String(plaintext, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun key(context: Context): SecretKey? {
        val code = readCode(context)?.takeIf { it.length >= 12 } ?: return null
        val digest = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(code.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
        if (digest == cachedCodeDigest) return cachedKey
        synchronized(this) {
            if (digest != cachedCodeDigest) {
                val spec = PBEKeySpec(code.toCharArray(), "AndroidUdpNotifier-v1".toByteArray(), 120_000, 256)
                cachedKey = SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded, "AES")
                spec.clearPassword()
                cachedCodeDigest = digest
            }
            return cachedKey
        }
    }

    private fun readCode(context: Context): String? { return try {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PAIRING_CODE, null) ?: return null
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        if (bytes.size <= 12) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, storageKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    } catch (_: Exception) { null } }

    private fun storageKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(STORAGE_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(STORAGE_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
