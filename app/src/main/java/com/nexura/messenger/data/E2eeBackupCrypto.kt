package com.nexura.messenger.data

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side envelope encryption for cloud E2EE state backups.
 *
 * Recovery codes contain 256 bits of random entropy. The code is never sent to
 * Supabase; only the AES-256-GCM envelope is uploaded. User ID and format version
 * are authenticated as AAD to prevent cross-account/cross-format substitution.
 */
object E2eeBackupCrypto {
    private const val VERSION = 1
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32
    private const val TAG_BITS = 128
    private const val MAX_PLAINTEXT_BYTES = 12 * 1024 * 1024
    private const val AAD_PREFIX = "nexura-e2ee-cloud-backup:v1:"

    fun generateRecoveryCode(): String {
        val key = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun encrypt(userId: String, plaintext: ByteArray, recoveryCode: String): String {
        require(userId.isNotBlank()) { "Invalid backup account" }
        require(plaintext.isNotEmpty() && plaintext.size <= MAX_PLAINTEXT_BYTES) {
            "E2EE backup size is invalid"
        }
        val key = decodeRecoveryCode(recoveryCode)
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad(userId))
        val ciphertext = cipher.doFinal(plaintext)
        return JSONObject()
            .put("version", VERSION)
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .toString()
    }

    fun decrypt(userId: String, envelope: String, recoveryCode: String): ByteArray {
        require(userId.isNotBlank()) { "Invalid backup account" }
        require(envelope.length <= MAX_PLAINTEXT_BYTES * 2) { "E2EE backup envelope is too large" }
        val json = JSONObject(envelope)
        require(json.optInt("version", -1) == VERSION) { "Unsupported E2EE backup version" }
        val iv = Base64.decode(json.getString("iv"), Base64.NO_WRAP)
        val ciphertext = Base64.decode(json.getString("ciphertext"), Base64.NO_WRAP)
        require(iv.size == IV_BYTES && ciphertext.size in 16..(MAX_PLAINTEXT_BYTES + 16)) {
            "Invalid E2EE backup envelope"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(decodeRecoveryCode(recoveryCode), "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )
        cipher.updateAAD(aad(userId))
        return cipher.doFinal(ciphertext)
    }

    private fun decodeRecoveryCode(value: String): ByteArray {
        val decoded = try {
            Base64.decode(value.trim(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Неверный ключ восстановления", error)
        }
        require(decoded.size == KEY_BYTES) { "Ключ восстановления должен содержать 256 бит" }
        return decoded
    }

    private fun aad(userId: String): ByteArray =
        (AAD_PREFIX + userId).toByteArray(Charsets.UTF_8)
}
