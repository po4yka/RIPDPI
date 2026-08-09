---
id: AND-1786264762917810
title: Introduce a VPN-session Hilt scope to reset per-session service state
kind: feature
status: backlog
area: android
priority: medium
owner: unassigned
parent: EPC-1786264762917503
blocked_by: []
spec_mode: required
openspec_change: and-1786264762917810-introduce-vpn-session-hilt-scope
created: 2026-06-10
updated: 2026-06-10
source_wiki_pages: []
linked_task: null
---

## Motivation

The 2026-06-10 Kotlin audit found Hilt has grown to **134 `SingletonComponent` modules** (up from 71+) with no custom VPN-session scope. Several service-layer singletons logically belong to a VPN-session lifetime — `ServiceStateStore`, `RootHelperManager`, `VpnAppExclusionPolicy`, `VpnDhtMitigationPolicy`, `NetworkFingerprintProvider` — yet are `@Singleton`, so state accumulated in one session persists into the next unless explicitly cleared (e.g., a stale `ServiceStateStore` emitting previous-session telemetry to new-session observers). The codebase already has the building blocks: `VpnServiceSessionComponent` / `ProxyServiceSessionComponent` `@DefineComponent` subcomponents reachable via `sessionComponentBuilderProvider`.

## Proposed change

1. Identify the service-layer singletons that are session-scoped in practice (start from the five named above; audit the rest of `core/service/.../services/`).
2. Migrate them from `@Singleton` into the existing `VpnServiceSessionComponent` / `ProxyServiceSessionComponent` scope (or a new `@VpnSessionScope`), so each VPN session gets a fresh instance.
3. Ensure session teardown disposes the scope; verify no singleton continues to hold session state across restart.
4. Add a `am kill`-style process-death / session-restart test (per `android-vpn-lifecycle.md`) asserting state does not bleed across sessions.

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
