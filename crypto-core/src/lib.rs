//! Isolated Olm 1:1 protocol proof of concept for Nexura.
//!
//! This crate is bridged into Android through JNI but is not wired into production message flow.
//! Callers must verify peer identity keys out-of-band before creating a session.
//! Pickles contain secret state and MUST be encrypted with a unique key protected
//! by Android Keystore before persistence. Never log plaintext, keys, or pickles.

use serde_json;
use vodozemac::olm::{Account, AccountPickle, OlmMessage, Session, SessionConfig, SessionPickle};
use vodozemac::{Ed25519PublicKey, Ed25519Signature};

#[derive(Debug)]
pub enum CryptoError {
    InvalidKey(String),
    InvalidEnvelope(String),
    UnexpectedPreKey,
    UnexpectedNormalMessage,
    Protocol(String),
}

impl std::fmt::Display for CryptoError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidKey(e) => write!(f, "invalid public key: {e}"),
            Self::InvalidEnvelope(e) => write!(f, "invalid encrypted envelope: {e}"),
            Self::UnexpectedPreKey => write!(f, "expected an Olm pre-key message"),
            Self::UnexpectedNormalMessage => write!(f, "expected a normal Olm message"),
            Self::Protocol(e) => write!(f, "Olm protocol error: {e}"),
        }
    }
}

impl std::error::Error for CryptoError {}

/// Public device identity material. Never contains private keys.
#[derive(serde::Serialize, serde::Deserialize, Clone)]
pub struct SignedOneTimeKey {
    pub key: String,
    pub signature: String,
}

#[derive(serde::Serialize, serde::Deserialize, Clone)]
pub struct PublicDeviceKeys {
    pub curve25519: String,
    pub ed25519: String,
    pub one_time_keys: Vec<SignedOneTimeKey>,
}

/// Canonical signed payload for each one-time key. Keep this exact encoding
/// stable across clients and server-side validation.
fn one_time_key_signing_payload(curve25519_identity_key: &str, key: &str) -> Vec<u8> {
    format!(r#"{{"curve25519":"{}","key":"{}"}}"#, curve25519_identity_key, key).into_bytes()
}

fn sign_unpublished_one_time_keys(account: &Account) -> Vec<SignedOneTimeKey> {
    let curve25519_identity_key = account.identity_keys().curve25519.to_base64();
    account.one_time_keys()
        .values()
        .map(|key| {
            let encoded = key.to_base64();
            let signature = account.sign(one_time_key_signing_payload(&curve25519_identity_key, &encoded));
            SignedOneTimeKey { key: encoded, signature: signature.to_base64() }
        })
        .collect()
}

/// Creates a device account, signs each published one-time key with the
/// account's Ed25519 identity key, and returns only public key material.
pub fn create_device_keys(one_time_key_count: usize) -> (Account, PublicDeviceKeys) {
    let mut account = Account::new();
    let count = one_time_key_count.min(account.max_number_of_one_time_keys());
    account.generate_one_time_keys(count);

    let identity = account.identity_keys();
    let one_time_keys = sign_unpublished_one_time_keys(&account);

    (
        account,
        PublicDeviceKeys {
            curve25519: identity.curve25519.to_base64(),
            ed25519: identity.ed25519.to_base64(),
            one_time_keys,
        },
    )
}

/// Generate a fresh batch after the previous batch has been durably published.
/// Returns only keys not yet acknowledged as published.
pub fn generate_additional_one_time_keys(
    account: &mut Account,
    requested_count: usize,
) -> Vec<SignedOneTimeKey> {
    let count = requested_count.min(account.max_number_of_one_time_keys());
    if count > 0 {
        account.generate_one_time_keys(count);
    }
    sign_unpublished_one_time_keys(account)
}

/// Mark currently generated one-time keys as published after the server confirms
/// their durable storage. Never call this before the upload transaction succeeds.
pub fn mark_keys_as_published(account: &mut Account) {
    account.mark_keys_as_published();
}

/// Verify a published one-time key against the device's Ed25519 identity key.
pub fn verify_signed_one_time_key(
    ed25519_identity_key: &str,
    curve25519_identity_key: &str,
    signed_key: &SignedOneTimeKey,
) -> Result<(), CryptoError> {
    let identity = Ed25519PublicKey::from_base64(ed25519_identity_key)
        .map_err(|e| CryptoError::InvalidKey(e.to_string()))?;
    let signature = Ed25519Signature::from_base64(&signed_key.signature)
        .map_err(|e| CryptoError::InvalidKey(e.to_string()))?;
    identity
        .verify(&one_time_key_signing_payload(curve25519_identity_key, &signed_key.key), &signature)
        .map_err(|e| CryptoError::Protocol(e.to_string()))
}

/// Serialize the full Olm account state. The returned string is secret
/// material and MUST immediately be encrypted by Android Keystore storage.
pub fn pickle_account(account: &Account) -> Result<String, CryptoError> {
    serde_json::to_string(&account.pickle())
        .map_err(|e| CryptoError::Protocol(e.to_string()))
}

/// Restore an Olm account from its serialized pickle. Caller must decrypt
/// the stored bytes before passing them here.
pub fn unpickle_account(serialized: &str) -> Result<Account, CryptoError> {
    let pickle: AccountPickle = serde_json::from_str(serialized)
        .map_err(|e| CryptoError::Protocol(e.to_string()))?;
    Ok(Account::from_pickle(pickle))
}

/// Serialize an established Olm session for encrypted local persistence.
pub fn pickle_session(session: &Session) -> Result<String, CryptoError> {
    serde_json::to_string(&session.pickle())
        .map_err(|e| CryptoError::Protocol(e.to_string()))
}

/// Restore an Olm session from serialized state. Caller must decrypt it first.
pub fn unpickle_session(serialized: &str) -> Result<Session, CryptoError> {
    let pickle: SessionPickle = serde_json::from_str(serialized)
        .map_err(|e| CryptoError::Protocol(e.to_string()))?;
    Ok(Session::from_pickle(pickle))
}

/// Creates an outbound Olm session and encrypts its first pre-key message.
/// Peer identity verification and signed-pre-key validation belong to the
/// higher-level key-directory protocol and are intentionally not implied here.
pub fn start_outbound_session(
    sender: &Account,
    recipient_curve25519: &str,
    recipient_one_time_key: &str,
    plaintext: &[u8],
) -> Result<(Session, String), CryptoError> {
    let identity = vodozemac::Curve25519PublicKey::from_base64(recipient_curve25519)
        .map_err(|e| CryptoError::InvalidKey(e.to_string()))?;
    let one_time = vodozemac::Curve25519PublicKey::from_base64(recipient_one_time_key)
        .map_err(|e| CryptoError::InvalidKey(e.to_string()))?;

    let mut session = sender
        .create_outbound_session(SessionConfig::version_1(), identity, one_time)
        .map_err(|e| CryptoError::Protocol(e.to_string()))?;
    let message = session
        .encrypt(plaintext)
        .map_err(|e| CryptoError::Protocol(e.to_string()))?;
    let envelope = serde_json::to_string(&message)
        .map_err(|e| CryptoError::InvalidEnvelope(e.to_string()))?;

    Ok((session, envelope))
}

/// Establishes the matching inbound session from a received pre-key envelope.
pub fn accept_inbound_session(
    receiver: &mut Account,
    sender_curve25519: &str,
    envelope_json: &str,
) -> Result<(Session, Vec<u8>), CryptoError> {
    let sender_identity = vodozemac::Curve25519PublicKey::from_base64(sender_curve25519)
        .map_err(|e| CryptoError::InvalidKey(e.to_string()))?;
    let message: OlmMessage = serde_json::from_str(envelope_json)
        .map_err(|e| CryptoError::InvalidEnvelope(e.to_string()))?;

    let OlmMessage::PreKey(pre_key) = message else {
        return Err(CryptoError::UnexpectedNormalMessage);
    };

    let result = receiver
        .create_inbound_session(SessionConfig::version_1(), sender_identity, &pre_key)
        .map_err(|e| CryptoError::Protocol(e.to_string()))?;

    Ok((result.session, result.plaintext))
}

/// Encrypts a subsequent message in an established Olm session.
pub fn encrypt_message(session: &mut Session, plaintext: &[u8]) -> Result<String, CryptoError> {
    let message = session
        .encrypt(plaintext)
        .map_err(|e| CryptoError::Protocol(e.to_string()))?;
    serde_json::to_string(&message).map_err(|e| CryptoError::InvalidEnvelope(e.to_string()))
}

/// Decrypts a subsequent normal Olm message in an established session.
pub fn decrypt_message(session: &mut Session, envelope_json: &str) -> Result<Vec<u8>, CryptoError> {
    let message: OlmMessage = serde_json::from_str(envelope_json)
        .map_err(|e| CryptoError::InvalidEnvelope(e.to_string()))?;
    let OlmMessage::Normal(normal) = message else {
        return Err(CryptoError::UnexpectedPreKey);
    };

    session
        .decrypt(&OlmMessage::Normal(normal))
        .map_err(|e| CryptoError::Protocol(e.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn two_independent_devices_establish_and_exchange_messages() {
        let (alice, alice_public) = create_device_keys(0);
        let (mut bob, bob_public) = create_device_keys(1);
        let bob_otk = &bob_public.one_time_keys.first().expect("Bob OTK").key;

        let (mut alice_session, first_envelope) = start_outbound_session(
            &alice,
            &bob_public.curve25519,
            bob_otk,
            b"hello from Alice",
        )
        .expect("outbound session");

        let (mut bob_session, first_plaintext) = accept_inbound_session(
            &mut bob,
            &alice_public.curve25519,
            &first_envelope,
        )
        .expect("inbound session");

        assert_eq!(first_plaintext, b"hello from Alice");
        assert_eq!(alice_session.session_id(), bob_session.session_id());

        let reply = encrypt_message(&mut bob_session, b"hello from Bob").expect("encrypt reply");
        let reply_plaintext = decrypt_message(&mut alice_session, &reply).expect("decrypt reply");
        assert_eq!(reply_plaintext, b"hello from Bob");
    }

    #[test]
    fn tampered_ciphertext_is_rejected() {
        let (alice, alice_public) = create_device_keys(0);
        let (mut bob, bob_public) = create_device_keys(1);
        let bob_otk = &bob_public.one_time_keys.first().expect("Bob OTK").key;

        let (mut alice_session, first_envelope) = start_outbound_session(
            &alice,
            &bob_public.curve25519,
            bob_otk,
            b"initial message",
        )
        .expect("outbound session");
        let (mut bob_session, _) = accept_inbound_session(
            &mut bob,
            &alice_public.curve25519,
            &first_envelope,
        )
        .expect("inbound session");

        let reply = encrypt_message(&mut bob_session, b"authentic reply").expect("encrypt");
        let parsed: OlmMessage = serde_json::from_str(&reply).expect("parse envelope");
        let (kind, mut bytes) = parsed.to_parts();
        let last = bytes.len() - 1;
        bytes[last] ^= 0x01;
        let modified = OlmMessage::from_parts(kind, &bytes).expect("valid framing");
        let modified_json = serde_json::to_string(&modified).expect("serialize modified");

        assert!(decrypt_message(&mut alice_session, &modified_json).is_err());
    }

    #[test]
    fn published_one_time_keys_are_signed_and_verifiable() {
        let (_account, public) = create_device_keys(3);
        assert_eq!(public.one_time_keys.len(), 3);
        for key in &public.one_time_keys {
            verify_signed_one_time_key(&public.ed25519, &public.curve25519, key).expect("valid OTK signature");
        }
        let mut tampered = public.one_time_keys[0].clone();
        tampered.key.push('A');
        assert!(verify_signed_one_time_key(&public.ed25519, &public.curve25519, &tampered).is_err());
    }

    #[test]
    fn one_time_key_signature_is_bound_to_curve25519_device_identity() {
        let (_account, public) = create_device_keys(1);
        let key = public.one_time_keys.first().expect("one-time key");
        verify_signed_one_time_key(&public.ed25519, &public.curve25519, key)
            .expect("signature must verify for the original Curve25519 identity");

        let (_other_account, other_public) = create_device_keys(0);
        assert!(
            verify_signed_one_time_key(&public.ed25519, &other_public.curve25519, key).is_err(),
            "a valid Ed25519 signature must not authorize substituting another Curve25519 identity"
        );
    }

    #[test]
    fn one_time_key_batches_can_be_replenished_after_publication() {
        let (mut account, public) = create_device_keys(2);
        assert_eq!(public.one_time_keys.len(), 2);
        mark_keys_as_published(&mut account);

        let next_batch = generate_additional_one_time_keys(&mut account, 3);
        assert_eq!(next_batch.len(), 3);
        for key in &next_batch {
            verify_signed_one_time_key(&public.ed25519, &public.curve25519, key)
                .expect("replenished OTK must bind to the same device identity");
        }

        mark_keys_as_published(&mut account);
        assert!(generate_additional_one_time_keys(&mut account, 0).is_empty());
    }

    #[test]
    fn account_and_session_pickles_restore_protocol_state() {
        let (alice, alice_public) = create_device_keys(0);
        let (mut bob, bob_public) = create_device_keys(1);
        let bob_otk = &bob_public.one_time_keys[0].key;
        let (mut alice_session, first) = start_outbound_session(
            &alice, &bob_public.curve25519, bob_otk, b"persist me"
        ).expect("start session");
        let (mut bob_session, received) = accept_inbound_session(
            &mut bob, &alice_public.curve25519, &first
        ).expect("accept session");
        assert_eq!(received, b"persist me");

        let alice_account_restored = unpickle_account(&pickle_account(&alice).unwrap()).unwrap();
        assert_eq!(alice_account_restored.identity_keys(), alice.identity_keys());
        let mut bob_session_restored = unpickle_session(&pickle_session(&bob_session).unwrap()).unwrap();
        let alice_session_restored = unpickle_session(&pickle_session(&alice_session).unwrap()).unwrap();

        let reply = encrypt_message(&mut bob_session_restored, b"after restore").unwrap();
        let mut alice_restored = alice_session_restored;
        assert_eq!(decrypt_message(&mut alice_restored, &reply).unwrap(), b"after restore");
    }

    #[test]
    fn malformed_public_keys_are_rejected() {
        let (alice, _) = create_device_keys(0);
        let result = start_outbound_session(&alice, "not-a-key", "not-a-key", b"hello");
        assert!(result.is_err());
    }
}
