# Nexura Calls — architecture and migration plan

Status (2026-11-11): Android 1:1 audio/video path is wired to LiveKit Cloud in `livekit-cloud-android-studio`; GitHub Actions produced a successful debug APK at commit `ccc42ba3c03b6ef78432c98f4f2567eeb00b93a4`. This is a test build, not a production release. LiveKit media E2EE key distribution and two-device network acceptance tests are still outstanding.

## Goals

- Keep Nexura's existing Android UI, accounts, chats, message storage, and push integration.
- Build a Nexura-owned calling control plane and operate the media plane on infrastructure controlled by Nexura.
- Support 1:1 audio/video first; design room and participant abstractions so group calls can follow.
- Keep media end-to-end encrypted so the media server forwards ciphertext and cannot decrypt audio/video.
- Recover from network changes and Android background/foreground transitions without leaving phantom call UI.
- Do not use the user's HP Pavilion g6 / Ubuntu Coturn machine for Nexura Calls. The user explicitly wants to retire that machine from this project.

## What exists today (verified 2026-11-11)

- `CallCoordinator` now owns the 1:1 call lifecycle and creates a LiveKit `Room` after the recipient accepts.
- `NexuraApi.liveKitRoomCredentials` obtains a short-lived room token from the authenticated Supabase Edge Function.
- `CallSignalingClient` uses private Supabase Realtime user topics for call-control messages (offer, answer, decline, busy, end); audio/video media is carried by LiveKit Cloud, not by this signaling socket.
- `NexuraCallEngine` and the native WebRTC dependency remain in the repository as a rollback/reference path; they are not the active media transport in the LiveKit `CallCoordinator` path.
- Android `Room.e2eeOptions` is not yet configured. LiveKit transport encryption is active, but Nexura call-media E2EE is not yet enabled.
- Supabase project lyhzawblxcvpgpjrkspc provides Auth, Postgres, Edge Functions and FCM call-push integration.
- Deployed Edge Functions include send-call-push and turn-credentials.
- turn-credentials currently returns the legacy TURN endpoint 37.230.242.232:3478 and creates short-lived Coturn REST credentials from the server-side TURN_SHARED_SECRET. Treat this as legacy only; do not depend on it for the new system and do not expose or copy its secret.
- The Render workspace currently exposes a separate Python web service dkd-miniapp-1 for another project. It is not the Nexura media server.
- E2eeMediaCrypto.kt encrypts uploaded media files; it is not a live audio/video frame encryption implementation.

## Proposed Nexura Calls architecture

### Control plane (existing Supabase project)

Supabase remains responsible for:
- user authentication and conversation membership checks;
- creating and ending call records / room state;
- push notifications through the existing send-call-push flow;
- issuing short-lived media-server access tokens through a new authenticated Edge Function;
- storing call metadata only (participants, timestamps, status, call type).

The token function must verify the caller's Supabase JWT and that caller and recipient are members of the same conversation. It must mint a short-lived token scoped to one room and one identity. Media-server API secrets must never ship in the Android APK.

### Media plane (LiveKit Cloud)

The selected media plane is the user's existing LiveKit Cloud project. LiveKit Cloud operates the SFU; Nexura keeps ownership of the app-level call lifecycle and authorization. Do not provision a VPS or use the HP Pavilion g6 / home Ubuntu machine for this migration.

- Keep media routing on LiveKit Cloud and control-plane data on the existing Supabase project.
- The LiveKit API key and secret are stored only as Supabase Edge Function secrets; they must never be shipped in the APK or committed to GitHub.
- The Android client receives only a short-lived, room-scoped participant token and the project's WSS URL.
- Confirm the LiveKit Cloud plan's quotas, region and any TURN/network fallback limits before production rollout; do not assume unlimited or free capacity.

Nexura owns the product layer around it: call authorization, room naming, call invitations, participant policy, push flow, call history, key distribution, UI and diagnostics.

### End-to-end encryption

- Enable SDK media E2EE before connecting to a room.
- Generate call/room keys on trusted client devices, not on the media server.
- Distribute keys through the existing Nexura device-key trust system; do not return room keys from the token endpoint or store plaintext keys in Supabase.
- For 1:1 calls, encrypt the room key to the recipient's verified device key(s).
- For group calls, define explicit membership-change and key-rotation behavior before release.
- Keep transport encryption (TLS/DTLS-SRTP) enabled in addition to E2EE.
- Server-side signaling and operational metadata remain visible to the control plane; E2EE protects media content, not all metadata.

### Android migration

Keep the existing call UI and CallCoordinator responsibilities where practical. Introduce a transport abstraction, then implement a LiveKit-backed transport. Replace the current custom SDP/ICE WebSocket path only after the new path passes tests. Keep the old WebRTC implementation available behind a temporary debug/test switch during migration; do not delete it until the new implementation is verified.

Keep and improve:
- foreground call service and notification actions;
- incoming-call FCM wakeup;
- call state machine with idempotent accept/decline/end;
- reconnect on Wi-Fi/mobile handoff;
- connection diagnostics and call-quality telemetry without collecting media content.

## Hosting and network requirements

Supabase Edge Functions remain control-plane services and do not relay media. LiveKit Cloud operates the SFU endpoint and media networking. Nexura's Android client must connect to the exact WSS URL returned by the authenticated token function. Test real mobile carriers, restrictive Wi-Fi, TCP/TURN fallback and Wi-Fi-to-cellular handoff; do not assume every network path is available.

The legacy home Coturn host is explicitly excluded. A new managed TURN service or TURN deployment on suitable VPS infrastructure must be evaluated separately; do not keep the old home machine as a dependency.

## Delivery stages

1. Inventory (no production changes): inspect current Supabase functions/secrets and GitHub build configuration without revealing secret values; identify the existing TURN dependency and plan its replacement. No access to or dependency on the HP Pavilion g6 is required.
2. Cloud staging: verify the LiveKit Cloud project URL, API key/secret and project region; check plan quotas. No production traffic yet.
3. Control plane: deploy and verify the authenticated `livekit-token` Edge Function, which checks the Supabase user and direct-conversation membership before issuing a 10-minute room-scoped token.
4. Android 1:1 LiveKit prototype: implemented in `CallCoordinator`; debug APK built at commit `ccc42ba3c03b6ef78432c98f4f2567eeb00b93a4`. The old P2P engine remains as rollback/reference code.
5. E2EE: integrate LiveKit `E2EEOptions`/`BaseKeyProvider` with Nexura's verified Olm device-key exchange. Do not use a hard-coded, server-issued or unauthenticated shared key; fail closed if the remote device cannot be verified or a key cannot be securely delivered.
6. Acceptance tests: two physical Android devices on different networks; Wi-Fi to cellular handoff; screen locked; app backgrounded; UDP blocked/TCP/TURN fallback; accept/decline/busy/timeout; reconnect; verify the SFU cannot decrypt media.
7. Controlled cutover: enable for a small test group only after E2EE and acceptance tests pass. Retain rollback to the existing P2P path until stability is demonstrated. Retire the legacy TURN endpoint only after the new route is proven and the app no longer requests its credentials.

## Non-goals for the first release

- Do not write a custom codec, RTP stack, ICE implementation, DTLS implementation or cryptographic primitive.
- Do not change chat, authentication, message encryption or unrelated app UI.
- Do not put service-role keys, SFU API secrets or plaintext media keys in the APK.
- Do not promise perfect connectivity; test against real carrier and firewall conditions.

## Release gate

No production cutover until two-device cross-network audio and video tests pass, E2EE key exchange is reviewed, Android background/lock-screen behavior is validated, and rollback is available.


## Current test build

- Branch: `livekit-cloud-android-studio`
- Commit: `ccc42ba3c03b6ef78432c98f4f2567eeb00b93a4`
- GitHub Actions run: [43066029611](https://github.com/NikolasConnor/nexura-messenger/actions/runs/43066029611) — successful debug build
- Artifact: `nexura-debug-apk` (debug-signed; install for testing, not Play Store distribution)
- Not yet verified on physical devices or against the user's LiveKit Cloud project credentials.
