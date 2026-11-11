# Nexura Messenger

Android-native messaging client backed by the existing Supabase project.

## Current MVP
- Email/password sign-up and sign-in through Supabase Auth
- Encrypted local session storage
- Profile lookup and direct conversation creation
- Conversation list and text messages
- Manual refresh for inbox and open conversation
- Existing push and call-notification integration
- Voice/video call media transport migration to LiveKit SFU (feature branch)

## Backend
Supabase project: `lyhzawblxcvpgpjrkspc`.
The app uses the project's publishable key only. Never add a service-role key or LiveKit API secret to this Android app.

## Build in Android Studio
1. Download this branch as ZIP: **Code → Download ZIP**.
2. Extract the ZIP and open the extracted project root (the folder containing `settings.gradle.kts`) in Android Studio.
3. Use **JDK 17** for Gradle.
4. Install **Android SDK Platform 35** and Build Tools 35.0.0 from SDK Manager.
5. Allow Gradle to sync and download dependencies. LiveKit Android SDK is pinned to `2.30.0` and resolves from Maven Central.
6. Build using **Build → Make Project**, then **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

This repository currently does not include the Gradle Wrapper JAR/scripts. If Android Studio asks for a Gradle installation, select a local **Gradle 8.9** installation in Settings → Build, Execution, Deployment → Build Tools → Gradle. Alternatively, run `gradle assembleDebug` from the project root with Gradle 8.9 installed.

Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## LiveKit calls
- Android uses LiveKit Cloud for audio/video media transport.
- Supabase Realtime remains responsible for private call signaling (incoming offer, accept, decline, end).
- Supabase Edge Function `livekit-token` verifies the signed-in user and that both participants belong to the same direct conversation before issuing a short-lived room token.
- Configure `LIVEKIT_URL`, `LIVEKIT_API_KEY`, and `LIVEKIT_API_SECRET` only in Supabase Edge Function Secrets. Never add the API secret to source control, `local.properties`, or Android resources.
- The old P2P WebRTC engine and TURN function are retained for rollback during testing; they are not removed in this feature branch.
- LiveKit transport encryption is enabled by the SDK, but app-level end-to-end encryption for live media has not yet been implemented.

## Build verification status
The feature branch contains the Android LiveKit integration and its Android Studio build instructions. A successful CI APK build has not yet been confirmed; build and test the project locally before distributing it.

## WebRTC TURN configuration

Calls request short-lived ICE credentials from the authenticated Supabase Edge Function
`turn-credentials`. The Turnix project API token must be configured as the Supabase
Edge Function secret `TURNIX_API_TOKEN` (Supabase Dashboard → Edge Functions → Secrets).
Never put this token in the Android app, GitHub Actions, or source control. The app receives
only short-lived ICE server credentials for each call.
