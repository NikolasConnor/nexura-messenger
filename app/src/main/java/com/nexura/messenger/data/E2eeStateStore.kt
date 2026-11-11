package com.nexura.messenger.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted persistence for opaque E2EE protocol state (for example, an Olm pickle).
 *
 * The AES-256 key is non-exportable and managed by Android Keystore. The protocol
 * state itself must already be serialized by the crypto layer; never pass plaintext
 * chat messages or raw private keys to this store.
 *
 * This store is used by E2eeClient for device accounts, Olm sessions, and
 * related local protocol state. Rust keeps only bounded in-memory working copies;
 * every state transition must be committed here before ciphertext is sent or
 * plaintext is exposed to the caller.
 */
class E2eeStateStore(context: Context, userNamespace: String) {
    // Android Keystore key handles are non-exportable; cache the handle, not key bytes.
    // This avoids reopening AndroidKeyStore for every pickle encrypt/decrypt operation.
    @Volatile private var cachedKey: SecretKey? = null
    private val keyAlias: String
    private val preferences: android.content.SharedPreferences

    init {
        require(userNamespace.isNotBlank()) { "userNamespace must not be blank" }
        val suffix = MessageDigest.getInstance("SHA-256")
            .digest(userNamespace.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
        keyAlias = "$KEY_ALIAS_PREFIX$suffix"
        preferences = context.applicationContext.getSharedPreferences(
            "$PREFS_PREFIX$suffix",
            Context.MODE_PRIVATE
        )
    }

    @Synchronized
    @Throws(GeneralSecurityException::class)
    fun save(sessionId: String, serializedState: ByteArray) {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(serializedState.isNotEmpty()) { "serializedState must not be empty" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad(sessionId))
        val encrypted = cipher.doFinal(serializedState)
        val encoded = VERSION + ":" +
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)

        check(preferences.edit().putString(storageKey(sessionId), encoded).commit()) {
            "Unable to persist encrypted E2EE state"
        }
    }

    /**
     * Encrypts a group of related protocol states and commits them in one
     * SharedPreferences transaction. Used to persist a ratchet advance and its
     * corresponding local message cache/outbox record without a partial commit.
     */
    @Synchronized
    @Throws(GeneralSecurityException::class)
    fun saveBatch(states: Map<String, ByteArray>) {
        require(states.isNotEmpty()) { "states must not be empty" }
        val editor = preferences.edit()
        for ((sessionId, serializedState) in states) {
            require(sessionId.isNotBlank()) { "sessionId must not be blank" }
            require(serializedState.isNotEmpty()) { "serializedState must not be empty" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(aad(sessionId))
            val encrypted = cipher.doFinal(serializedState)
            val encoded = VERSION + ":" +
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            editor.putString(storageKey(sessionId), encoded)
        }
        check(editor.commit()) { "Unable to persist encrypted E2EE state batch" }
    }

    @Synchronized
    @Throws(GeneralSecurityException::class)
    fun load(sessionId: String): ByteArray? {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val encoded = preferences.getString(storageKey(sessionId), null) ?: return null
        val parts = encoded.split(":", limit = 3)
        if (parts.size != 3 || parts[0] != VERSION) {
            throw GeneralSecurityException("Unsupported encrypted E2EE state format")
        }

        val iv = try {
            Base64.decode(parts[1], Base64.NO_WRAP)
        } catch (error: IllegalArgumentException) {
            throw GeneralSecurityException("Invalid E2EE state IV", error)
        }
        val ciphertext = try {
            Base64.decode(parts[2], Base64.NO_WRAP)
        } catch (error: IllegalArgumentException) {
            throw GeneralSecurityException("Invalid E2EE state ciphertext", error)
        }
        if (iv.size != GCM_IV_BYTES || ciphertext.size < GCM_TAG_BITS / 8) {
            throw GeneralSecurityException("Invalid encrypted E2EE state length")
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad(sessionId))
        return cipher.doFinal(ciphertext)
    }

    @Synchronized
    fun delete(sessionId: String) {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        check(preferences.edit().remove(storageKey(sessionId)).commit()) {
            "Unable to delete encrypted E2EE state"
        }
    }

    /** Removes all saved protocol states and the Keystore wrapping key. */
    @Synchronized
    @Throws(GeneralSecurityException::class)
    fun clearAll() {
        check(preferences.edit().clear().commit()) { "Unable to clear encrypted E2EE states" }
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        cachedKey = null
    }

    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let {
            cachedKey = it
            return it
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey().also { cachedKey = it }
    }

    private fun aad(sessionId: String): ByteArray =
        "$AAD_PREFIX$sessionId".toByteArray(Charsets.UTF_8)

    private fun storageKey(sessionId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sessionId.toByteArray(Charsets.UTF_8))
        return "state_" + digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PREFS_PREFIX = "nexura_e2ee_state_v1_"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS_PREFIX = "nexura_e2ee_state_aes_v1_"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION = "v1"
        const val AAD_PREFIX = "nexura-e2ee-state:v1:"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
