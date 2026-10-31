package io.github.rhythmcache.dioxamine.lgremote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.MessageDigest

/** Keeps the TV's bearer client-key encrypted with a key that never leaves Android Keystore. */
internal class LgTvSecureStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("lg_tv_pairing", Context.MODE_PRIVATE)
    private val alias = "dioxamine.lg-tv.client-key.v1"

    @Synchronized
    fun readClientKey(host: String): String? = readEncrypted("key", host)

    @Synchronized
    fun writeClientKey(host: String, clientKey: String) = writeEncrypted("key", host, clientKey)

    @Synchronized
    fun readMac(host: String): String? = preferences.getString(prefName("mac", host), null)

    @Synchronized
    fun writeMac(host: String, mac: String) {
        val normalized = mac.trim().uppercase()
        if (normalized.matches(Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}"))) {
            preferences.edit().putString(prefName("mac", host), normalized).apply()
        }
    }

    @Synchronized
    fun forget(host: String) {
        preferences.edit()
            .remove(prefName("key", host))
            .remove(prefName("mac", host))
            .apply()
    }

    private fun readEncrypted(kind: String, host: String): String? {
        val encoded = preferences.getString(prefName(kind, host), null) ?: return null
        return runCatching {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            require(combined.size > IV_LENGTH)
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val ciphertext = combined.copyOfRange(IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(host.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrElse {
            // A restored app backup may contain ciphertext but not the device-bound Keystore key.
            preferences.edit().remove(prefName(kind, host)).apply()
            null
        }
    }

    private fun writeEncrypted(kind: String, host: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(host.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + ciphertext
        preferences.edit().putString(prefName(kind, host), Base64.encodeToString(combined, Base64.NO_WRAP)).apply()
    }

    private fun prefName(kind: String, host: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(host.toByteArray(Charsets.UTF_8))
        val hostHash = digest.joinToString("") { "%02x".format(it) }
        return "$kind.$hostHash"
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
