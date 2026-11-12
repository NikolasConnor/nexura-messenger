package com.nexura.messenger.data

/**
 * JNI boundary for the isolated vodozemac core.
 * Native methods exchange opaque serialized protocol state; callers must persist
 * every returned pickle immediately with E2eeStateStore.
 */
internal object E2eeNative {
    init {
        System.loadLibrary("nexura_e2ee_jni")
    }

    external fun nativeCreateDevice(oneTimeKeyCount: Int): Array<ByteArray>

    external fun nativeGenerateOneTimeKeys(accountPickle: ByteArray, count: Int): Array<ByteArray>
    external fun nativeGenerateOneTimeKeysCached(accountId: String, accountPickle: ByteArray, count: Int): Array<ByteArray>
    external fun nativeMarkKeysPublishedCached(accountId: String, accountPickle: ByteArray): ByteArray
    external fun nativeStartOutboundCached(accountId: String, accountPickle: ByteArray, recipientCurve25519: String, recipientEd25519: String, recipientOneTimeKey: String, recipientOneTimeKeySignature: String, plaintext: ByteArray): Array<ByteArray>
    external fun nativeAcceptInboundCached(accountId: String, accountPickle: ByteArray, senderCurve25519: String, envelope: String): Array<ByteArray>
    external fun nativeInvalidateAccount(accountId: String)

    external fun nativeMarkKeysPublished(accountPickle: ByteArray): ByteArray

    external fun nativeVerifyOneTimeKey(
        ed25519IdentityKey: String,
        curve25519IdentityKey: String,
        oneTimeKey: String,
        signature: String
    ): Boolean

    external fun nativeStartOutbound(
        accountPickle: ByteArray,
        recipientCurve25519: String,
        recipientEd25519: String,
        recipientOneTimeKey: String,
        recipientOneTimeKeySignature: String,
        plaintext: ByteArray
    ): Array<ByteArray>

    external fun nativeAcceptInbound(
        accountPickle: ByteArray,
        senderCurve25519: String,
        envelope: String
    ): Array<ByteArray>

    external fun nativeCacheSession(sessionId: String, sessionPickle: ByteArray): Boolean
    external fun nativeInvalidateSession(sessionId: String)
    external fun nativeInvalidateSessionsForPrefix(prefix: String)
    external fun nativeEncryptCached(sessionId: String, sessionPickle: ByteArray, plaintext: ByteArray): Array<ByteArray>
    external fun nativeDecryptCached(sessionId: String, sessionPickle: ByteArray, envelope: String): Array<ByteArray>

    external fun nativeEncrypt(
        sessionPickle: ByteArray,
        plaintext: ByteArray
    ): Array<ByteArray>

    external fun nativeDecrypt(
        sessionPickle: ByteArray,
        envelope: String
    ): Array<ByteArray>
}
