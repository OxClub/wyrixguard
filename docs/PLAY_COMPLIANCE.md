# Google Play compliance checklist (review against CURRENT policy before submission)

A technically possible API does not guarantee approval. Items below are risks to resolve, not approvals.

| Area | What the app does | Action before release |
|---|---|---|
| Background location | `ACCESS_BACKGROUND_LOCATION` requested separately, after in-app disclosure; core purpose is anti-theft recovery | Complete the Play Console Permissions Declaration with a demo video; confirm the prominent-disclosure text in `EnrollScreen`/`HomeScreen` matches policy wording |
| Foreground services | `location` (tracking, persistent notification) and `mediaPlayback` (owner alarm) types | Declare both in Play Console; justify each |
| Device admin | Optional; only `watch-login`; never wipes/locks | Play restricts device admin use; failed-unlock events are not guaranteed on newer Android. Be prepared to ship without it |
| Camera / evidence | Not implemented: Android blocks background camera capture | None. Do not market intruder selfies |
| Accessibility | Not used | - |
| Full-screen intent | Alarm / Lost Mode screens | Declare `USE_FULL_SCREEN_INTENT` purpose (Android 14+ restricts it) |
| Sensitive identifiers | No IMEI/IMSI/ICCID/SIM number read; IMEI is only typed by the owner | Data Safety form: state owner-entered IMEI |
| Data Safety | Collected: precise & approximate location, device status, security events, email, optional IMEI. Encrypted in transit; user can request deletion | Fill the form to match; link a privacy policy URL |
| Account deletion | In-app (Settings) and `delete-account` function; web path needed | Add a public web page explaining deletion, required by Play |
| Billing | Play Billing subscriptions, server-verified, purchases acknowledged server-side | Create products, test with license testers, add RTDN later |
| Boot / battery | `BOOT_COMPLETED` resumes tracking if allowed, else notifies | Do not request battery-optimisation exemption unless justified |
| Target API | targetSdk 36 | Re-check the current Play target requirement |

Supported Android: 10 (API 29) to 16 (API 36). Enterprise-only features: none used.
