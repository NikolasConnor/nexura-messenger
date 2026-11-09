package com.nexura.messenger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class E2eeClientInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clients = mutableListOf<Pair<String, E2eeClient>>()

    @After
    fun cleanUp() {
        clients.forEach { (_, client) ->
            runCatching { client.clearLocalIdentity() }
        }
    }

    private fun newClient(): Pair<String, E2eeClient> {
        val userNamespace = "e2ee-test-${UUID.randomUUID()}"
        val client = E2eeClient(context, userNamespace)
        clients += userNamespace to client
        return userNamespace to client
    }

    @Test
    fun twoAndroidDevicesExchangeAndRestoreOlmSession() {
        val (aliceId, alice) = newClient()
        val (_, bob) = newClient()

        val aliceBundle = JSONObject(alice.ensureDevice())
        val bobBundle = JSONObject(bob.ensureDevice())
        val bobKeys = bobBundle.getJSONArray("one_time_keys")
        val bobOneTimeKey = bobKeys.getJSONObject(0)

        val conversationId = UUID.randomUUID().toString()
        val firstMessageId = UUID.randomUUID().toString()
        val initialText = "Nexura E2EE test".toByteArray(StandardCharsets.UTF_8)
        val preKeyEnvelope = alice.startDirectSession(
            conversationId = conversationId,
            recipientCurve25519 = bobBundle.getString("curve25519"),
            recipientEd25519 = bobBundle.getString("ed25519"),
            recipientOneTimeKey = bobOneTimeKey.getString("key"),
            recipientOneTimeKeySignature = bobOneTimeKey.getString("signature"),
            firstPlaintext = initialText,
            messageId = firstMessageId
        )
        assertEquals(preKeyEnvelope, alice.pendingOutboundEnvelope(conversationId, firstMessageId))
        assertArrayEquals(initialText, alice.cachedMessage(conversationId, firstMessageId))
        alice.markOutboundDelivered(conversationId, firstMessageId)
        assertEquals(null, alice.pendingOutboundEnvelope(conversationId, firstMessageId))

        assertArrayEquals(
            initialText,
            bob.acceptDirectSession(
                conversationId,
                aliceBundle.getString("curve25519"),
                preKeyEnvelope,
                firstMessageId
            )
        )
        assertArrayEquals(initialText, bob.cachedMessage(conversationId, firstMessageId))
        assertEquals(
            true,
            runCatching {
                bob.acceptDirectSession(
                    conversationId,
                    aliceBundle.getString("curve25519"),
                    preKeyEnvelope,
                    firstMessageId
                )
            }.isFailure
        )
        // The caller's encrypted local cache lets message polling avoid replaying
        // the already-consumed pre-key envelope.
        assertArrayEquals(initialText, bob.cachedMessage(conversationId, firstMessageId))

        val replyMessageId = UUID.randomUUID().toString()
        val replyText = "Reply after session setup".toByteArray(StandardCharsets.UTF_8)
        val replyEnvelope = bob.encryptDirectMessage(conversationId, replyText, replyMessageId)
        assertEquals(replyEnvelope, bob.pendingOutboundEnvelope(conversationId, replyMessageId))

        // A new facade instance simulates process restart; persisted session and
        // local message cache must be decrypted from Android Keystore-backed storage.
        val aliceAfterRestart = E2eeClient(context, aliceId)
        assertArrayEquals(replyText, aliceAfterRestart.decryptDirectMessage(conversationId, replyEnvelope, replyMessageId))
        assertArrayEquals(replyText, aliceAfterRestart.cachedMessage(conversationId, replyMessageId))
    }

    @Test
    fun encryptedStateIsIsolatedBetweenUsersAndConversationKeys() {
        val firstUser = "state-test-${UUID.randomUUID()}"
        val secondUser = "state-test-${UUID.randomUUID()}"
        val firstStore = E2eeStateStore(context, firstUser)
        val secondStore = E2eeStateStore(context, secondUser)
        val secretState = "opaque Olm pickle".toByteArray(StandardCharsets.UTF_8)

        try {
            firstStore.save("conversation-a", secretState)
            assertArrayEquals(secretState, firstStore.load("conversation-a"))
            assertEquals(null, secondStore.load("conversation-a"))
            assertEquals(null, firstStore.load("conversation-b"))
        } finally {
            firstStore.clearAll()
            secondStore.clearAll()
        }
    }

    @Test
    fun stateStoreBatchPersistsRelatedValuesTogether() {
        val store = E2eeStateStore(context, "batch-${UUID.randomUUID()}")
        val first = "ratchet-state".toByteArray(StandardCharsets.UTF_8)
        val second = "cached-plaintext".toByteArray(StandardCharsets.UTF_8)
        try {
            store.saveBatch(mapOf("session" to first, "message-cache" to second))
            assertArrayEquals(first, store.load("session"))
            assertArrayEquals(second, store.load("message-cache"))
        } finally {
            store.clearAll()
        }
    }

    @Test
    fun remoteDeviceTrustIsBoundToBothIdentityKeys() {
        val (_, client) = newClient()
        val userId = UUID.randomUUID().toString()
        val deviceId = UUID.randomUUID().toString()
        val curve = "A".repeat(43)
        val ed = "B".repeat(43)

        assertEquals(false, client.isRemoteDeviceTrusted(userId, deviceId, curve, ed))
        client.trustRemoteDevice(userId, deviceId, curve, ed)
        assertEquals(true, client.isRemoteDeviceTrusted(userId, deviceId, curve, ed))
        assertEquals(false, client.isRemoteDeviceTrusted(userId, deviceId, "C".repeat(43), ed))
        assertEquals(false, client.isRemoteDeviceTrusted(userId, deviceId, curve, "D".repeat(43)))
        assertEquals(
            client.deviceFingerprint(curve, ed),
            client.deviceFingerprint(curve, ed)
        )
        assertNotEquals(client.deviceFingerprint(curve, ed), client.deviceFingerprint("C".repeat(43), ed))
    }

    @Test
    fun e2eeMessageEnvelopeRoundTripsAndRejectsUnknownProtocol() {
        val envelope = E2eeMessageEnvelope(
            kind = E2eeMessageEnvelope.Kind.PRE_KEY,
            conversationId = UUID.randomUUID().toString(),
            senderDeviceId = UUID.randomUUID().toString(),
            recipientDeviceId = UUID.randomUUID().toString(),
            senderCurve25519 = "A".repeat(43),
            ciphertext = """{"type":0,"body":"opaque-ciphertext"}"""
        )
        val decoded = E2eeMessageEnvelope.decode(envelope.encode())
        assertEquals(envelope, decoded)
        assertEquals(true, E2eeMessageEnvelope.isEnvelope(envelope.encode()))
        assertEquals(false, E2eeMessageEnvelope.isEnvelope("ordinary legacy text"))
        val unknown = JSONObject(envelope.encode()).put("protocol", "nexura-olm-v2").toString()
        assertEquals(true, E2eeMessageEnvelope.isEnvelope(unknown))
        assertEquals(false, runCatching { E2eeMessageEnvelope.decode(unknown) }.isSuccess)
    }

    @Test
    fun oneTimeKeySignatureTamperingIsRejected() {
        val (_, bob) = newClient()
        val bundle = JSONObject(bob.ensureDevice())
        val keys = bundle.getJSONArray("one_time_keys")
        val first = keys.getJSONObject(0)

        assertEquals(
            true,
            bob.verifyPublishedOneTimeKey(
                bundle.getString("ed25519"),
                bundle.getString("curve25519"),
                first.getString("key"),
                first.getString("signature")
            )
        )
        assertEquals(
            false,
            runCatching {
                bob.verifyPublishedOneTimeKey(
                    bundle.getString("ed25519"),
                    bundle.getString("curve25519"),
                    first.getString("key") + "A",
                    first.getString("signature")
                )
            }.getOrDefault(false)
        )
    }
}
