## Context

Task SVC-1791025113925875 follows a code-based deletion audit. The working selector already excludes Cloudflare from automatic selection and protects manual changes with revision CAS. Detection auto-tune always returns empty, while real advice has unused string routes. Native monitor-adapter contains only reexports; diagnostics compatibility duplicates canonical owners.

## Goals / Non-Goals

- Deliver typed user-directed advice, pre-start selector reconciliation, safe active-session switching and actual candidate transport payload probes.
- Remove the audited obsolete modules without replacing their names with compatibility shims.
- Preserve offline operation, explicit user action, manual-only Cloudflare, privacy, VPN protection and fail-closed lifecycle.
- No new backend, dependency, arbitrary probe source, credential authority, JNI/protobuf/wire version, golden blessing, or automatic heuristic settings application.

## Decisions

- Core detection owns a small typed recommendation destination; app navigation maps it to existing typed routes through a dedicated callback. Informational advice has no action.
- The selector store remains the provenance/revision authority. Reconcile selected members into relay settings before start, then dispatch active changes through service-owned session coordination rather than stopping the observing service.
- Use the existing candidate relay runtime/probe facilities behind MemberLatencyProbe. Execute the configured probe through a candidate endpoint with bounded deadlines and response consumption; retain the existing latency/tolerance decision and revision CAS. Scope evidence to current profile configuration and network; cancellation/unsupported candidates never count as success.
- Remove settings/selector obsolete types only after retaining the production policy regression tests. Remove auto-tune shell tests only after moving negative evidence assertions to recommendations.
- Remove monitor-adapter and use canonical classifier/config dependencies; keep monitor-lane-adapter enforced seam but remove its unused inventory. Inline HistoryMutationRunner, DiagnosticsViewModelBootstrapper and warmup forwarding module while preserving scheduling and platform ownership. Remove diagnostics aliases and duplicate mappings; retain canonical implementations and explicit deferTerminal=false in migrated tests.

## Contracts and ownership

- Root owns selector app/service/data integration and diagnostics compatibility/wrappers, portfolio/spec state and integration. Native writer owns Rust/manifests/lock and native docs. Detection writer owns detection/navigation and all locales. Exact ownership is recorded in the portfolio task.
- Existing persistence keys/provenance remain; no schema migration is planned. Runtime commands must carry current selection identity and report non-success on stale or rejected apply.
- All outbound candidate sockets retain existing VPN protection/owned-UID routing rules; exports retain redaction. No platform adapter or real bootstrap recovery interface is removed.

## Risks / Trade-offs

- Lifecycle cancellation or concurrent Stop: serialize switches through session coordination, validate generation, retain a blocking barrier and exercise cancellation tests.
- TCP-connect false positives: prove handshake and bounded response through transport; deterministic local fixtures exercise stalls and failures.
- Stale network/configuration evidence: compare captured generation before applying or publishing.
- UI routes or locale gaps: explicit destination mapping, interaction tests and locale lint across all ten locales.
- Large native workspace: use the machine build gate, <=4 jobs/workers, targeted native tests plus required architecture and affected analysis; report blocked gates honestly.

## Migration Plan

Forward update every call site and remove obsolete interfaces in atomic commits. Roll back a failing atomic commit by an authorized additive revert, not shared-history rewrite. Rebase each job on origin/main, rerun combined-tree gates, fast-forward main and push each finished implementation task. Validate with gated affected Gradle unit suites, locale lint and staticAnalysis, cargo metadata --locked, native architecture/health checks, gated affected Rust tests/clippy, and available Android device smoke. Record local, hosted CI and device outcomes separately.
