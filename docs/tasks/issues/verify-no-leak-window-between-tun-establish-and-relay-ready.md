---
title: "Verify no leak/black-hole window between TUN establish() and native relay readiness"
type: task
status: doing
area: vpn
priority: high
owner: Lifecycle and PMTUD lane
parent: null
blocks: []
blocked_by: []
created: 2026-06-10
updated: 2026-07-17
status_detail: Deterministic and focused physical-device startup-window regressions are complete; release-grade dual-vantage evidence remains blocked on a configured runner, runner config, and independent observer hook
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

- [~] Instrumented test exercises the `establish()` → readiness-push window with real or simulated app traffic. Deterministic Kotlin/Rust tests exercise delayed readiness, setup failure, timeout, and a retained blocking TUN; focused physical app-process traffic has passed, while release-grade dual-vantage packet capture remains open.
- [~] Test asserts no unprotected egress and no silent black-hole of first packets before readiness. Deterministic tests prove no false `Connected`, no early TUN close/direct fallback, and bounded fail-closed cleanup; packet-capture confirmation remains open.
- [x] Lifecycle ordering (route installed ⇒ relay accepting) is asserted, not assumed. Native readiness fires only after fallible packet-loop setup, and service status history cannot publish `Connected` before the callback returns.
- [x] The code-level window is closed and regression-pinned: failure/timeout retains cleanup ownership and transitions fail closed instead of reporting a live VPN.
- [x] Runs in the existing instrumented lane. The focused physical-device invocation of `:app:connectedGithubFullDebugAndroidTest` executed the required startup-window testcase exactly once without skips.

## Risks / open questions

- Timing-sensitive: a flaky window may only appear under load or on slow devices; consider a deterministic hook (readiness barrier) rather than a wall-clock race in the test.
- This verifies an invariant the review already believes holds (ADR 0003); the value is a regression pin, so keep the test fast and deterministic.

## References

- External security review, 2026-06-10 (kill-switch/DNS-leak judged correct; this is the one residual instrumentation item).
- `docs/adr/0003-native-readiness-push.md`, `.claude/rules/vpnservice-protect-invariant.md`, `.claude/rules/android-vpn-lifecycle.md`.
- Existing artifacts: `ProtectGate.kt`, `VpnLifecycleState.kt`, `VpnStartAbortOnProtectFailureTest.kt`, `LifecycleRegressionMatrixTest.kt`, `PacketSmokeInstrumentedTest.kt`.

## Work log

- 2026-07-17: On a physical Pixel 7 API 37 user build, exact source SHA `6b2e2959826685414744792db48b48f9d81b7aac` passed `VpnStartupWindowE2ETest#vpnStartupWindowHoldsDnsPacketUntilNativeReady` exactly once (`1` test, `0` failures/errors/skips, testcase `2.899s`; JUnit XML SHA-256 `52fea4f1c20ce8c6b68afc2a2c8196a11e98d5f823434b02de1ebcb84b07cfab`). The test held a real app-process DNS datagram while readiness was gated, observed no correlated fixture egress and no false `Running`, then required the exact DNS response and one correlated external-fixture event after release. This is local physical-device evidence, not the still-missing release-grade dual-vantage packet-capture artifact.
- 2026-07-17: The remaining dual-vantage capture cannot run: the repository has zero self-hosted runners matching `self-hosted, linux, ripdpi-network-evidence, physical-android`, and the local host has no runner config. Deterministic and focused physical-device acceptance are complete; release-grade packet evidence remains open without being green-skipped.
- 2026-07-16: Independent review found and closed two startup gaps before commit: readiness now fires after all fallible `setup_io_loop` work (with a valid-config/non-IP SOCKS fault test), and the five-second JNI deadline no longer performs an unbounded worker join. Timeout cancellation transfers join/fd ownership to a runtime reaper; a native injected-stall test proves the startup thread returns while cleanup ownership remains tracked.
- 2026-07-16: Implemented the deterministic half of the invariant. Native `start` now waits for a one-shot barrier emitted only after TUN fd adoption, smoltcp addresses/routes, and packet-loop setup; service status remains non-running while the established TUN blocks traffic. Virtual-time tests cover delayed readiness, timeout, and failure-before-TUN-close ordering. The physical Android dual-vantage capture acceptance criteria remain open and must not be inferred from JVM/Rust ownership tests.
- 2026-07-16: Assigned to the lifecycle/PMTUD lane. Completion now requires a deterministic fault-injected `TUN establish -> native ready` barrier test proving no direct egress or false `Connected`, plus fail-closed timeout/error cleanup ownership.
