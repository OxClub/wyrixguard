# WYRIX GUARD — Architecture & Feasibility (Phase 0)

## Layers (Clean Architecture + MVVM)
- ui/ — Compose screens, ViewModels (Hilt), navigation
- domain/ — models, use cases, repository interfaces (pure Kotlin)
- data/ — Room, Supabase client, FCM, location, repository impls
- service/ — foreground location service, FCM receiver, DeviceAdmin receiver

## Feasibility matrix
| Feature | Status | Constraint |
|---|---|---|
| Live GPS (Fused) | Supported | Foreground service type `location`; background location needs Play declaration |
| Offline queue + sync | Supported | Room + WorkManager; deferred, not guaranteed instant |
| Lost Mode message | Supported | Needs device online + app not force-stopped |
| Remote alarm | Supported | High-priority FCM, device fetches and validates; DND/Doze may delay |
| Failed-unlock events | Restricted | DevicePolicyManager + user-enabled device admin; Play review |
| Intruder photo | Restricted | Camera permission, no covert capture; often impossible from lock screen |
| SIM change alert | Partial | Public APIs only; no IMSI/ICCID/new-owner info |
| IMEI | Manual record only | Cannot locate by IMEI; guide to CEIR / Sanchar Saathi |
| Survive factory reset / power-off / uninstall | Not possible | Disclosed in-app |
| Geofences | Supported | Android Geofencing API, opt-in |
| Subscriptions | Supported | Play Billing + server-side verification |

## Backend (Phase 2 detail)
Tables: profiles, devices, device_locations, device_status, security_events, intruder_evidence, remote_commands, geofences, emergency_contacts, recovery_reports, subscriptions, audit_logs. Strict RLS (owner_id = auth.uid()). Sensitive actions via Edge Functions (ownership check, expiry, replay prevention, rate limit). FCM is a wake-up only; device fetches and validates commands.

## Roadmap
Phases 1-10 as in the master prompt. Phase 1 = this foundation.

## Verification status
Phase 1 has NOT been built or run in this environment (no network). CI result is pending.
