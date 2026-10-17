# Integration checklist and honest status

Legend: ✅ done and executed here · 🟡 written, NOT executed · ❌ not implemented

## Executed in the authoring environment (no network, no Android SDK, no Docker)
- ✅ Command state machine, DER→raw ECDSA conversion, device-signature verification, SHA-256 (Node 22 stand-in for Deno)
- ✅ IMEI Luhn vector check (Node)
- ✅ Brace/paren balance scan of all Kotlin files

## Written but NOT executed (treat as unverified until CI/devices prove them)
- 🟡 Gradle build, KSP/Hilt/Room codegen, lint, Android unit tests (`CoreTest`)
- 🟡 `npm install`, `tsc`, `vite build`, vitest (`format.test.ts`)
- 🟡 `supabase db push`, pgTAP `rls.test.sql`, Deno tests, every Edge Function against a live project
- 🟡 Every on-device behaviour: location service, Doze, reboot, FCM wake-up, alarm, Lost Mode screen, geofences, SIM events, billing

## Not implemented (by design or Android limits)
- ❌ Intruder photos (Android blocks background camera). Failed-unlock events record `photo: not_captured`
- ❌ IMEI-based lookup, tracking after power-off / factory reset / uninstall
- ❌ Client-side end-to-end encryption of evidence (only TLS + Supabase at-rest); local Room DB is not SQLCipher-encrypted
- ❌ Play Real-time Developer Notifications (entitlements refresh on a schedule instead)
- ❌ SIM *swap* detection (only inserted/removed; needs restricted APIs for more)
- ❌ Dashboard CSV/PDF export of *geofence* data; evidence upload from the app (no capture path exists)

## First-run order (do these in sequence)
1. Create a Supabase project; run the **Backend** workflow with `deploy=true` (needs `SUPABASE_ACCESS_TOKEN`, `SUPABASE_PROJECT_REF`, `SUPABASE_DB_PASSWORD`).
2. Set Edge Function secrets (see `backend/README.md`). In Auth settings enable email confirmation; add the dashboard URL to redirect URLs.
3. Create a Firebase project; add the Android app; store `google-services.json` base64 as `GOOGLE_SERVICES_JSON_B64`; put the **service account JSON** in Supabase secret `FCM_SERVICE_ACCOUNT_JSON` (never in an app).
4. Add `SUPABASE_URL`, `SUPABASE_ANON_KEY` to both repos' secrets; enable GitHub Pages (Source: GitHub Actions) on the dashboard repo.
5. Push both repos; fix whatever the first CI run reports (expect some compile/type errors: nothing here was compiled).
6. Install the debug APK, register, enrol, grant permissions, start protection; confirm rows appear in `device_locations`.
7. From the dashboard send REQUEST_LOCATION, TRIGGER_ALARM, Lost Mode; confirm each state transitions (requested → delivered → executed) and the audit log.

## Manual test matrix (record real results; never mark untested items as passed)
Android 10, 12, 14, 16 · locked screen · app swiped away · reboot · Doze (`adb shell dumpsys deviceidle force-idle`) ·
battery saver · GPS off · permission revoked · airplane mode (queue then sync) · Supabase outage · FCM failure (poll fallback) ·
duplicate-sync (no duplicate rows) · expired command · replayed ack (409) · cross-account access attempt (RLS) · reauth-gated actions.
