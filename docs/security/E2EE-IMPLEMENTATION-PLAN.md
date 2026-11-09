# Nexura E2EE implementation plan

## Scope and safety boundary

This work is being developed on `security/e2ee-phase-2`. It is not deployed to the production Supabase project and does not alter production database policies, data, or secrets.

## Protocol direction

- Direct messages first; groups and multi-device synchronization follow as separate milestones.
- Use a maintained, independently reviewed protocol implementation; do not design a custom ratchet or key exchange.
- Required protocol properties: asynchronous pre-key establishment, forward secrecy, post-compromise recovery, authenticated identity keys, replay/out-of-order handling, and multi-device session management.
- Evaluate Signal Protocol (PQXDH + Double Ratchet / Sesame) as the leading candidate, but do not integrate it until its current API stability, maintenance model, and AGPL-3.0 licensing implications are reviewed. The upstream libsignal README explicitly says use outside Signal is unsupported and APIs may change.
- Do not silently introduce a third-party repackaging/fork of libsignal.

## Proposed data model (design only; no production migration)

- Per-device identity key pair; private material generated and protected locally using Android Keystore-backed wrapping/encryption.
- Public identity key, signed pre-key, and one-time pre-keys published to a server-side key directory.
- Server stores public key material only. Private identity/session state stays on the device.
- Message envelope stores protocol version, sender device ID, recipient device/session routing metadata, ciphertext, nonce/header as required by the selected protocol, and opaque timestamps/IDs.
- Plaintext must never be included in push payloads, logs, analytics, database fields, or object storage.
- Device changes require key verification and explicit handling of old sessions; key changes should be visible to contacts.

## Rollout constraints

1. Ship generic push notifications first.
2. Build and test the cryptographic storage/session adapter in a non-production branch.
3. Add public pre-key directory and narrowly scoped RLS only after protocol/API design is validated.
4. Enable E2EE for new direct chats behind a capability/version gate. Do not silently mix plaintext and ciphertext.
5. Keep legacy history readable only as an explicitly marked legacy mode; no claim that historical plaintext becomes encrypted retroactively.
6. Encrypt media client-side with per-attachment random keys; send keys only inside E2EE message payloads.
7. Add two-device, reinstall, lost-device, identity-change, replay, out-of-order, and backup/restore tests before rollout.

## Current confirmed gap

The current app writes message text directly to `messages.content` and uploads original image bytes to Storage. The current production push function previously accepted a preview string and forwarded it to FCM. This branch removes preview forwarding from the Android client and provides a privacy-safe function source; production deployment is intentionally not performed here.

## Licensing note

Upstream `signalapp/libsignal` is AGPL-3.0 and states that use outside Signal is unsupported. Obtain a licensing decision before shipping it in a distributed proprietary app. Do not assume a Maven artifact or fork changes those terms.


## Cryptographic library evaluation (2026-11-09)

### Candidate A: official Signal libsignal

- Implements the Signal Protocol and Double Ratchet, and publishes Android native artifacts.
- License is AGPL-3.0.
- Upstream explicitly says use outside Signal is unsupported and APIs/bridge layers can change without notice.
- Decision: do not add it to Nexura until the project owner makes an explicit licensing decision and obtains legal review if the app will be distributed under a non-AGPL model.

### Candidate B: Matrix vodozemac

- Apache-2.0 Rust implementation of Olm (1:1 Double Ratchet) and Megolm (group ratchet); also provides SAS verification.
- Upstream documents a Least Authority security audit with no significant findings.
- It is a Rust library, not a ready-made Kotlin SDK. Android integration requires a maintained Rust/UniFFI or JNI bridge, native builds for supported Android ABIs, wrapper tests, and careful encrypted persistence of ratchet state.
- Olm/Megolm are Matrix protocols, not Signal PQXDH. Do not describe the result as Signal Protocol or post-quantum secure.
- Decision: preferred candidate for a technical proof of concept if Nexura accepts Matrix Olm/Megolm semantics and the team can own the Android FFI bridge. No production dependency is added yet.

### Candidate C: OpenMLS

- Rust implementation of the IETF MLS protocol (RFC 9420), a plausible standards-based path for group encryption.
- Android targets are described upstream as built but unsupported/not tested; a Kotlin wrapper and device/key lifecycle still need engineering.
- Decision: keep as a separate group-encryption candidate; do not combine it with Olm/Megolm until a protocol architecture is chosen.

### Recommended next engineering milestone

Create an isolated native crypto module and prove, with automated tests, only these operations before touching chat persistence:
1. Create two independent device identities.
2. Publish and consume a signed pre-key bundle without exposing private keys.
3. Establish an outbound/inbound 1:1 session.
4. Encrypt/decrypt a message and reject tampered ciphertext.
5. Persist and restore ratchet state using an authenticated encrypted pickle.
6. Demonstrate forward secrecy across multiple message exchanges and correct handling of duplicate/out-of-order delivery.
7. Build and run tests for arm64-v8a and armeabi-v7a (and x86_64 emulator if supported).

The wrapper must expose only high-level safe operations. It must not expose raw private key bytes to Kotlin. Persisted protocol state must be encrypted using a key protected by Android Keystore; the server stores public pre-key material and ciphertext only.

### Important deployment note

The generic-push source in this branch is not deployed to Supabase. Production continues running the currently deployed Edge Function until an explicitly approved deployment.


## Implementation status (2026-11-09)

### Implemented in the isolated branch

- Rust Olm core uses vodozemac 0.11.2.
- Public one-time keys are signed with the device Ed25519 identity key; signature verification is exposed.
- Account and session pickle serialization/restoration APIs are exposed. Pickles are secret and must be encrypted before persistence.
- Android JNI crate exposes device creation, signature verification, outbound/inbound session establishment, message encrypt/decrypt, and explicit acknowledgement after public OTK upload.
- Kotlin `E2eeClient` wraps those operations and persists every returned account/session pickle using `E2eeStateStore`.
- E2EE local state is namespaced per authenticated user, with a separate Android Keystore AES-GCM key alias for each user.
- A design-only SQL file proposes public device-key tables and peer-conversation RLS. It is not a Supabase migration and has not been applied.
- Rust protocol tests and host compilation of the JNI bridge passed in GitHub Actions for commit `4c2a9fdfdb84e18932c196627c74ee2d2f873c78`.

### Still blocked / not production-ready

- Android cross-compilation and APK packaging of the JNI library are still being validated in CI.
- No Supabase key publication or atomic one-time-key consumption RPC exists yet.
- No identity fingerprint / safety-number verification UX or key-change warning exists.
- Kotlin facade is not connected to `NexuraApi`, message persistence, or chat UI.
- No multi-device protocol, group encryption, media encryption, or interoperability testing.
- Existing message rows remain plaintext; historical content is not retroactively encrypted.
- No production database, Edge Function, or deployment was changed.

### Required gates before enabling E2EE in chats

1. Pass Android NDK cross-compilation and APK build for supported ABIs.
2. Run JNI methods on an Android emulator/device, including process restart and state restoration.
3. Implement Supabase public-key publication plus atomic OTK consumption with conversation membership checks.
4. Add user-visible device identity verification and key-change handling.
5. Integrate encrypted envelopes into a new message format without treating legacy plaintext as ciphertext.
6. Verify two-device round trips, replay/tamper rejection, offline delivery, duplicate/out-of-order messages, and logout/account switching.
7. Complete an independent protocol/security review before enabling by default.


### Key-directory client integration added (still awaiting server schema deployment)

- The Android client now registers its public device identity after sign-in when the E2EE key-directory tables are available. Registration failure is isolated and does not block normal chat access.
- Device registration is insert-only/idempotent; the client reads the row back and refuses to silently accept an identity-key mismatch.
- Signed one-time keys are uploaded in batches with stable IDs. The client reads them back and verifies exact key/signature equality before acknowledging publication in the local Olm account.
- Failed uploads remain pending locally and can be retried without changing key IDs.
- The client replenishes the server-side pool when fewer than 10 unused keys remain.
- A membership-checked RPC atomically consumes a recipient's one-time key using row locking. The Android API wrapper is present but no message sending path uses it yet.
- Direct updates to one-time-key rows have been removed from the design grants; clients cannot set `consumed_at` themselves.
- The schema file remains design-only. It has not been applied to production. A Supabase development branch would cost approximately $0.01344/hour and requires cost approval before creation.

