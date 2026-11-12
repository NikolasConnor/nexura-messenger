# Nexura stage 5 — push and privacy

- Push notifications contain only a generic message body and the conversation UUID.
- Android notification taps open the matching conversation after the signed-in user's chat list is loaded.
- Notification content is marked private for lock-screen presentation.
- Conversation UUIDs are validated before navigation.
- The push function remains authenticated and checks conversation membership before sending.
- Firebase service-account credentials must remain in Supabase Edge Function secrets; never commit them to this repository.
- The production `send-chat-push` Edge Function is deployed as version 13 with JWT verification enabled, canonical conversation UUID validation, conversation-membership checks, and generic notification content.
