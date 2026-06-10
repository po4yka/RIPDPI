---
title: "Verify no leak/black-hole window between TUN establish() and native relay readiness"
type: task
status: backlog
area: vpn
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-06-10
updated: 2026-06-10
source_wiki_pages: []
linked_task: null
---

## Motivation

A 2026-06-10 external security review examined the kill-switch / DNS-leak posture and judged it **designed correctly and fail-closed** — no code change requested there:

- `ProtectGate` (`core/service/.../lifecycle/ProtectGate.kt`): the TUN fd is not opened until every transport socket is protected via `VpnService.protect`; failure transitions to `Blocked(ProtectFailed)`. Correct invariant against the relay-socket→TUN loop, and fail-closed.
- `BuilderAllowBypassGuard`: `allowBypass` defaults false, requires explicit per-profile `unsafeAllowBypass`.
- `VpnAppExclusionPolicy`: correctly handles Android's "cannot mix addAllowed/addDisallowed on one Builder" by emitting exactly one plan form.
- DHT-mitigation via `excludeRoute` (API 33+) closes the torrent-app deanonymization vector.
- The native side holds a generation token on the `VpnService.protect` GlobalRef (per `jniRegisterVpnProtect` docstring) so a stale unregister from an evicted session cannot drop a live callback.

The one residual is **not verifiable by code reading** — it needs instrumentation: confirm there is no window between `establish()` of the TUN interface and the native relay actually accepting connections, where apps see a default route into TUN but the relay is not yet serving. That is the classic first-packets leak / black-hole point. `ADR 0003 (native-readiness-push)` appears to close exactly this, but only a real connection trace or packet-capture test confirms it.

## Proposed change

1. Extend `app/src/androidTest/.../e2e/PacketSmokeInstrumentedTest.kt` (or add a focused instrumented test) to assert that, in the interval between `establish()` and the readiness-push firing (ADR 0003), no application packet egresses unprotected and none is silently black-holed — packets are either held until ready or fail closed.
2. Correlate the lifecycle transition (`VpnLifecycleState` → ready) with the first accepted relay connection; assert ordering: route installed ⇒ relay accepting, with no observable gap that drops/leaks first packets.
3. If a gap is found, fix by gating route exposure on the readiness-push (not on `establish()` alone) and re-run the trace.

## Acceptance criteria

- [ ] Instrumented test exercises the `establish()` → readiness-push window with real or simulated app traffic.
- [ ] Test asserts no unprotected egress and no silent black-hole of first packets before readiness.
- [ ] Lifecycle ordering (route installed ⇒ relay accepting) is asserted, not assumed.
- [ ] If a window exists, it is closed and the test proves the fix; if none exists, the test pins the invariant against regression.
- [ ] Runs in the existing instrumented lane (`:app:ciDevicesGroupGithubDebugAndroidTest` or the managed-device matrix).

## Risks / open questions

- Timing-sensitive: a flaky window may only appear under load or on slow devices; consider a deterministic hook (readiness barrier) rather than a wall-clock race in the test.
- This verifies an invariant the review already believes holds (ADR 0003); the value is a regression pin, so keep the test fast and deterministic.

## References

- External security review, 2026-06-10 (kill-switch/DNS-leak judged correct; this is the one residual instrumentation item).
- `docs/adr/0003-native-readiness-push.md`, `.claude/rules/vpnservice-protect-invariant.md`, `.claude/rules/android-vpn-lifecycle.md`.
- Existing artifacts: `ProtectGate.kt`, `VpnLifecycleState.kt`, `VpnStartAbortOnProtectFailureTest.kt`, `LifecycleRegressionMatrixTest.kt`, `PacketSmokeInstrumentedTest.kt`.
