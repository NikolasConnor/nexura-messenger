# Nexura crypto core

This crate is an isolated Rust proof of concept for 1:1 Olm sessions using vodozemac.

## Current API

- Create an Olm account and public Curve25519 / Ed25519 identity keys.
- Generate one-time Curve25519 keys and sign each key with the account Ed25519 identity key.
- Verify the signature before using a fetched one-time key.
- Establish outbound and inbound Olm sessions.
- Encrypt/decrypt subsequent Olm messages.
- Serialize account/session pickles for encrypted local persistence.

## Security boundary

- Upload only public identity keys and signed one-time keys.
- Never upload account/session pickles or private keys.
- Pickles are sensitive secret state. Encrypt them with `E2eeStateStore` before writing to disk.
- Verify device identity fingerprints through an authenticated out-of-band process before establishing trust.
- Do not use this proof of concept as a production-ready E2EE system yet. It has no server key directory, signed-pre-key rotation, device verification UI, JNI/UniFFI bridge, group encryption, or end-to-end integration tests against the Android app.

## Local tests

Run `cargo test` from this directory.
