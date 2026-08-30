---
id: AND-1786264762917810
title: Introduce a VPN-session Hilt scope to reset per-session service state
kind: feature
status: review
area: android
priority: medium
owner: codex
parent: EPC-1786264762917503
blocked_by: []
spec_mode: required
openspec_change: and-1786264762917810-introduce-vpn-session-hilt-scope
created: 2026-06-10
updated: 2026-08-30
source_wiki_pages: []
linked_task: null
status_detail: Implementation and local verification complete; final review found no CRITICAL/WARNING findings.
---

## Motivation

The 2026-06-10 Kotlin audit found Hilt has grown to **134 `SingletonComponent` modules** (up from 71+) with no custom VPN-session scope. Several service-layer singletons logically belong to a VPN-session lifetime — `ServiceStateStore`, `RootHelperManager`, `VpnAppExclusionPolicy`, `VpnDhtMitigationPolicy`, `NetworkFingerprintProvider` — yet are `@Singleton`, so state accumulated in one session persists into the next unless explicitly cleared (e.g., a stale `ServiceStateStore` emitting previous-session telemetry to new-session observers). The codebase already has the building blocks: `VpnServiceSessionComponent` / `ProxyServiceSessionComponent` `@DefineComponent` subcomponents reachable via `sessionComponentBuilderProvider`.

## Proposed change

1. Identify the service-layer singletons that are session-scoped in practice (start from the five named above; audit the rest of `core/service/.../services/`).
2. Migrate them from `@Singleton` into the existing `VpnServiceSessionComponent` / `ProxyServiceSessionComponent` scope (or a new `@VpnSessionScope`), so each VPN session gets a fresh instance.
3. Ensure session teardown disposes the scope; verify no singleton continues to hold session state across restart.
4. Add a `am kill`-style process-death / session-restart test (per `android-vpn-lifecycle.md`) asserting state does not bleed across sessions.

## Work ownership (2026-08-30)

- `codex` owns production Hilt components/modules, service lifecycle integration, OpenSpec/task artifacts, and all serialized shared files.
- `red_vpn_session_scope` owns exactly one new RED test file under `core/service/src/test/`; it must not edit production code or shared task/spec files.
- `map_vpn_session_scope` is read-only and owns architecture/injection-site mapping only.

## Implementation decision (2026-08-30)

The original audit predates the current service graph: `ServiceSessionScope`,
`VpnServiceSessionComponent`, and `ProxyServiceSessionComponent` already own the
coordinators, runtime supervisors, status reporters, VPN protect failure monitor,
and Xray session holders/controllers. This change completes the lifetime boundary
instead of introducing a duplicate component.

- `ServiceStateStore` remains process-global because app, widget, and diagnostics
  consumers observe the same projection. A `@ServiceSessionScope` owner now opens
  a generation-bound writer for every VPN/proxy component. The writer rejects late
  status, telemetry, and failure callbacks from a destroyed component. Session reset
  publishes status plus telemetry as one aggregate projection, switches the event
  generation before that projection becomes visible, and preserves only the
  process-local monotonic restart count.
- `RootHelperManager` remains process-global because the pre-service connection
  policy preflight starts it before a service component exists, and all callers
  coordinate one helper process and one UDS path.
- `VpnAppExclusionPolicy` and `VpnDhtMitigationPolicy` remain process-global: their
  implementations are stateless policy computation over immutable process caches.
- `NetworkFingerprintProvider` remains process-global: it is a stateless live
  snapshot facade shared by app, diagnostics, and both service modes.
- `GoogleAppsScriptRelayRuntime` was the only audited mutable singleton with a
  narrower safe lifetime, so it is no longer a singleton. It is intentionally
  per-relay-attempt (narrower than a service session): its factory, plus the
  NaiveProxy and pluggable-transport factories, obtains a fresh mutable wrapper from
  `Provider` for every start. Their process-wide managers remain the single
  subprocess/concurrency owners and are not counted as Hilt session-scoped objects.

The restart regression test exercises the stricter same-process boundary: two
distinct session-scoped owners open distinct writers over the same singleton graph.
Session A writes telemetry and queues a failure; session B starts from one clean
aggregate projection, rejects A's queued and late writes, accepts B's event, and
preserves only the monotonic restart count. `am kill` reconstructs the entire
singleton graph, so it cannot retain more state than this same-process case.

## Acceptance criteria

- [ ] PR enumerates which singletons moved to session scope and why each qualifies.
- [ ] Migrated objects get a fresh instance per VPN session; old-session state is gone on restart.
- [ ] Session-restart test confirms no cross-session state bleed (e.g., telemetry observers do not receive prior-session events).
- [ ] `./gradlew :core:service:testDebugUnitTest --locked` green; no Hilt graph errors.

## Risks / open questions

- Some of the five may be legitimately process-global (e.g., `RootHelperManager` if it owns a long-lived helper connection) — decide per object; do not over-scope.
- Moving scope can change injection sites; keep the change incremental, one object at a time, each in its own commit.

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, item 12 / H-1).
- `.claude/rules/android-vpn-lifecycle.md` (state across kill/restart cycles).
