# WYRIX GUARD - Android app (`wyrix-guard-android`)

Kotlin · Jetpack Compose (Material 3) · Hilt · Room · WorkManager · Fused Location · FCM · Play Billing.
Package `com.wyrix.guard`, minSdk 29, targetSdk 36. Backend: Supabase (see `wyrix-guard-dashboard/backend`).

**Status: written, not yet built or run.** See `docs/INTEGRATION_CHECKLIST.md` for what is verified and what is pending.

## What it does
Enrolment with explicit consent and a Keystore-backed device identity · foreground location service with a permanent
notification · Room offline queue with dedup + retry/backoff · remote commands (fetch + validate + signed ack) ·
Lost Mode screen · owner alarm (unlock required to stop) · SIM inserted/removed events · optional failed-unlock events
(device admin) · geofences · recovery PDF / CSV · emergency contacts · Play Billing with server verification.

## What it deliberately does NOT do
No covert/background photography (Android blocks it) · no IMEI reading or IMEI tracking · no tracking after power-off,
factory reset, uninstall or force-stop · no hidden icon/notification · no accessibility-service tricks · no service_role key.

## Configuration (never commit secrets)
| Name | Where | Notes |
|---|---|---|
| `SUPABASE_URL`, `SUPABASE_ANON_KEY` | GitHub Secrets (or `~/.gradle/gradle.properties`) | anon key is public by design |
| `DASHBOARD_URL` | GitHub variable | used for contact links |
| `GOOGLE_SERVICES_JSON_B64` | GitHub Secret | base64 of Firebase `google-services.json`; without it push is inactive |
| `WYRIX_KEYSTORE_B64`, `WYRIX_KEYSTORE_PASSWORD`, `WYRIX_KEY_ALIAS`, `WYRIX_KEY_PASSWORD` | GitHub Secrets | release signing only |

Create keystore (Termux): `keytool -genkeypair -v -keystore release.jks -alias wyrix -keyalg RSA -keysize 4096 -validity 10000`
then `base64 -w0 release.jks` into the secret. Never commit `release.jks`.

## Termux: clone, edit, push, download builds
```sh
pkg update && pkg install -y git openssh gh
gh auth login                     # or: git config credential.helper store
git clone https://github.com/<you>/wyrix-guard-android.git && cd wyrix-guard-android
# edit files with nano / vim, then:
git add -A && git commit -m "Describe change" && git push
gh run watch                      # follow the Android CI run
gh run download -n wyrix-guard-debug-apk -D ~/storage/downloads    # needs: termux-setup-storage
# signed release bundle: Actions > Android CI > Run workflow > release = true
gh run download -n wyrix-guard-release-aab -D ~/storage/downloads
```
Install the APK from Downloads (allow "install unknown apps" for your file manager).

## Build notes
- No Gradle wrapper is committed (no network when generated). CI uses Gradle 8.11.1, which AGP 8.9.1 requires, with JDK 17.
  To add the wrapper later: `gradle wrapper --gradle-version 8.11.1`, commit `gradlew`, `gradle/wrapper/*`.
- Firebase: add an Android app `com.wyrix.guard` (and `com.wyrix.guard.debug`) in the Firebase console.
- Play Billing: create subscriptions `wyrix_premium_monthly` and `wyrix_premium_yearly`; link the service account in Play Console.
