# Nexura browser crypto adapter

This crate exposes the existing `nexura-crypto-core` vodozemac Olm implementation to WebAssembly. It is designed to share the same Olm v1 protocol implementation as Android, not to introduce a second JavaScript cryptography implementation.

## Build

```sh
rustup target add wasm32-unknown-unknown
cargo install wasm-pack
wasm-pack build crypto-wasm --target web --out-dir ../web/src/crypto/pkg
```

## Security contract

- `account_pickle` and `session_pickle` are secret protocol state.
- The browser app must encrypt them with a non-exportable WebCrypto AES-GCM key before writing to IndexedDB.
- Never store pickles or private key material in localStorage, cookies, Supabase, URLs, logs, analytics or push payloads.
- Ratchet state and the corresponding encrypted outbox/message record must be committed atomically; do not advance a ratchet if the message cannot be durably queued.
- Verify the sender/recipient device identity and the signed one-time key before session establishment.
- A fingerprint must be explicitly verified by users out-of-band; first-use trust is not proof of identity.
- No plaintext fallback is permitted. Any crypto, storage, key-directory, or verification error must block sending.
- This adapter only supplies Olm 1:1 session primitives. It does not itself implement Supabase key-directory transactions, identity verification UX, browser storage, multi-device fan-out, group policy, media encryption, or a complete safe messaging client.

Do not enable message sending based only on a successful WebAssembly build. Complete the browser storage adapter, key-directory parity, two-account interoperability tests, tamper/replay tests, restart/recovery tests, and a security review first.
