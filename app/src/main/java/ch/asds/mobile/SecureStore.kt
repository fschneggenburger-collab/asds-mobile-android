package ch.asds.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStore(context: Context) {
    private val preferences = context.getSharedPreferences("asds_secure_store", Context.MODE_PRIVATE)
    private val alias = "asds_mobile_local_data_v1"

    @Synchronized
    fun put(key: String, value: String) {
        preferences.edit().putString(key, encrypt(value)).apply()
    }

    @Synchronized
    fun get(key: String, fallback: String = ""): String {
        val encrypted = preferences.getString(key, null) ?: return fallback
        return try {
            decrypt(encrypted)
        } catch (_: Exception) {
            fallback
        }
    }

    @Synchronized
    fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val result = ByteArray(cipher.iv.size + encrypted.size)
        System.arraycopy(cipher.iv, 0, result, 0, cipher.iv.size)
        System.arraycopy(encrypted, 0, result, cipher.iv.size, encrypted.size)
        return Base64.encodeToString(result, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 12) { "Encrypted value is too short" }
        val iv = bytes.copyOfRange(0, 12)
        val encrypted = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }
}
