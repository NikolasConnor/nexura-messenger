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
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

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
    fun groupEnvelopeRoundTripsAndRejectsDuplicateRecipientDevices() {
        val envelope = E2eeGroupMessageEnvelope(
            conversationId = UUID.randomUUID().toString(),
            senderUserId = UUID.randomUUID().toString(),
            senderDeviceId = UUID.randomUUID().toString(),
            senderCurve25519 = "A".repeat(43),
            recipients = listOf(
                E2eeGroupRecipientEnvelope(
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    E2eeMessageEnvelope.Kind.PRE_KEY, """{"type":0,"body":"alice-to-bob"}"""
                ),
                E2eeGroupRecipientEnvelope(
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    E2eeMessageEnvelope.Kind.NORMAL, """{"type":1,"body":"alice-to-carol"}"""
                )
            )
        )
        assertEquals(envelope, E2eeGroupMessageEnvelope.decode(envelope.encode()))
        assertEquals(true, E2eeGroupMessageEnvelope.isEnvelope(envelope.encode()))
        val duplicate = envelope.copy(recipients = listOf(envelope.recipients.first(), envelope.recipients.first()))
        assertEquals(false, runCatching { E2eeGroupMessageEnvelope.decode(duplicate.encode()) }.isSuccess)
    }

    @Test
    fun groupFanoutUsesIndependentOlmSessionsPerRecipientDevice() {
        val (_, alice) = newClient()
        val (bobId, bob) = newClient()
        val (carolId, carol) = newClient()
        val aliceBundle = JSONObject(alice.ensureDevice())
        val bobBundle = JSONObject(bob.ensureDevice())
        val carolBundle = JSONObject(carol.ensureDevice())
        val bobOtk = bobBundle.getJSONArray("one_time_keys").getJSONObject(0)
        val carolOtk = carolBundle.getJSONArray("one_time_keys").getJSONObject(0)
        val room = UUID.randomUUID().toString()
        val message1 = UUID.randomUUID().toString()
        val firstText = "Group photo key and metadata".toByteArray(StandardCharsets.UTF_8)

        val bobCipher = alice.startGroupSession(
            room, bobId, bobBundle.getString("device_id"),
            bobBundle.getString("curve25519"), bobBundle.getString("ed25519"),
            bobOtk.getString("key"), bobOtk.getString("signature"), firstText, message1
        )
        val carolCipher = alice.startGroupSession(
            room, carolId, carolBundle.getString("device_id"),
            carolBundle.getString("curve25519"), carolBundle.getString("ed25519"),
            carolOtk.getString("key"), carolOtk.getString("signature"), firstText, message1
        )
        assertNotEquals(bobCipher, carolCipher)
        assertArrayEquals(firstText, bob.acceptGroupSession(
            room, "alice", aliceBundle.getString("device_id"),
            aliceBundle.getString("curve25519"), bobCipher, message1
        ))
        assertArrayEquals(firstText, carol.acceptGroupSession(
            room, "alice", aliceBundle.getString("device_id"),
            aliceBundle.getString("curve25519"), carolCipher, message1
        ))

        val message2 = UUID.randomUUID().toString()
        val secondText = "Second group message".toByteArray(StandardCharsets.UTF_8)
        val bobNext = alice.encryptGroupMessage(room, bobId, bobBundle.getString("device_id"), secondText, message2)
        val carolNext = alice.encryptGroupMessage(room, carolId, carolBundle.getString("device_id"), secondText, message2)
        assertArrayEquals(secondText, bob.decryptGroupMessage(
            room, "alice", aliceBundle.getString("device_id"), bobNext, message2
        ))
        assertArrayEquals(secondText, carol.decryptGroupMessage(
            room, "alice", aliceBundle.getString("device_id"), carolNext, message2
        ))
        assertEquals(null, bob.pendingGroupEnvelope(room, message1, carolId, carolBundle.getString("device_id")))
        assertEquals(bobCipher, alice.pendingGroupEnvelope(room, message1, bobId, bobBundle.getString("device_id"))?.ciphertext)
    }

    @Test
    fun encryptedMediaRoundTripsAndRejectsTampering() {
        val dir = File(context.cacheDir, "e2ee-media-test").also { it.mkdirs() }
        val plain = ByteArray(512 * 1024) { (it % 251).toByte() }
        val key = ByteArray(32) { (it + 3).toByte() }
        val iv = ByteArray(12) { (it + 9).toByte() }
        val encrypted = File.createTempFile("media-test-", ".enc", dir)
        val decrypted = File.createTempFile("media-test-", ".dec", dir)
        val tamperedOutput = File.createTempFile("media-test-", ".bad", dir)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            CipherOutputStream(FileOutputStream(encrypted), cipher).use { it.write(plain) }
            val digest = MessageDigest.getInstance("SHA-256").digest(encrypted.readBytes())
                .joinToString("") { "%02x".format(it) }
            E2eeMediaCrypto.decryptFile(
                encrypted, decrypted,
                Base64.encodeToString(key, Base64.NO_WRAP),
                Base64.encodeToString(iv, Base64.NO_WRAP),
                digest
            )
            assertArrayEquals(plain, decrypted.readBytes())

            val bytes = encrypted.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
            encrypted.writeBytes(bytes)
            val tamperedDigest = MessageDigest.getInstance("SHA-256").digest(encrypted.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(
                true,
                runCatching {
                    E2eeMediaCrypto.decryptFile(
                        encrypted, tamperedOutput,
                        Base64.encodeToString(key, Base64.NO_WRAP),
                        Base64.encodeToString(iv, Base64.NO_WRAP),
                        tamperedDigest
                    )
                }.isFailure
            )
            assertEquals(false, tamperedOutput.exists() && tamperedOutput.length() > 0)
        } finally {
            encrypted.delete()
            decrypted.delete()
            tamperedOutput.delete()
            dir.delete()
        }
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

    @Test
    fun conversationSafetyNumberIsIdenticalForBothParticipants() {
        val (_, alice) = newClient()
        val (_, bob) = newClient()
        val aliceBundle = JSONObject(alice.ensureDevice())
        val bobBundle = JSONObject(bob.ensureDevice())

        val aliceView = alice.conversationFingerprint(
            aliceBundle.getString("curve25519"), aliceBundle.getString("ed25519"),
            bobBundle.getString("curve25519"), bobBundle.getString("ed25519")
        )
        val bobView = bob.conversationFingerprint(
            bobBundle.getString("curve25519"), bobBundle.getString("ed25519"),
            aliceBundle.getString("curve25519"), aliceBundle.getString("ed25519")
        )
        assertEquals(aliceView, bobView)

        val (_, other) = newClient()
        val otherBundle = JSONObject(other.ensureDevice())
        val changedPeerView = alice.conversationFingerprint(
            aliceBundle.getString("curve25519"), aliceBundle.getString("ed25519"),
            otherBundle.getString("curve25519"), otherBundle.getString("ed25519")
        )
        assertNotEquals(aliceView, changedPeerView)
    }

}
