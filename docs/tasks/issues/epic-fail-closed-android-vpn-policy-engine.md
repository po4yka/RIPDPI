---
title: Epic - Fail-closed Android VPN policy engine
type: epic
status: backlog
area: vpn
priority: critical
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-01
---

- [ ] #task Epic - Fail-closed Android VPN policy engine #repo/RIPDPI #area/vpn #status/backlog 🔺

## Goal

Make RIPDPI a fail-closed policy-first Android tunneled outbound profile, not just a GUI for imported proxy links. The app should eliminate the common failure classes in existing clients: incomplete policy bundles, DNS and IPv6 leaks, weak kill-switch UX, shared subscriptions, manual-only failover, unsafe logs, and untested VPN lifecycle behavior.

## Scope

- In scope: Android VpnService lifecycle, lockdown onboarding, DNS and IPv6 policy, priority failover, typed policy profile schema, per-device subscription handling, secret storage, no-secret diagnostics, and regression tests.
- Out of scope: server-side subscription delivery implementation, payment flows, non-Android clients, and replacing existing direct-mode or Xray-provider epics.

## Status

New cross-cutting hardening epic derived from the client-problem analysis. It coordinates with Xray VPN mode, subscription import, advanced routing, QR/import, and runtime lifecycle epics.

## Child work

- Define policy bundle profile schema (closed task)
- Define split-strict DNS policy model (closed task)
- Add Android lockdown onboarding and kill-switch health checks (closed task)
- Enforce fail-closed VpnService lifecycle (closed task)
- Add DNS interceptor and split DNS leak tests (closed task)
- Implement scoped bootstrap DNS allowlist (closed task)
- Implement strict tunneled DNS resolver failover (closed task)
- Bind DNS answers to route decisions (closed task)
- Add explicit IPv6 policy modes and leak tests (closed task)
- Add priority-based outbound failover state machine (closed task)
- Add per-device subscription token UX and shared-link warnings (closed task)
- Encrypt VPN profiles with Android Keystore (closed task)
- Add no-secret logging and diagnostics redaction tests (closed task)
- Add NetworkCallback reconnect and underlying-network tracking (closed task)
- [[Add captive-portal and whitelist-mode connection states]]
- Add captive portal DNS assist via Network object (closed task)
- [[Add Android Private DNS conflict warning]]
- Harden DoH POST resolver client (closed task)
- Add authoritative DNS leak-test harness (closed task)
- Add Android VPN leak-test instrumentation matrix (closed task)

## Milestones

- [ ] Internal VPN profile is a typed policy bundle, not only imported URI strings.
- [ ] Secure default captures full-device traffic with DNS interception and explicit IPv4-only policy.
- [ ] Lockdown onboarding clearly distinguishes Android system kill switch from soft reconnect.
- [ ] Core crash, network switch, and VPN revoke paths fail closed in tests.
- [ ] Logs, diagnostics, crash exports, QR/import, and subscription refreshes redact live credentials.

## Risks

- Android lockdown state is partly user/system controlled; the app must not overclaim hard kill-switch guarantees.
- DNS and IPv6 policy cuts across direct-mode, Xray provider mode, and subscription rendering.
- Per-app policy changes require VPN session re-establish and can conflict with user expectations under lockdown.

## Notes

This epic intentionally removes an entire class of client problems rather than mirroring individual behavior from reference Android implementation, reference implementation, Streisand, or sing-box GUI clients.

## Links

- [[ripdpi-android]]
- ripdpi-android-split-strict-dns-architecture-2026-05-01
- [[Epic - Xray provider mode]]
- Epic - Subscription and profile import
- [[Epic - Advanced routing rules and geoip enforcement]]
- [[Epic - Runtime lifecycle and supervisors]]
- https://developer.android.com/develop/connectivity/vpn
- Child issues: 21
