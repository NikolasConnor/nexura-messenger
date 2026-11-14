//! Browser adapter for the same vodozemac Olm core used by Nexura Android.
//!
//! This module intentionally returns serialized account/session pickles to the
//! caller. The browser application MUST encrypt every pickle with a non-exportable
//! WebCrypto AES-GCM key before persisting it in IndexedDB. Never put pickles in
//! localStorage, URLs, logs, analytics, or Supabase.
//!
//! This is a protocol adapter, not a complete E2EE rollout. The caller must still
//! implement authenticated device registration, signed OTK publication/consumption,
//! fingerprint verification, durable ratchet transactions, replay handling and
//! fail-closed message transport before enabling chat sending.

use nexura_crypto_core::{
    accept_inbound_session as core_accept_inbound,
    create_device_keys as core_create_device,
    decrypt_message as core_decrypt,
    encrypt_message as core_encrypt,
    generate_additional_one_time_keys as core_generate_keys,
    mark_keys_as_published as core_mark_published,
    pickle_account as core_pickle_account,
    pickle_session as core_pickle_session,
    start_outbound_session as core_start_outbound,
    unpickle_account as core_unpickle_account,
    unpickle_session as core_unpickle_session,
    verify_signed_one_time_key as core_verify_otk,
    PublicDeviceKeys, SignedOneTimeKey,
};
use serde::Serialize;
use wasm_bindgen::prelude::*;

fn js_error(error: impl std::fmt::Display) -> JsValue {
    JsValue::from_str(&error.to_string())
}

fn to_js<T: Serialize>(value: &T) -> Result<JsValue, JsValue> {
    serde_wasm_bindgen::to_value(value).map_err(js_error)
}

#[derive(Serialize)]
struct DeviceCreated {
    account_pickle: String,
    public_keys: PublicDeviceKeys,
}

#[derive(Serialize)]
struct KeysGenerated {
    account_pickle: String,
    one_time_keys: Vec<SignedOneTimeKey>,
}

#[derive(Serialize)]
struct OutboundStarted {
    session_pickle: String,
    olm_envelope: String,
}

#[derive(Serialize)]
struct InboundAccepted {
    account_pickle: String,
    session_pickle: String,
    plaintext: Vec<u8>,
}

#[derive(Serialize)]
struct EncryptedMessage {
    session_pickle: String,
    olm_envelope: String,
}

#[derive(Serialize)]
struct DecryptedMessage {
    session_pickle: String,
    plaintext: Vec<u8>,
}

/// Create a new local Olm identity and its signed public one-time-key bundle.
/// The returned account_pickle is secret and must be encrypted before storage.
#[wasm_bindgen]
pub fn create_device(one_time_key_count: usize) -> Result<JsValue, JsValue> {
    if one_time_key_count > 50 {
        return Err(JsValue::from_str("one_time_key_count must be between 0 and 50"));
    }
    let (account, public_keys) = core_create_device(one_time_key_count);
    let account_pickle = core_pickle_account(&account).map_err(js_error)?;
    to_js(&DeviceCreated { account_pickle, public_keys })
}

/// Generate additional signed one-time keys without changing the device identity.
#[wasm_bindgen]
pub fn generate_one_time_keys(
    account_pickle: &str,
    requested_count: usize,
) -> Result<JsValue, JsValue> {
    if requested_count > 50 {
        return Err(JsValue::from_str("requested_count must be between 0 and 50"));
    }
    let mut account = core_unpickle_account(account_pickle).map_err(js_error)?;
    let one_time_keys = core_generate_keys(&mut account, requested_count);
    let account_pickle = core_pickle_account(&account).map_err(js_error)?;
    to_js(&KeysGenerated { account_pickle, one_time_keys })
}

/// Acknowledge key publication only after the server confirms durable storage.
#[wasm_bindgen]
pub fn acknowledge_published_keys(account_pickle: &str) -> Result<String, JsValue> {
    let mut account = core_unpickle_account(account_pickle).map_err(js_error)?;
    core_mark_published(&mut account);
    core_pickle_account(&account).map_err(js_error)
}

/// Verify a signed one-time key against the advertised Ed25519 and Curve25519 identity.
#[wasm_bindgen]
pub fn verify_one_time_key(
    ed25519_identity_key: &str,
    curve25519_identity_key: &str,
    one_time_key: &str,
    signature: &str,
) -> bool {
    core_verify_otk(
        ed25519_identity_key,
        curve25519_identity_key,
        &SignedOneTimeKey { key: one_time_key.to_owned(), signature: signature.to_owned() },
    ).is_ok()
}

/// Establish an outbound session. Returns the updated ratchet pickle and first pre-key envelope.
#[wasm_bindgen]
pub fn start_outbound_session(
    account_pickle: &str,
    recipient_curve25519: &str,
    recipient_one_time_key: &str,
    plaintext: &[u8],
) -> Result<JsValue, JsValue> {
    let account = core_unpickle_account(account_pickle).map_err(js_error)?;
    let (session, olm_envelope) = core_start_outbound(
        &account, recipient_curve25519, recipient_one_time_key, plaintext,
    ).map_err(js_error)?;
    let session_pickle = core_pickle_session(&session).map_err(js_error)?;
    to_js(&OutboundStarted { session_pickle, olm_envelope })
}

/// Accept an inbound pre-key envelope and return both updated account and session state.
#[wasm_bindgen]
pub fn accept_inbound_session(
    account_pickle: &str,
    sender_curve25519: &str,
    olm_envelope: &str,
) -> Result<JsValue, JsValue> {
    let mut account = core_unpickle_account(account_pickle).map_err(js_error)?;
    let (session, plaintext) = core_accept_inbound(
        &mut account, sender_curve25519, olm_envelope,
    ).map_err(js_error)?;
    let account_pickle = core_pickle_account(&account).map_err(js_error)?;
    let session_pickle = core_pickle_session(&session).map_err(js_error)?;
    to_js(&InboundAccepted { account_pickle, session_pickle, plaintext })
}

/// Encrypt one message and return the next ratchet state. Persist state atomically with the outbox.
#[wasm_bindgen]
pub fn encrypt_message(
    session_pickle: &str,
    plaintext: &[u8],
) -> Result<JsValue, JsValue> {
    let mut session = core_unpickle_session(session_pickle).map_err(js_error)?;
    let olm_envelope = core_encrypt(&mut session, plaintext).map_err(js_error)?;
    let session_pickle = core_pickle_session(&session).map_err(js_error)?;
    to_js(&EncryptedMessage { session_pickle, olm_envelope })
}

/// Decrypt one Olm envelope and return the next ratchet state.
#[wasm_bindgen]
pub fn decrypt_message(
    session_pickle: &str,
    olm_envelope: &str,
) -> Result<JsValue, JsValue> {
    let mut session = core_unpickle_session(session_pickle).map_err(js_error)?;
    let plaintext = core_decrypt(&mut session, olm_envelope).map_err(js_error)?;
    let session_pickle = core_pickle_session(&session).map_err(js_error)?;
    to_js(&DecryptedMessage { session_pickle, plaintext })
}
