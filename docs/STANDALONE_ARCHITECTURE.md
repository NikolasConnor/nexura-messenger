# Nexura Messenger — standalone product architecture

## Product rule

Nexura is an independent messenger. The Android client must communicate only with Nexura-owned services. It must not depend on Telegram accounts, MTProto, Telegram API credentials, Telegram servers, or Telegram branding.

The Telegram Android fork is not the product foundation for this implementation and must not be used for Nexura APK releases.

## Canonical codebase

- Repository: `NikolasConnor/nexura-messenger`
- Working branch: `feature/standalone-nexura-messenger`
- Android: Kotlin, Jetpack Compose, Android application ID `com.nexura.messenger`
- Backend: Supabase project `lyhzawblxcvpgpjrkspc` (Auth, Postgres, Realtime/Edge Functions/Storage as configured)

## Existing implementation (audited 2026-11-28)

### Android client
- Email/password and phone-code authentication, Google OAuth handoff.
- Encrypted local session storage.
- Chat list, direct and group chat creation, contact matching and user search.
- Text messages, replies, edit/delete, image attachment upload/display.
- Push-token registration and message push invocation.
- Profile name/avatar editing.
- A single large `MainActivity.kt` currently owns most navigation and screen state; this should be split into feature-level Compose screens and ViewModels before the UI grows further.

### Backend
- Existing RLS-protected tables include profiles, conversations, members, messages, attachments, device tokens, presence, reactions, pins, blocks, typing, stories, profile photos/comments/likes, saved messages, notification preferences, and E2EE key/state-backup records.
- Existing Edge Functions include LiveKit token issuance, chat/call push, TURN credentials, and cloud-history support.
- The schema is significantly ahead of the Android UI. A table or Edge Function does not mean the corresponding feature is complete in the Android app.

### Known implementation gap
- The Android API layer currently has no client-side E2EE protocol implementation in its exposed method set. E2EE database tables alone do not provide end-to-end encryption.
- Stories, social profile media, reactions, pins, typing indicators, cloud/secret chat controls, saved messages, and full call flows require Android UI/API integration and end-to-end tests.
- The current client uses manual/polling refresh in places; production chat UX needs reliable Realtime delivery, reconnect handling, unread/read state, and offline behavior.

## Delivery sequence

1. **Foundation and navigation** — split the monolithic activity into authentication, chat list, conversation, contacts/search, profile/settings, and shared UI components. Preserve the existing working auth and chat flows.
2. **Core chat reliability** — Supabase Realtime subscriptions, reconnect/resume, pagination, optimistic sends with retry, unread counts, read receipts, typing state, mute/archive/pin behavior.
3. **Telegram-like conversation UX, with Nexura identity** — polished message bubbles, date separators, reply/edit/delete, reactions, pinned messages, attachment picker, image/video/file previews, voice-message recording and playback.
4. **People and groups** — contact discovery, group creation/member management, roles, profile photos/covers, stories with 24-hour expiry.
5. **Calls and notifications** — integrate the existing LiveKit/TURN/push functions, incoming-call UX, call history, notification preferences.
6. **Security** — implement and independently review client-side E2EE, device identity/key lifecycle, encrypted backups and secret-chat semantics. Do not label chats end-to-end encrypted until verified on-device.
7. **Release readiness** — automated Android tests, RLS/security review, migration verification, signed release pipeline, privacy and GPL-free Nexura-only source inventory.

## Non-negotiable acceptance criteria

- No Telegram package, endpoint, API ID/hash, MTProto transport, Telegram account flow, or Telegram-branded UI in the Nexura release app.
- A new user can register a Nexura account and exchange messages with another Nexura account using Nexura infrastructure only.
- RLS is enabled and verified for every exposed user-data table; client apps never contain service-role secrets.
- Claims about E2EE, calls, delivery/read status, or offline support are made only after the relevant behavior is implemented and tested.
