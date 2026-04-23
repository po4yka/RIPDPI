# RIPDPI Roadmap

All previously tracked roadmap streams are complete in repo-owned scope. New
incremental hardening work is recorded here as it lands.

## Final Status (2026-04-22)

- Audit roadmap: COMPLETE.
- Architecture refactor: COMPLETE.
- Bypass techniques expansion: COMPLETE in repo-owned scope.
- Bypass modernization: COMPLETE in repo-owned scope.
- Integrations / Track S: COMPLETE in repo-owned scope.

## Incremental Hardening

### 2026-04-22: Strategy-Pack Anti-Rollback

Status: COMPLETE.

- Added signed-catalog anti-rollback metadata via `StrategyPackCatalog.sequence` and `StrategyPackCatalog.issuedAt`.
- Enforced repository-side freshness and monotonic-sequence checks for downloaded catalogs only.
- Added typed refresh failure reporting and runtime state fields for accepted/rejected sequences.
- Added a debug-only local rollback override that bypasses only the monotonic-sequence rejection.
- Updated bundled catalog metadata, operator docs, and regression coverage.

### 2026-04-22: Typed Cache Degradation

Status: COMPLETE.

- Added a shared typed cache-degradation contract for cached control-plane snapshots.
- Converted host-pack and strategy-pack startup loads to return both the active snapshot and any cached-snapshot degradation reason.
- Threaded strategy cache degradation into `StrategyPackRuntimeState` and host-pack cache degradation into settings UI state.
- Added a warning-style host-pack bundled-fallback status when the cached snapshot is unreadable.
- Added load-path regression coverage for unreadable and incompatible cached snapshots.

### 2026-04-23: Native Readiness Events

Status: COMPLETE.

- Added typed native runtime event kinds so readiness and shutdown are keyed off structured `runtime_ready` and `runtime_stopped` events.
- Split Android native event transport into first-class proxy, relay, and WARP rings instead of multiplexing non-proxy events through the proxy ring.
- Emitted explicit readiness events from the embedded proxy, relay, and WARP runtimes only after their listeners were actually bound.
- Switched Kotlin `awaitReady()` wrappers to wait for typed readiness events instead of treating `state == "running"` as sufficient.
- Updated contract fixtures, telemetry goldens, and wrapper/supervisor regression coverage for the stricter readiness handshake.

### 2026-04-23: Typed Telemetry Outcomes

Status: COMPLETE.

- Added a shared typed runtime telemetry outcome contract so polling distinguishes real snapshots, no-data runtime absence, and engine polling errors.
- Switched proxy, relay, WARP, and tunnel telemetry polling to return typed outcomes instead of collapsing failures into `null` or idle snapshots.
- Threaded per-runtime telemetry status through `ServiceTelemetrySnapshot`, diagnostics persistence, exports, and current diagnostics UI surfaces.
- Preserved existing tunnel failure handling while keeping proxy, relay, and WARP polling failures observational only in this pass.
- Bumped the diagnostics Room schema, added the new schema snapshot, and updated telemetry regression fixtures and goldens for the richer status model.

### 2026-04-23: Missing Orchestration Regression Tests

Status: COMPLETE.

- Added deterministic coordinator regressions for delayed handover retries, ignored late handover events after stop, and stale superseded runtime exits across proxy and VPN service modes.
- Hardened proxy, relay, and WARP runtime supervisors so completion callbacks from superseded runtime instances are ignored instead of tearing down replacement sessions.
- Expanded diagnostics bootstrap coverage to lock in one-time automatic-probe subscription wiring and preserve scheduling when runtime-history startup fails.
- Added app-startup bootstrap regressions for subsystem initialization ordering, swallowed detection-observer failures, distinct battery-banner clearing, missing crash-report handling, and continued strategy-pack UI propagation.

## Roadmap Hygiene

- Keep `ROADMAP.md` updated in the same change as every future roadmap-scoped implementation.
- Use this file for incremental hardening and productization follow-ups instead of resurrecting removed historical roadmaps.

## Removed Completed Roadmaps

The following completed roadmap files were removed because they had become
stale implementation archives rather than active planning documents:

- `ROADMAP-architecture-refactor.md`
- `ROADMAP-bypass-techniques.md`
- `ROADMAP-bypass-modernization.md`
- `docs/roadmap-execution-queue.md`
- `docs/roadmap-integrations.md`
