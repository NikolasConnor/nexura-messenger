package com.nexura.messenger.data

import android.content.Context
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Olm E2EE client used by NexuraApi for direct and group conversations.
 *
 * Account/session pickles are encrypted at rest by E2eeStateStore before
 * persistence. The server-facing public bundle contains no private material.
 */
class E2eeClient(context: Context, userId: String) {
    private val store = E2eeStateStore(context.applicationContext, userId)
    private val nativeSessionNamespace = userId

    private fun nativeSessionId(key: String): String = "$nativeSessionNamespace|$key"
    private fun cachedEncrypt(key: String, pickle: ByteArray, plaintext: ByteArray): Array<ByteArray> {
        val id = nativeSessionId(key)
        return try { E2eeNative.nativeEncryptCached(id, pickle, plaintext) }
        catch (error: Throwable) { runCatching { E2eeNative.nativeInvalidateSession(id) }; throw error }
    }
    private fun cachedDecrypt(key: String, pickle: ByteArray, envelope: String): Array<ByteArray> {
        val id = nativeSessionId(key)
        return try { E2eeNative.nativeDecryptCached(id, pickle, envelope) }
        catch (error: Throwable) { runCatching { E2eeNative.nativeInvalidateSession(id) }; throw error }
    }
    private fun invalidateNativeSession(key: String) { runCatching { E2eeNative.nativeInvalidateSession(nativeSessionId(key)) } }
    // Olm ratchets are sequential per device session, but independent chats can progress concurrently.
    private val sessionLocks = ConcurrentHashMap<String, Any>()
    private fun sessionLock(key: String): Any = sessionLocks.getOrPut(key) { Any() }

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
        curve25519IdentityKey: String,
        oneTimeKey: String,
        signature: String
    ): Boolean = E2eeNative.nativeVerifyOneTimeKey(ed25519IdentityKey, curve25519IdentityKey, oneTimeKey, signature)

    @Synchronized
    fun startDirectSession(
        conversationId: String,
        recipientCurve25519: String,
        recipientEd25519: String,
        recipientOneTimeKey: String,
        recipientOneTimeKeySignature: String,
        firstPlaintext: ByteArray,
        messageId: String = UUID.randomUUID().toString()
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
        val ciphertext = String(result[1], StandardCharsets.UTF_8)
        store.saveBatch(mapOf(
            sessionKey(conversationId) to result[0],
            messageCacheKey(conversationId, messageId) to firstPlaintext,
            outboxKey(conversationId, messageId) to JSONObject()
                .put("kind", E2eeMessageEnvelope.Kind.PRE_KEY.wireValue)
                .put("ciphertext", ciphertext)
                .toString().toByteArray(StandardCharsets.UTF_8)
        ))
        invalidateNativeSession(sessionKey(conversationId))
        return ciphertext
    }

    @Synchronized
    fun acceptDirectSession(
        conversationId: String,
        senderCurve25519: String,
        preKeyEnvelope: String,
        messageId: String? = null
    ): ByteArray {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        val account = store.load(ACCOUNT_KEY) ?: error("E2EE device is not initialized")
        val result = E2eeNative.nativeAcceptInbound(account, senderCurve25519, preKeyEnvelope)
        require(result.size == 3) { "Invalid native inbound-session result" }
        val states = linkedMapOf(
            ACCOUNT_KEY to result[0],
            sessionKey(conversationId) to result[1]
        )
        if (!messageId.isNullOrBlank()) {
            states[messageCacheKey(conversationId, messageId)] = result[2]
        }
        store.saveBatch(states)
        invalidateNativeSession(sessionKey(conversationId))
        return result[2]
    }

    private fun groupSessionKey(conversationId: String, peerUserId: String, peerDeviceId: String): String =
        "olm-group-session:$conversationId:$peerUserId:$peerDeviceId"
    private fun groupOutboxKey(conversationId: String, messageId: String, peerUserId: String, peerDeviceId: String): String =
        "group-outbox:$conversationId:$messageId:$peerUserId:$peerDeviceId"

    @Synchronized
    fun hasGroupSession(conversationId: String, peerUserId: String, peerDeviceId: String): Boolean =
        store.load(groupSessionKey(conversationId, peerUserId, peerDeviceId)) != null

    @Synchronized
    fun startGroupSession(
        conversationId: String,
        peerUserId: String,
        peerDeviceId: String,
        recipientCurve25519: String,
        recipientEd25519: String,
        recipientOneTimeKey: String,
        recipientOneTimeKeySignature: String,
        plaintext: ByteArray,
        messageId: String
    ): String {
        val account = store.load(ACCOUNT_KEY) ?: error("E2EE device is not initialized")
        val result = E2eeNative.nativeStartOutbound(
            account, recipientCurve25519, recipientEd25519,
            recipientOneTimeKey, recipientOneTimeKeySignature, plaintext
        )
        require(result.size == 2) { "Invalid group outbound-session result" }
        val key = groupSessionKey(conversationId, peerUserId, peerDeviceId)
        val ciphertext = String(result[1], StandardCharsets.UTF_8)
        store.saveBatch(mapOf(
            key to result[0],
            messageCacheKey(conversationId, messageId) to plaintext,
            groupOutboxKey(conversationId, messageId, peerUserId, peerDeviceId) to JSONObject()
                .put("kind", E2eeMessageEnvelope.Kind.PRE_KEY.wireValue)
                .put("ciphertext", ciphertext).toString().toByteArray(StandardCharsets.UTF_8)
        ))
        invalidateNativeSession(key)
        return ciphertext
    }

    @Synchronized
    fun acceptGroupSession(
        conversationId: String,
        peerUserId: String,
        peerDeviceId: String,
        senderCurve25519: String,
        ciphertext: String,
        messageId: String
    ): ByteArray {
        val account = store.load(ACCOUNT_KEY) ?: error("E2EE device is not initialized")
        val result = E2eeNative.nativeAcceptInbound(account, senderCurve25519, ciphertext)
        require(result.size == 3) { "Invalid group inbound-session result" }
        store.saveBatch(mapOf(
            ACCOUNT_KEY to result[0],
            groupSessionKey(conversationId, peerUserId, peerDeviceId) to result[1],
            messageCacheKey(conversationId, messageId) to result[2]
        ))
        invalidateNativeSession(groupSessionKey(conversationId, peerUserId, peerDeviceId))
        return result[2]
    }

    fun encryptGroupMessage(
        conversationId: String,
        peerUserId: String,
        peerDeviceId: String,
        plaintext: ByteArray,
        messageId: String
    ): String {
        val key = groupSessionKey(conversationId, peerUserId, peerDeviceId)
        return synchronized(sessionLock(key)) {
            val session = store.load(key) ?: error("No E2EE group session for recipient device")
            val result = cachedEncrypt(key, session, plaintext)
            require(result.size == 2) { "Invalid group encryption result" }
            val ciphertext = String(result[1], StandardCharsets.UTF_8)
            try {
            store.saveBatch(mapOf(
                key to result[0],
                messageCacheKey(conversationId, messageId) to plaintext,
                groupOutboxKey(conversationId, messageId, peerUserId, peerDeviceId) to JSONObject()
                    .put("kind", E2eeMessageEnvelope.Kind.NORMAL.wireValue)
                    .put("ciphertext", ciphertext).toString().toByteArray(StandardCharsets.UTF_8)
            ))
            } catch (error: Throwable) {
                invalidateNativeSession(key)
                throw error
            }
            ciphertext
        }
    }

    @Synchronized
    fun pendingGroupEnvelope(
        conversationId: String, messageId: String, peerUserId: String, peerDeviceId: String
    ): E2eeGroupRecipientEnvelope? {
        val bytes = store.load(groupOutboxKey(conversationId, messageId, peerUserId, peerDeviceId)) ?: return null
        val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
        return E2eeGroupRecipientEnvelope(
            peerUserId, peerDeviceId,
            E2eeMessageEnvelope.Kind.fromWire(json.getString("kind")),
            json.getString("ciphertext")
        )
    }

    @Synchronized
    fun markGroupOutboundDelivered(conversationId: String, messageId: String, recipients: List<E2eeGroupRecipientEnvelope>) {
        recipients.forEach { recipient ->
            store.delete(groupOutboxKey(conversationId, messageId, recipient.userId, recipient.deviceId))
        }
    }

    fun decryptGroupMessage(
        conversationId: String,
        peerUserId: String,
        peerDeviceId: String,
        ciphertext: String,
        messageId: String
    ): ByteArray {
        val key = groupSessionKey(conversationId, peerUserId, peerDeviceId)
        return synchronized(sessionLock(key)) {
            val session = store.load(key) ?: error("No E2EE group session for sender device")
            val result = cachedDecrypt(key, session, ciphertext)
            require(result.size == 2) { "Invalid group decryption result" }
            try {
            store.saveBatch(mapOf(
                key to result[0],
                messageCacheKey(conversationId, messageId) to result[1]
            ))
            } catch (error: Throwable) {
                invalidateNativeSession(key)
                throw error
            }
            result[1]
        }
    }

    @Synchronized
    fun hasDirectSession(conversationId: String): Boolean {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        return store.load(sessionKey(conversationId)) != null
    }

    /**
     * A fingerprint is a full SHA-256 digest over BOTH device identity keys.
     * Users must compare it through a separate trusted channel before trusting.
     */
    fun deviceFingerprint(curve25519IdentityKey: String, ed25519IdentityKey: String): String {
        require(curve25519IdentityKey.length in 40..64 && ed25519IdentityKey.length in 40..64) {
            "Invalid device identity keys"
        }
        val canonical = "nexura-device-identity:v1\n$curve25519IdentityKey\n$ed25519IdentityKey"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it) }.chunked(4).joinToString("-")
    }

    /**
     * Shared safety number for a direct conversation. Both participants hash
     * the same two device identities in a deterministic order, so the result
     * is identical on both phones regardless of who computes it.
     */
    fun conversationFingerprint(
        firstCurve25519Key: String,
        firstEd25519Key: String,
        secondCurve25519Key: String,
        secondEd25519Key: String
    ): String {
        val keys = listOf(
            firstCurve25519Key to firstEd25519Key,
            secondCurve25519Key to secondEd25519Key
        )
        require(keys.all { (curve, ed) -> curve.length in 40..64 && ed.length in 40..64 }) {
            "Invalid device identity keys"
        }
        require(keys[0] != keys[1]) { "Conversation devices must have different identities" }
        val canonicalDevices = keys
            .map { (curve, ed) -> "$curve:$ed" }
            .sorted()
        val canonical = "nexura-conversation-safety-number:v1\n" +
            canonicalDevices.joinToString("\n")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it) }.chunked(4).joinToString("-")
    }

    @Synchronized
    fun trustRemoteDevice(
        remoteUserId: String,
        remoteDeviceId: String,
        curve25519IdentityKey: String,
        ed25519IdentityKey: String
    ) {
        require(remoteUserId.isNotBlank() && remoteDeviceId.isNotBlank()) { "Invalid remote device identity" }
        require(curve25519IdentityKey.length in 40..64 && ed25519IdentityKey.length in 40..64) {
            "Invalid remote device keys"
        }
        val trusted = JSONObject()
            .put("curve25519", curve25519IdentityKey)
            .put("ed25519", ed25519IdentityKey)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        store.save(trustedDeviceKey(remoteUserId, remoteDeviceId), trusted)
    }


    /** Trust first-seen device identity; a later key change always requires manual verification. */
    @Synchronized
    fun trustRemoteDeviceOnFirstUse(userId: String, deviceId: String, curveKey: String, edKey: String): Boolean {
        require(userId.isNotBlank() && deviceId.isNotBlank()) { "Invalid remote device identity" }
        require(curveKey.length in 40..64 && edKey.length in 40..64) { "Invalid remote device keys" }
        val saved = store.load(trustedDeviceKey(userId, deviceId))
        if (saved == null) {
            trustRemoteDevice(userId, deviceId, curveKey, edKey)
            return true
        }
        val identity = runCatching { JSONObject(String(saved, StandardCharsets.UTF_8)) }.getOrNull()
            ?: throw IllegalStateException("Сохранённая запись доверия повреждена")
        if (identity.optString("curve25519") != curveKey || identity.optString("ed25519") != edKey)
            throw IllegalStateException("Ключ устройства изменился. Сверьте отпечаток с собеседником.")
        return false
    }

    @Synchronized
    fun isRemoteDeviceTrusted(
        remoteUserId: String,
        remoteDeviceId: String,
        curve25519IdentityKey: String,
        ed25519IdentityKey: String
    ): Boolean {
        val saved = store.load(trustedDeviceKey(remoteUserId, remoteDeviceId)) ?: return false
        val identity = runCatching { JSONObject(String(saved, StandardCharsets.UTF_8)) }.getOrNull()
            ?: return false
        return identity.optString("curve25519") == curve25519IdentityKey &&
            identity.optString("ed25519") == ed25519IdentityKey
    }

    fun encryptDirectMessage(
        conversationId: String,
        plaintext: ByteArray,
        messageId: String = UUID.randomUUID().toString()
    ): String {
        val key = sessionKey(conversationId)
        return synchronized(sessionLock(key)) {
            val session = store.load(key) ?: error("No E2EE session for this conversation")
            val result = E2eeNative.nativeEncrypt(session, plaintext)
            require(result.size == 2) { "Invalid native encryption result" }
            val ciphertext = String(result[1], StandardCharsets.UTF_8)
            try {
            store.saveBatch(mapOf(
                key to result[0],
                messageCacheKey(conversationId, messageId) to plaintext,
                outboxKey(conversationId, messageId) to JSONObject()
                    .put("kind", E2eeMessageEnvelope.Kind.NORMAL.wireValue)
                    .put("ciphertext", ciphertext)
                    .toString().toByteArray(StandardCharsets.UTF_8)
            ))
            } catch (error: Throwable) {
                invalidateNativeSession(key)
                throw error
            }
            ciphertext
        }
    }

    fun decryptDirectMessage(
        conversationId: String,
        envelope: String,
        messageId: String? = null
    ): ByteArray {
        val key = sessionKey(conversationId)
        return synchronized(sessionLock(key)) {
            val session = store.load(key) ?: error("No E2EE session for this conversation")
            val result = cachedDecrypt(key, session, envelope)
            require(result.size == 2) { "Invalid native decryption result" }
            val states = linkedMapOf(key to result[0])
            if (!messageId.isNullOrBlank()) {
                states[messageCacheKey(conversationId, messageId)] = result[1]
            }
            try {
            store.saveBatch(states)
            } catch (error: Throwable) {
                invalidateNativeSession(key)
                throw error
            }
            result[1]
        }
    }

    @Synchronized
    fun cachedMessage(conversationId: String, messageId: String): ByteArray? =
        store.load(messageCacheKey(conversationId, messageId))

    @Synchronized
    fun pendingOutboundEnvelope(conversationId: String, messageId: String): String? {
        val bytes = store.load(outboxKey(conversationId, messageId)) ?: return null
        return JSONObject(String(bytes, StandardCharsets.UTF_8)).optString("ciphertext")
            .takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun pendingOutboundKind(conversationId: String, messageId: String): E2eeMessageEnvelope.Kind? {
        val bytes = store.load(outboxKey(conversationId, messageId)) ?: return null
        val kind = JSONObject(String(bytes, StandardCharsets.UTF_8)).optString("kind")
        return E2eeMessageEnvelope.Kind.fromWire(kind)
    }

    @Synchronized
    fun markOutboundDelivered(conversationId: String, messageId: String) {
        store.delete(outboxKey(conversationId, messageId))
    }

    /** Permanently remove this user's local E2EE identity and all local sessions. */
    @Synchronized
    fun clearLocalIdentity() {
        store.clearAll()
    }

    private fun sessionKey(conversationId: String): String = "olm-session:$conversationId"
    private fun messageCacheKey(conversationId: String, messageId: String): String =
        "message-cache:$conversationId:$messageId"
    private fun outboxKey(conversationId: String, messageId: String): String =
        "outbox:$conversationId:$messageId"

    private fun trustedDeviceKey(userId: String, deviceId: String): String =
        "trusted-device:$userId:$deviceId"

    private companion object {
        const val ACCOUNT_KEY = "olm-device-account"
        const val PUBLIC_BUNDLE_KEY = "olm-public-device-bundle"
        const val PUBLISHED_KEY_IDS = "olm-published-one-time-key-ids"
    }
}
