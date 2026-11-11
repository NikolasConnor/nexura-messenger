# Nexura Messenger

Android-native messaging client built with Kotlin and Jetpack Compose, backed by Supabase.

## Included in this branch

- Supabase Auth and encrypted local session storage.
- Direct and group messaging, profile lookup and contact matching.
- Push-notification integration.
- Existing call invitation/signaling flow through Supabase Realtime.
- LiveKit Android SDK media-room integration for 1:1 audio/video calls.
- Authenticated Supabase Edge Function `livekit-token` for short-lived, conversation-scoped LiveKit access.
- Legacy WebRTC/TURN implementation retained during migration for rollback.
- E2EE client/key-directory work and encrypted message/media support.

## Backend

Supabase project: `lyhzawblxcvpgpjrkspc`.

The Android app uses the project's public/publishable key only. Never place a Supabase service-role key or LiveKit API secret in the Android app.

### LiveKit Cloud secrets

Configure these in Supabase Dashboard → Edge Functions → Secrets:

- `LIVEKIT_URL`
- `LIVEKIT_API_KEY`
- `LIVEKIT_API_SECRET`

The API secret must remain server-side. The `livekit-token` Edge Function validates the Supabase user and direct-conversation membership, then issues a short-lived room token.

## Build

Requires JDK 17, Android SDK Platform 35 and Gradle 8.9.

```bash
gradle --no-daemon assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions also builds a debug APK for pushes to `main` and `feature/voice-video-calls`; download the `nexura-debug-apk` artifact from the workflow run.

For Android Studio setup and LiveKit test steps, see [Android Studio build guide](docs/calls/ANDROID-STUDIO-BUILD.md).

## Call rollout status

The LiveKit client and server token endpoint are present, but a successful two-device, cross-network acceptance test is still required. Keep the legacy P2P/TURN path until audio, video, reconnect, background behavior and network fallback have been verified.

Transport encryption (TLS/DTLS-SRTP) is not the same as Nexura end-to-end media encryption. Do not claim live-call E2EE until client-side media encryption and device-key distribution are implemented and tested.

## Existing TURN

The authenticated `turn-credentials` function is retained as a rollback path during migration. Do not remove its configuration until LiveKit calls are verified on real devices.
