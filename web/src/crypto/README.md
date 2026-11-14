# Browser Olm adapter

`olm.js` wraps the shared Rust/WASM vodozemac core. Protocol state is persisted through `secure-store.js` using AES-GCM and a non-exportable WebCrypto key stored by IndexedDB.

The module is not connected to the React chat screen or Supabase transport yet. It deliberately requires the caller to assert that the peer fingerprint was verified, validates the signed one-time key, stores the next ratchet state and encrypted outbox item in a single IndexedDB transaction, and never provides plaintext fallback.

Before enabling it in the UI, complete:
1. Supabase device publication and membership-checked atomic OTK consumption from the current Android API.
2. Real fingerprint comparison UX; do not trust a caller-supplied boolean as a security boundary.
3. Two independent browser accounts and Android↔browser interoperability tests.
4. Duplicate/out-of-order/replay/tamper tests and reload/crash recovery tests.
5. Group fan-out, encrypted media, attachment key distribution and device revocation UX.
6. Independent security review.

Never use this as evidence that web E2EE is production-ready until those gates pass.
