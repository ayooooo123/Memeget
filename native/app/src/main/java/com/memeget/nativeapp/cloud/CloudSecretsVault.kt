package com.memeget.nativeapp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted vault for cloud credentials using AES-256-GCM.
 * The encryption key stays in AndroidKeyStore. Keystore failures fail closed;
 * there is no file-backed key that would make ciphertext effectively plaintext.
 * Plaintext keys are never logged, never exposed to settings, and never stored in SQLite.
 */
internal class CloudSecretsVault(
    private val storageDir: File,
) {
    constructor(context: Context) : this(File(context.filesDir, "cloud_vault"))

    private val secretsFile = File(storageDir, "secrets.enc")

    init {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
    }

    @Synchronized
    fun saveSecrets(apiKey: String?, judgeKey: String?, primaryBinding: String, captionBinding: String) {
        val json = loadJson() ?: JSONObject()
        if (apiKey != null) {
            require(apiKey.length <= 8192)
            json.put("primaryBinding", primaryBinding)
            if (apiKey.isBlank()) json.remove("apiKey") else json.put("apiKey", apiKey.trim())
        }
        if (judgeKey != null) {
            require(judgeKey.length <= 8192)
            json.put("captionBinding", captionBinding)
            if (judgeKey.isBlank()) json.remove("judgeKey") else json.put("judgeKey", judgeKey.trim())
        }

        val plainBytes = json.toString().toByteArray(Charsets.UTF_8)
        val encrypted = encryptBytes(plainBytes)
        writeCloudAtomic(secretsFile, encrypted)
    }

    @Synchronized
    fun getApiKey(binding: String): String? {
        val json = loadJson() ?: return null
        if (json.optString("primaryBinding") != binding) return null
        val key = json.optString("apiKey", "")
        return key.ifBlank { null }
    }

    @Synchronized
    fun getJudgeKey(binding: String): String? {
        val json = loadJson() ?: return null
        if (json.optString("captionBinding") != binding) return null
        val key = json.optString("judgeKey", "")
        return key.ifBlank { null }
    }

    @Synchronized
    fun clear() {
        if (secretsFile.exists()) {
            secretsFile.delete()
        }
    }

    private fun loadJson(): JSONObject? {
        if (!secretsFile.exists() || secretsFile.length() == 0L) return null
        require(secretsFile.length() <= 32_768) { "Invalid encrypted credential file" }
        return try {
            val encBytes = secretsFile.readBytes()
            val plainBytes = decryptBytes(encBytes) ?: return null
            JSONObject(String(plainBytes, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun encryptBytes(plain: ByteArray): ByteArray {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)

        // Wire format: [1 byte version][1 byte IV len][IV bytes][ciphertext + tag]
        val result = ByteArray(2 + iv.size + ciphertext.size)
        result[0] = VERSION
        result[1] = iv.size.toByte()
        System.arraycopy(iv, 0, result, 2, iv.size)
        System.arraycopy(ciphertext, 0, result, 2 + iv.size, ciphertext.size)
        return result
    }

    private fun decryptBytes(encrypted: ByteArray): ByteArray? {
        if (encrypted.size < 4) return null
        val version = encrypted[0]
        if (version != VERSION) return null
        val ivLen = encrypted[1].toInt() and 0xFF
        if (encrypted.size < 2 + ivLen + 16) return null // At least IV + GCM tag

        val iv = ByteArray(ivLen)
        System.arraycopy(encrypted, 2, iv, 0, ivLen)
        val cipherLen = encrypted.size - 2 - ivLen
        val ciphertext = ByteArray(cipherLen)
        System.arraycopy(encrypted, 2 + ivLen, ciphertext, 0, cipherLen)

        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateSecretKey(): SecretKey = getOrGenerateAndroidKeyStoreKey()

    private fun getOrGenerateAndroidKeyStoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER)
        keyStore.load(null)
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return entry.secretKey
            }
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE_PROVIDER,
        )
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }


    companion object {
        private const val VERSION: Byte = 1
        private const val KEY_ALIAS = "memeget_cloud_aes_master"
        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
