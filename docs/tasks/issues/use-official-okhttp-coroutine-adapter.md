---
id: DGN-1794036571954184
title: Use the official OkHttp coroutine adapter
kind: bug
status: todo
area: diagnostics
priority: high
owner: Standard-library migration
parent: EPC-1794036260770217
blocked_by: []
spec_mode: required
openspec_change: use-official-okhttp-coroutine-adapter
created: 2026-11-07
updated: 2026-11-07
---

## Goal

HTTP probes must cancel calls and release responses when cancellation wins after the callback but before coroutine delivery.

## Audit evidence

Audit finding 4, 2026-11-07, baseline 202f970bb3574980dd2eb87fea002cac8fbe29f7. This is a confirmed replacement candidate, not proof that an implementation is complete. Recheck callers and tests before editing.

## Scope

Use okhttp-coroutines Call.executeAsync with response.use in the repeated probe adapters. Keep response-body processing on an appropriate IO dispatcher.

Keep call deadlines, EventListener, status and payload checks, socket routing, and cancellation propagation. Do not replace specialized probe payloads.

## Acceptance criteria

- A successful probe consumes and closes its response while keeping its existing payload verdict.
- Cancellation after the callback but before continuation delivery closes the undelivered response and cancels the call.
- Timeouts, failed HTTP status, invalid payloads, EventListener events, and local SOCKS routing retain their contracts.
- Extend existing probe tests with deterministic cancellation after onResponse and before dispatch; assert response closure and test normal and failed payloads with MockWebServer.
- Named local gates and repository-required independent review pass; record exact implementation SHA and command results. Commit each complete atomic implementation unit on main.

## Dependencies and order

Priority band 1; recommended position 4 in the epic queue. Prerequisites: None. Respect the epic priority and shared-file queue.

No dependency is implied by priority alone. Shared Cargo and Gradle files use one writer; the epic's queue prevents concurrent edits without introducing false semantic blockers.

## Ownership

- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/DefaultProbeReplayService.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/StrategyProbeService.kt`
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/RelayCapabilityProbe.kt`
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/QualityHttpPayloadProbe.kt`

The same writer owns related module tests, direct dependency declarations, and task artifacts. Shared dependency and generated-output lanes follow the epic. The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

## Dependency decision

Add the official okhttp-coroutines module at the version pinned for OkHttp in gradle/libs.versions.toml. Verify publication, licenses, transitive dependencies, and Android compatibility. One writer owns the version catalog and affected build files.

## Verification

- `./gradlew :core:diagnostics:testDebugUnitTest :core:service:testDebugUnitTest`
- `./gradlew staticAnalysis`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
