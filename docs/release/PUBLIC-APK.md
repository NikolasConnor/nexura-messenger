# Public Android APK release

This repository publishes the signed Android APK on the Nexura website through GitHub Pages.

## Required GitHub Actions secrets

Create these repository secrets in **Settings → Secrets and variables → Actions**:

- `NEXURA_KEYSTORE_BASE64` — Base64 contents of the release keystore.
- `NEXURA_KEYSTORE_PASSWORD` — keystore password.
- `NEXURA_KEY_ALIAS` — alias inside the keystore.
- `NEXURA_KEY_PASSWORD` — private-key password.

Never commit the keystore or passwords to Git. Never send them in chat or issue comments.

## Create the signing key on Windows

Install/use JDK 17 (Android Studio includes a JBR runtime). In PowerShell, run this in a private folder that is backed up securely:

```powershell
keytool -genkeypair -v -keystore nexura-release.jks -alias nexura-release -keyalg RSA -keysize 4096 -validity 10000
```

Keep the password and alias safe. Make at least two offline backups of `nexura-release.jks`. If this key is lost, future APK updates cannot be installed as updates over this signed app.

Convert the keystore to Base64 and copy it to the clipboard:

```powershell
$bytes = [System.IO.File]::ReadAllBytes(".\nexura-release.jks")
[Convert]::ToBase64String($bytes) | Set-Clipboard
```

Add the clipboard value as `NEXURA_KEYSTORE_BASE64`. Add the other three values as separate secrets. Do not paste any of these values into source code.

## Publish

1. Merge and test the intended release source first. Do not tag a draft or unverified branch.
2. Confirm `versionName` and increment `versionCode` in `app/build.gradle.kts` for every new app release.
3. Create and push a version tag from the approved release commit, for example `v0.2.0`.
4. The `Build and publish signed Nexura APK` workflow builds the Release variant, signs and verifies the APK, computes SHA-256, then deploys the website to GitHub Pages.
5. The public download URL is `<site-root>/downloads/Nexura.apk`; checksum is `<site-root>/downloads/SHA256SUMS.txt`.

The first Pages deployment requires GitHub Pages to be enabled with **Build and deployment → GitHub Actions**. If the project uses a custom domain, configure it in Pages settings and add the DNS records requested by GitHub.

## Before announcing the download

- Install the APK on a clean Android device and verify the app signature.
- Test upgrade installation from the previous signed version.
- Test account registration/login, direct and group messaging, media upload, push notifications and supported E2EE flows on two devices.
- Replace the preliminary privacy page with the final accurate policy, including operator identity/contact, data categories, providers, retention/deletion process and applicable legal disclosures.
- Publish a clear statement that APK installation is outside Google Play and Android may ask the user to allow installation from the browser.
