# Nexura Calls — architecture and migration plan

Status: design baseline, not yet deployed.

## Goals

- Keep Nexura's existing Android UI, accounts, chats, message storage, and push integration.
- Build a Nexura-owned calling control plane and operate the media plane on infrastructure controlled by Nexura.
- Support 1:1 audio/video first; design room and participant abstractions so group calls can follow.
- Keep media end-to-end encrypted so the media server forwards ciphertext and cannot decrypt audio/video.
- Recover from network changes and Android background/foreground transitions without leaving phantom call UI.
- Do not use the user's HP Pavilion g6 / Ubuntu Coturn machine for Nexura Calls. The user explicitly wants to retire that machine from this project.

## What exists today (verified 2026-11-11)

- Android uses the native WebRTC library (io.github.webrtc-sdk:android).
- CallCoordinator owns call lifecycle and exchanges offer/answer/ICE.
- CallSignalingClient uses private Supabase Realtime user topics for signaling.
- NexuraCallEngine creates one WebRTC PeerConnection per call; current media path is peer-to-peer, not an SFU room.
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

### Media plane (new Nexura-controlled infrastructure)

Recommended first implementation: self-host the open-source LiveKit Server as the SFU media engine, operated by Nexura on a new dedicated Linux VM/VPS. This is not LiveKit Cloud: Nexura controls the deployment, domain, API keys, server configuration and operational data.

Hosting decision:
- Provision a new VPS specifically for Nexura Calls; do not use the HP Pavilion g6 / home Ubuntu machine.
- Do not assume the existing Render service or Supabase Edge Functions can host the media plane.
- Keep the new media plane separate from production chat/backend workloads initially, so media load cannot destabilize messaging.
- Choose the VPS size after estimating concurrent calls, video quality, bandwidth and region latency. Start with a staging instance and load-test before production sizing.

Why this foundation:
- production-grade SFU and room/participant lifecycle;
- Android SDK and reconnect/network-state facilities;
- integrated ICE/TURN options;
- built-in media E2EE support, with key distribution remaining the application's responsibility.

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

Supabase Edge Functions are serverless control-plane functions, not long-running UDP media servers. The currently connected Render web service belongs to another project and is not configured as a Nexura media server.

A self-hosted SFU on the new VPS needs:
- stable public IP and DNS name;
- trusted TLS certificate;
- inbound UDP media ports (default LiveKit range 50000–60000/UDP);
- TCP fallback (default 7881/TCP);
- HTTPS/WebSocket endpoint (typically 443/TCP);
- TURN/TLS or TURN/UDP reachability for restrictive networks;
- enough bandwidth and CPU for relayed media.

The legacy home Coturn host is explicitly excluded. A new managed TURN service or TURN deployment on suitable VPS infrastructure must be evaluated separately; do not keep the old home machine as a dependency.

## Delivery stages

1. Inventory (no production changes): inspect current Supabase functions/secrets and GitHub build configuration without revealing secret values; identify the existing TURN dependency and plan its replacement. No access to or dependency on the HP Pavilion g6 is required.
2. Server staging: provision a new VPS in a suitable European region; configure TLS, media ports, API key/secret and monitoring. No production traffic yet.
3. Control plane: add authenticated short-lived room-token issuance and call-room authorization in Supabase.
4. Android prototype: add the LiveKit Android SDK behind a transport interface and implement 1:1 audio.
5. E2EE: integrate room media E2EE with Nexura's verified device-key exchange; fail closed if keys are unavailable.
6. Video and background: add video, FCM incoming calls, foreground service, lock-screen notification actions and network handoff.
7. Acceptance tests: two physical Android devices on different networks; Wi-Fi to cellular handoff; screen locked; app backgrounded; UDP blocked/TCP/TURN fallback; accept/decline/busy/timeout; reconnect; verify server cannot decrypt media.
8. Cutover: enable for a small test group, monitor, then migrate the rest. Retain rollback to the existing P2P path until stability is demonstrated. Retire the legacy TURN endpoint only after the new route is proven and the app no longer requests its credentials.

## Non-goals for the first release

- Do not write a custom codec, RTP stack, ICE implementation, DTLS implementation or cryptographic primitive.
- Do not change chat, authentication, message encryption or unrelated app UI.
- Do not put service-role keys, SFU API secrets or plaintext media keys in the APK.
- Do not promise perfect connectivity; test against real carrier and firewall conditions.

## Release gate

No production cutover until two-device cross-network audio and video tests pass, E2EE key exchange is reviewed, Android background/lock-screen behavior is validated, and rollback is available.
