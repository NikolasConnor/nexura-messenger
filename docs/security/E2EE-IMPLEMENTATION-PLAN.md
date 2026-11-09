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
