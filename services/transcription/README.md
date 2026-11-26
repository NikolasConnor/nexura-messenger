# Nexura local voice transcription

This service runs whisper.cpp locally. Audio is decrypted by the Nexura Web client only after the user presses "Transcribe"; the plaintext audio is sent directly to this self-hosted service over HTTPS. The service authenticates each request against Supabase Auth, processes the audio in a temporary directory, and deletes the temporary files when the request ends. It does not store audio or transcripts.

## Run on the Nexura server

Requirements: Docker with Compose, outbound access to download the image/model during the first build, and a public HTTPS reverse proxy/tunnel for the Web client to reach the service.

Create an environment file at services/transcription/.env (never commit it) with:

- SUPABASE_URL=https://lyhzawblxcvpgpjrkspc.supabase.co
- SUPABASE_ANON_KEY=<the project's publishable/anon key>
- CORS_ORIGINS=https://nikolasconnor.github.io

Build and run:

    docker build -t nexura-transcription ./services/transcription
    docker run -d --name nexura-transcription --restart unless-stopped --env-file services/transcription/.env -p 127.0.0.1:8080:8080 --memory=1200m --cpus=2 nexura-transcription

Put the service behind HTTPS and expose only the reverse-proxy endpoint. Do not expose port 8080 directly to the public internet. The example binds the service to localhost on the host.

The initial image build downloads the multilingual tiny-q5_1 Whisper model. It is intentionally small for CPU-only machines. One transcription runs at a time; uploads are limited to 20 MB and five minutes. The service accepts only authenticated Supabase sessions.

## Connect the Web build

Set VITE_NEXURA_TRANSCRIPTION_URL to the HTTPS base URL of this service in the GitHub Pages build environment, without a trailing slash. The client appends /v1/transcribe. Rebuild and deploy the Web app after setting it.

## Verify

    curl -fsS https://YOUR-HTTPS-HOST/health

The endpoint must return JSON with ok: true. Then sign in to Nexura Web, open a voice message, and press "Расшифровать". Russian and English are detected automatically.

The low-memory host may take longer than the audio duration to transcribe. If tiny quality is insufficient, benchmark base-q5_1 only after checking available RAM.
