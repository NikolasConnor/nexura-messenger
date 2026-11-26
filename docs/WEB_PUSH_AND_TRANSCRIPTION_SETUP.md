# Nexura Web Push and browser-local voice transcription

## Voice transcription

Nexura Web uses Whisper Tiny through Transformers.js and ONNX Runtime Web. The model is downloaded from Hugging Face on first use and cached by the browser. Audio is decrypted in the Nexura client and processed locally on the user's device; the audio itself is not uploaded to a transcription server or sent to an inference API.

The first transcription requires an internet connection to download the model files. Later use can reuse the browser cache, subject to the browser's storage policy. Recognition speed and memory use depend on the phone or computer. The model is multilingual and detects the spoken language automatically.

No transcription server, Docker container, API key, or transcription URL is required.

## Web Push

Web Push delivery is implemented by the Supabase Edge Function `send-chat-push` and can work independently of Firebase Cloud Messaging.

Check Supabase Dashboard → Project → Edge Functions → Secrets for existing `WEB_PUSH_VAPID_PUBLIC_KEY` and `WEB_PUSH_VAPID_PRIVATE_KEY` values. If they already exist, reuse that exact public key; do not generate a new pair, because rotating VAPID keys invalidates existing browser subscriptions.

If no pair is configured, generate one on a trusted machine:

```sh
npx --yes web-push generate-vapid-keys --json
```

Keep the private key out of Git and out of chat.

Set Supabase Edge Function secrets:
- `WEB_PUSH_VAPID_PUBLIC_KEY`
- `WEB_PUSH_VAPID_PRIVATE_KEY`
- `WEB_PUSH_VAPID_SUBJECT=https://nikolasconnor.github.io/nexura-messenger/`

Set the GitHub Actions repository variable `NEXURA_WEB_PUSH_VAPID_PUBLIC_KEY` to the matching public key and re-run the GitHub Pages deployment.

For iPhone/iPad, Web Push requires iOS/iPadOS 16.4 or later: install through Safari → Add to Home Screen, open the installed app, enable notifications, and allow the permission prompt.
