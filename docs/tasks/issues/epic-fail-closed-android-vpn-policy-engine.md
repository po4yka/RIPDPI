---
id: EPC-1786264762917557
title: Epic - Fail-closed Android VPN policy engine
kind: epic
status: blocked
area: epic
priority: critical
risk: high
owner: Android VPN policy maintainer
parent: null
blocked_by:
  - RTE-1786264762917255
spec_mode: required
openspec_change: epc-1786264762917557-epic-fail-closed-android-vpn-policy-engine
created: 2026-05-01
updated: 2026-08-09
status_detail: Repository policy implementation is landed; remaining acceptance is physical Android 17 exclusion persistence and allowed-versus-excluded egress evidence.
---

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
- Add captive-portal and whitelist-mode connection states (closed task)
- Add captive portal DNS assist via Network object (closed task)
- Add Android Private DNS conflict warning (closed task)
- Harden DoH POST resolver client (closed task)
- Add authoritative DNS leak-test harness (closed task)
- Add Android VPN leak-test instrumentation matrix (closed task)
- Add tun2socks UID validation to close SO_BINDTODEVICE escape — `add-tun2socks-uid-validation-against-so-bindtodevice-bypass` (doing — TCP/UDP data-plane enforcement, JNI UID source, and recurring physical Linux TUN evidence complete; Android app/kernel/adb checks and ICMP policy remain)
- Adopt Android 17 system split-tunnel UI via ACTION_VPN_APP_EXCLUSION_SETTINGS — `adopt-android-17-system-split-tunnel-ui-via-action-vpn-app-exclusion` (doing — version-gated delegation + fallback shipped + unit-tested; reconnect-persistence device-gated)
- Adopt process-based per-package routing via VpnService.Builder app filters — `adopt-process-based-per-package-routing-via-xray-tun-routeonly` (doing — criterion 4 policy half unit-tested; egress-IP half device-gated)
- Spike FakeIP mode compatibility on Android — **resolved 2026-06-11 → [ADR 0008](../../adr/0008-fakeip-mode-android.md) (no-go for a user-facing FakeIP mode; MapDNS already ships the primitive in TUN mode)**; spike task closed/deleted.

## Milestones

- [x] Internal VPN profile is a typed policy bundle, not only imported URI strings.
- [x] Secure default captures full-device traffic with DNS interception and explicit IPv4-only policy.
- [x] Lockdown onboarding clearly distinguishes Android system kill switch from soft reconnect.
- [x] Core crash, network switch, and VPN revoke paths fail closed in tests.
- [x] Logs, diagnostics, crash exports, QR/import, and subscription refreshes redact live credentials.

## Risks

- Android lockdown state is partly user/system controlled; the app must not overclaim hard kill-switch guarantees.
- DNS and IPv6 policy cuts across direct-mode, Xray provider mode, and subscription rendering.
- Per-app policy changes require VPN session re-establish and can conflict with user expectations under lockdown.

## Notes

This epic intentionally removes an entire class of client problems rather than mirroring individual behavior from reference Android implementation, reference implementation, Streisand, or sing-box GUI clients.

## Work log

- 2026-07-17: **SO_BINDTODEVICE child reached its CI/privileged completion bar.** The smoltcp TCP/UDP admission gate and JNI UID attribution contract were covered by the integrated suite, and job `87764872159` in recurring physical Linux run `29541621476` passed all 12 IPv4/IPv6 direct/allowed/denied TCP/UDP phases plus strict evidence validation. The child remains `doing` for its Android synthetic-app/kernel-version/`adb` oracles and explicit ICMP policy.
- 2026-06-05: NOT done — deletion refuted. The 5 milestones are largely implemented in live code (DeviceProfile.kt/SplitStrictDnsPolicy.kt typed bundle; DnsInterceptorDispatcher.kt full-device DNS interception; AndroidHardKillSwitchState.kt + HardKillSwitchUiState.kt lockdown UX; LifecycleRegressionMatrixTest.kt/DnsLeakMatrixTest.kt fail-closed tests; DiagnosticsRedactor.kt + DiagnosticsBundleRedactionTest.kt redaction). BUT the `## Child work` list above is stale: two later-added children parented to this epic remain open in backlog — `add-tun2socks-uid-validation-against-so-bindtodevice-bypass.md` (status: backlog, all acceptance criteria unmet per its 2026-06-05 work log; no UID enforcement at the tun2socks layer) and `spike-fakeip-mode-compatibility-on-android.md` (status: backlog). Epic stays open until those close.
- 2026-06-05: Epic audit (child rollup). Re-verified all 5 milestones against live source and marked them [x]: typed bundle (core/data/model/.../DeviceProfile.kt, core/service/.../SplitStrictDnsPolicy.kt), full-device DNS interception (core/service/.../DnsInterceptorDispatcher.kt), kill-switch UX (core/service/.../AndroidHardKillSwitchState.kt + app/.../HardKillSwitchUiState.kt), fail-closed tests (core/service/src/test/.../lifecycle/LifecycleRegressionMatrixTest.kt + leak/DnsLeakMatrixTest.kt), redaction (core/service/.../keystore/ProfileDiagnosticsRedactor.kt + src/test/.../redaction/DiagnosticsBundleRedactionTest.kt; keystore encryption via EncryptedProfileStore.kt/KeystoreKeyManager.kt). Both tracked children remain `backlog` per their freshly-audited 2026-06-05 logs (no `UidFlowPolicy` in native/rust/crates; no FakeIP implementation anywhere). Status stays `doing` (not `done`): milestones met but two children open. `updated: 2026-06-05`.
- 2026-06-11: **Child rollup — advanced all four open children to their CI-achievable limit.** (1) tun2socks UID validation: shipped the unit-tested `ripdpi_tunnel_core::uid_policy` decision core (`UidFlowPolicy`/`FlowUidSource`), fail-closed; data-path wiring + JNI source + `SO_BINDTODEVICE` device tests device-gated → `backlog`→`doing`. (2) Android 17 split-tunnel UI: shipped the version-gated `ACTION_VPN_APP_EXCLUSION_SETTINGS` delegation + in-app fallback + 8-locale strings + unit test; corrected the (incorrect) manifest-declaration criterion; reconnect-persistence device-gated → `backlog`→`doing`. (3) Per-package routing: added the criterion-4 policy-decision unit test; egress-IP verification device-gated → stays `doing`. (4) FakeIP spike: resolved as **ADR 0008** (no-go for a user-facing mode — MapDNS already ships the FakeIP primitive in TUN mode); spike task deleted (`done`). Epic stays `doing`: all 5 milestones [x] and all CI-achievable child work is done, but three children carry device-gated remainders (an on-device kernel-5.7+/Android-17 session) so they cannot close in CI. pr-reviewer pass applied across the code.

## Links

- [[ripdpi-android]]
- ripdpi-android-split-strict-dns-architecture-2026-05-01
- [[Epic - Xray provider mode]]
- Epic - Subscription and profile import
- [[Epic - Advanced routing rules and geoip enforcement]]
- [[Epic - Runtime lifecycle and supervisors]]
- https://developer.android.com/develop/connectivity/vpn
- Child issues: 21
