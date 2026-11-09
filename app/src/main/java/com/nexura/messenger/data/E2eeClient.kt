package com.nexura.messenger.data

import android.content.Context
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Experimental 1:1 Olm facade. Not connected to NexuraApi or chat UI yet.
 *
 * All account/session pickles are encrypted at rest by E2eeStateStore before
 * persistence. The server-facing public bundle contains no private material.
 */
class E2eeClient(context: Context, userId: String) {
    private val store = E2eeStateStore(context.applicationContext, userId)

    @Synchronized
    fun ensureDevice(oneTimeKeyCount: Int = 20): String {
        require(oneTimeKeyCount in 1..50) { "oneTimeKeyCount must be between 1 and 50" }
        val savedPublic = store.load(PUBLIC_BUNDLE_KEY)
        if (savedPublic != null && store.load(ACCOUNT_KEY) != null) {
            return String(savedPublic, StandardCharsets.UTF_8)
        }

        val result = E2eeNative.nativeCreateDevice(oneTimeKeyCount)
        require(result.size == 2) { "Invalid native device creation result" }
        val publicBundle = JSONObject(String(result[1], StandardCharsets.UTF_8))
            .put("protocol", "olm-v1")
            .put("bundle_version", 1)
            .put("device_id", UUID.randomUUID().toString())
        val oneTimeKeys = publicBundle.getJSONArray("one_time_keys")
        for (index in 0 until oneTimeKeys.length()) {
            oneTimeKeys.getJSONObject(index).put("key_id", UUID.randomUUID().toString())
        }
        val publicBundleBytes = publicBundle.toString().toByteArray(StandardCharsets.UTF_8)
        store.save(ACCOUNT_KEY, result[0])
        store.save(PUBLIC_BUNDLE_KEY, publicBundleBytes)
        return String(publicBundleBytes, StandardCharsets.UTF_8)
    }

    @Synchronized
    fun deviceId(): String {
        val bundle = store.load(PUBLIC_BUNDLE_KEY)
            ?: error("E2EE device is not initialized")
        return JSONObject(String(bundle, StandardCharsets.UTF_8)).getString("device_id")
    }

    @Synchronized
    fun replenishOneTimeKeys(count: Int): String {
        require(count in 1..50) { "count must be between 1 and 50" }
        val account = store.load(ACCOUNT_KEY) ?: error("E2EE device is not initialized")
        val savedBundle = store.load(PUBLIC_BUNDLE_KEY)
            ?: error("E2EE public bundle is missing")
        val bundle = JSONObject(String(savedBundle, StandardCharsets.UTF_8))
        val generated = E2eeNative.nativeGenerateOneTimeKeys(account, count)
        require(generated.size == 2) { "Invalid native one-time-key result" }

        val newKeys = org.json.JSONArray(String(generated[1], StandardCharsets.UTF_8))
        for (index in 0 until newKeys.length()) {
            newKeys.getJSONObject(index).put("key_id", UUID.randomUUID().toString())
        }
        val allKeys = bundle.getJSONArray("one_time_keys")
        for (index in 0 until newKeys.length()) allKeys.put(newKeys.getJSONObject(index))
        val updatedBundle = bundle.toString().toByteArray(StandardCharsets.UTF_8)

        // Persist the account and public bundle before any network request. A
        // failed upload can safely be retried with the same stable key IDs.
        store.save(ACCOUNT_KEY, generated[0])
        store.save(PUBLIC_BUNDLE_KEY, updatedBundle)
        return newKeys.toString()
    }

    /**
     * Call only after Supabase confirms the public one-time-key bundle was saved.
     * This prevents the same private OTKs from being republished after restart.
     */
    @Synchronized
    fun pendingOneTimeKeysJson(): String {
        val bundleBytes = store.load(PUBLIC_BUNDLE_KEY)
            ?: error("E2EE device is not initialized")
        val bundle = JSONObject(String(bundleBytes, StandardCharsets.UTF_8))
        val allKeys = bundle.getJSONArray("one_time_keys")
        val publishedIds = store.load(PUBLISHED_KEY_IDS)?.let {
            org.json.JSONArray(String(it, StandardCharsets.UTF_8))
        } ?: org.json.JSONArray()
        val published = (0 until publishedIds.length()).mapNotNull { publishedIds.optString(it) }.toSet()
        val pending = org.json.JSONArray()
        for (index in 0 until allKeys.length()) {
            val key = allKeys.getJSONObject(index)
            if (!published.contains(key.optString("key_id"))) pending.put(key)
        }
        return pending.toString()
    }

    @Synchronized
    fun markPublicKeysPublished(keyIds: List<String>) {
        require(keyIds.isNotEmpty()) { "No published one-time keys to acknowledge" }
        val account = store.load(ACCOUNT_KEY) ?: error("E2EE device is not initialized")
        val bundleBytes = store.load(PUBLIC_BUNDLE_KEY)
            ?: error("E2EE public bundle is missing")
        val bundle = JSONObject(String(bundleBytes, StandardCharsets.UTF_8))
        val allKeys = bundle.getJSONArray("one_time_keys")
        val knownIds = (0 until allKeys.length()).map { allKeys.getJSONObject(it).getString("key_id") }.toSet()
        require(keyIds.all { knownIds.contains(it) }) { "Unknown one-time key ID" }

        val previous = store.load(PUBLISHED_KEY_IDS)?.let {
            org.json.JSONArray(String(it, StandardCharsets.UTF_8))
        } ?: org.json.JSONArray()
        val combined = linkedSetOf<String>()
        for (index in 0 until previous.length()) previous.optString(index).takeIf { it.isNotBlank() }?.let(combined::add)
        keyIds.forEach(combined::add)
        val updatedAccount = E2eeNative.nativeMarkKeysPublished(account)
        store.save(ACCOUNT_KEY, updatedAccount)
        store.save(PUBLISHED_KEY_IDS, org.json.JSONArray(combined.toList()).toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun verifyPublishedOneTimeKey(
        ed25519IdentityKey: String,
        oneTimeKey: String,
        signature: String
    ): Boolean = E2eeNative.nativeVerifyOneTimeKey(ed25519IdentityKey, oneTimeKey, signature)

    @Synchronized
    fun startDirectSession(
        conversationId: String,
        recipientCurve25519: String,
        recipientEd25519: String,
        recipientOneTimeKey: String,
        recipientOneTimeKeySignature: String,
        firstPlaintext: ByteArray
    ): String {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        val account = store.load(ACCOUNT_KEY) ?: error("E2EE device is not initialized")
        val result = E2eeNative.nativeStartOutbound(
            account,
            recipientCurve25519,
            recipientEd25519,
            recipientOneTimeKey,
            recipientOneTimeKeySignature,
            firstPlaintext
        )
        require(result.size == 2) { "Invalid native outbound-session result" }
        store.save(sessionKey(conversationId), result[0])
        return String(result[1], StandardCharsets.UTF_8)
    }

    @Synchronized
    fun acceptDirectSession(
        conversationId: String,
        senderCurve25519: String,
        preKeyEnvelope: String
    ): ByteArray {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        val account = store.load(ACCOUNT_KEY) ?: error("E2EE device is not initialized")
        val result = E2eeNative.nativeAcceptInbound(account, senderCurve25519, preKeyEnvelope)
        require(result.size == 3) { "Invalid native inbound-session result" }
        store.save(ACCOUNT_KEY, result[0])
        store.save(sessionKey(conversationId), result[1])
        return result[2]
    }

    @Synchronized
    fun encryptDirectMessage(conversationId: String, plaintext: ByteArray): String {
        val session = store.load(sessionKey(conversationId))
            ?: error("No E2EE session for this conversation")
        val result = E2eeNative.nativeEncrypt(session, plaintext)
        require(result.size == 2) { "Invalid native encryption result" }
        store.save(sessionKey(conversationId), result[0])
        return String(result[1], StandardCharsets.UTF_8)
    }

    @Synchronized
    fun decryptDirectMessage(conversationId: String, envelope: String): ByteArray {
        val session = store.load(sessionKey(conversationId))
            ?: error("No E2EE session for this conversation")
        val result = E2eeNative.nativeDecrypt(session, envelope)
        require(result.size == 2) { "Invalid native decryption result" }
        store.save(sessionKey(conversationId), result[0])
        return result[1]
    }

    /** Permanently remove this user's local E2EE identity and all local sessions. */
    @Synchronized
    fun clearLocalIdentity() {
        store.clearAll()
    }

    private fun sessionKey(conversationId: String): String = "olm-session:$conversationId"

    private companion object {
        const val ACCOUNT_KEY = "olm-device-account"
        const val PUBLIC_BUNDLE_KEY = "olm-public-device-bundle"
        const val PUBLISHED_KEY_IDS = "olm-published-one-time-key-ids"
    }
}
