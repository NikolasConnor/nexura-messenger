# Nexura Web Push and iPhone setup

Web Push delivery is implemented by the Supabase Edge Function `send-chat-push`. The Edge Function is deployed as version 22 and now supports Web Push independently of Firebase Cloud Messaging.

## 1. Generate a VAPID key pair

Run this on a trusted machine with Node.js. Keep the private key out of Git and out of chat:

```sh
npx --yes web-push generate-vapid-keys --json
```

The command returns `publicKey` and `privateKey`.

## 2. Configure Supabase Edge Function secrets

In Supabase Dashboard → Project → Edge Functions → Secrets, set:

- `WEB_PUSH_VAPID_PUBLIC_KEY` = generated publicKey
- `WEB_PUSH_VAPID_PRIVATE_KEY` = generated privateKey
- `WEB_PUSH_VAPID_SUBJECT` = `https://nikolasconnor.github.io/nexura-messenger/`

Or use the Supabase CLI from a machine already authenticated to the project:

```sh
supabase secrets set --project-ref lyhzawblxcvpgpjrkspc \
  WEB_PUSH_VAPID_PUBLIC_KEY="PUBLIC_KEY" \
  WEB_PUSH_VAPID_PRIVATE_KEY="PRIVATE_KEY" \
  WEB_PUSH_VAPID_SUBJECT="https://nikolasconnor.github.io/nexura-messenger/"
```

Do not commit or paste the private key into GitHub source.

## 3. Configure GitHub repository variables

Repository → Settings → Secrets and variables → Actions → Variables. Add:

- `NEXURA_WEB_PUSH_VAPID_PUBLIC_KEY` = the same generated publicKey
- `NEXURA_TRANSCRIPTION_URL` = the HTTPS URL of the self-hosted transcription service (without a trailing slash)

The Pages workflow injects these as Vite build variables. Re-run the Pages workflow after setting them. The public VAPID key is public by design; the private key remains only in Supabase secrets.

## 4. iPhone requirements and test

- iOS/iPadOS 16.4 or newer.
- Open the Nexura Web app in Safari.
- Share → Add to Home Screen, then launch the installed Nexura icon.
- Sign in, open Settings → Notifications, enable push, and allow notifications.
- From a different account/device, send a message to this account while Nexura is closed.
- Confirm the notification arrives and tapping it opens Nexura.

Push delivery cannot be verified by a desktop build alone; the last step must be tested on a real iPhone/iPad. The browser permission is per installed web app and per device.

## 5. Local transcription endpoint

See [services/transcription/README.md](../services/transcription/README.md). The local service must be running on the Nexura server behind HTTPS. GitHub Pages cannot access a private LAN address from an iPhone outside that LAN.
