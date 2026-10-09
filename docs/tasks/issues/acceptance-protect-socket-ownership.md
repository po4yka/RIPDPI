---
id: SVC-1791570478587374
title: Preserve protect socket ownership across VPN session overlap
kind: bug
status: doing
area: service
priority: high
owner: Android
parent: null
blocked_by: []
spec_mode: required
openspec_change: svc-1791570478587374-acceptance-protect-socket-ownership
created: 2026-10-09
updated: 2026-10-09
---

## Goal

Keep the active VPN protect socket reachable when an old service session finishes cleanup after a new session starts. Coordinated by TST-1791553917096956.

## Acceptance criteria

- Preserve the original c2a failed Android report and its TCP empty EOF, UDP timeout, and server receipts.
- Reproduce overlapping socket ownership with a failing deterministic test before the production fix.
- Old cleanup cannot remove or close a newer endpoint. Failed and repeated operations preserve ownership.
- Keep descriptor protection and fail-closed behavior on non-root devices.
- Pass full service tests, static analysis, genuine Android profile and repeats, independent review, and exact published CI.

## Ownership

Android owns VpnProtectSocketServer, VpnServiceSessionLifecycle, ActiveProtectSocketPathProvider, VpnNativeProtectRegistration and their tests, the named core/service test fixture and app Android endpoint probe, plus the single app/build.gradle.kts test fixture dependency. The coordinator owns this task and OpenSpec artifacts. Linux owns read-only failure analysis. No other writer changes these files.

## Observed failure

The clean c2a Android Network run starts a new protect listener at 57.267 and logs old server stopped at 57.268. The old stop deletes the shared pathname after joining its threads. This is a concrete possible overlap defect; the exact historical unlink order and causal connection to this traffic failure require a deterministic reproduction and fresh runtime evidence. The TCP reader completed connect but returned empty EOF, UDP timed out, and restart TCP had no server receipt. DoH success uses a distinct protection path and does not prove this endpoint remains reachable.
