# Nexura Messenger

Android-native messaging client backed by the existing Supabase project.

## Current MVP
- Email/password sign-up and sign-in through Supabase Auth
- Encrypted local session storage
- Profile lookup and direct conversation creation
- Conversation list and text messages
- Manual refresh for inbox and open conversation
- GitHub Actions debug APK build

## Backend
Supabase project: `lyhzawblxcvpgpjrkspc`.
The app uses the project's publishable key only. Never add a service-role key to this Android app.

## Build
Requires JDK 17, Android SDK 35, and Gradle 8.9:
```bash
gradle assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Known MVP limitations
Realtime live updates, background push notifications, media attachments, group creation UI, Google OAuth, and email delivery configuration are not yet included. Email confirmation depends on the Supabase Auth email provider configuration.
