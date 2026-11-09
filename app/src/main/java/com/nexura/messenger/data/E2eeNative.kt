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

    external fun nativeVerifyOneTimeKey(
        ed25519IdentityKey: String,
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

    external fun nativeEncrypt(
        sessionPickle: ByteArray,
        plaintext: ByteArray
    ): Array<ByteArray>

    external fun nativeDecrypt(
        sessionPickle: ByteArray,
        envelope: String
    ): Array<ByteArray>
}
