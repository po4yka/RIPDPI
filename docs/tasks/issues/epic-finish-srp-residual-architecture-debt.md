---
title: Epic - Finish SRP residual architecture debt
type: epic
status: done
area: epic
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [x] #task Epic - Finish SRP residual architecture debt #repo/RIPDPI #area/epic #status/done ⏫

## Goal

Close every remaining SRP and dependency-direction finding from the follow-up
architecture audits, including the newly identified P2 service/runtime hotspots.
This epic is the coverage contract for the residual architecture debt: every
current P2/P3 finding in the listed paths must either be fixed by a child task or
explicitly replaced by a narrower architecture decision with matching guardrail
coverage.

The work should move policy decisions, UI taxonomy, feature state, diagnostics
facades, Android service lifecycle glue, WARP provisioning/bootstrap behavior,
and socket runtime duties behind focused feature-owned contracts. The result
should make invalid states harder to represent, reduce broad public facades, and
keep lifecycle/telemetry/reporting code from owning unrelated runtime policy.

## Why now

The first split reduced several major hubs, but the follow-up audits still found
residual aggregates in the proxy runtime, Android VPN service shell, service
runtime/session layer, VPN and proxy telemetry paths, status reporting, WARP
bootstrap/provisioning, diagnostics facades, runtime platform facade, desync TCP
model, settings UI, DNS settings UI, detection UI, diagnostics context mapping,
and mode editor. Those broad contracts keep unrelated changes flowing through
shared surfaces and allow the same classes/crates to regress into coordination
hubs after local refactors.

## Required finding coverage

This epic must cover the following audit findings before it can move to review.
Do not close the epic by only moving code or updating baselines; the architecture
shape must actually change.

**P2 coverage**
- `native/rust/crates/ripdpi-proxy-runtime/Cargo.toml`: remove direct
  policy-engine edges from socket execution by moving adaptive/runtime-policy
  selection behind ports or adapter crates.
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/ServiceRuntimeCoordinator.kt`:
  split lifecycle, permission watchdog, telemetry-loop ownership, network
  handover retry, and shared proxy-stack orchestration into focused owners.
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/ServiceSessionComponents.kt`:
  split service-session DI by runtime family so proxy, VPN, bootstrap, relay,
  WARP, tunnel, DNS failover, protect-socket, status, and coordinator wiring do
  not share one module.
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/ServiceStatusReporter.kt`:
  separate status persistence, failure emission, telemetry projection, network
  fingerprint hashing, and strategy-family reporting.
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/ProxyServiceRuntimeCoordinator.kt`:
  separate proxy-stack lifecycle, handover restart, telemetry polling, direct
  path telemetry, notification updates, and status reporting.
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/RipDpiVpnService.kt`:
  thin the Android service shell by delegating session creation, protect-socket
  lifecycle, JNI protect registration, notification rendering, and underlying
  network binding.
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/VpnServiceRuntimeCoordinator.kt`:
  split VPN runtime composition so proxy stack, tunnel runtime, DNS policy,
  protect failure handling, supervisor exits, telemetry callbacks, and active
  policy application are composed by narrower owners.
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/VpnTelemetryCoordinator.kt`:
  keep telemetry polling separate from DNS tunnel rebuilds, encrypted-DNS
  recovery, fatal failure classification, status mutation, and service stop.
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/WarpBootstrapProxyRunner.kt`:
  move WARP bootstrap proxy preferences and proxy-runtime construction policy
  out of WARP enrollment flow.
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/WarpRuntimeSupervisor.kt`:
  split WARP credential/endpoint/provisioning resolution from runtime
  start/readiness/exit supervision.

**P3 coverage**
- `native/rust/crates/ripdpi-diagnostics-runner/src/lib.rs`: expose only
  execution-owned runner APIs from the root.
- `native/rust/crates/ripdpi-diagnostics-net/src/lib.rs`: make compatibility
  re-exports explicit, narrow, and opt-in/deprecated.
- `native/rust/crates/ripdpi-runtime-platform/src/lib.rs`: split the broad
  platform operation facade by operation family.
- `native/rust/crates/ripdpi-config/src/model/tcp.rs`: replace the TCP chain
  field bag with typed variants or per-step payload structs.
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/settings/AdvancedSettingsTaxonomy.kt`:
  move setting identifiers/action contracts into feature-owned registries.
- `app/src/main/kotlin/com/poyka/ripdpi/activities/SettingsUiState.kt`: split
  feature state into feature-owned UI state modules with a small aggregate.
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/settings/AdvancedSettingsBinder.kt`:
  split settings mutations into feature-owned binders.
- `app/src/main/kotlin/com/poyka/ripdpi/activities/DiagnosticsUiContextSupport.kt`:
  split diagnostics context mapping by presentation concern.
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/dns/DnsSettingsScreen.kt`:
  split resolver catalog, local input state, validation, protocol sections, and
  save actions into focused modules.
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/detection/DetectionCheckScreen.kt`:
  split route/permission handling, result summary, recommendations, category
  cards, history/community sections, charts, dialog hosts, and sharing.
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/config/ModeEditorScreen.kt`:
  split the main composable below the worsened baseline into route shell,
  section renderers, field editors, validation/errors, and action rows.

## Scope

- In scope: the residual child findings captured from the SRP follow-up audit.
- In scope: any additional current P2/P3 architecture-health indicators on the
  listed paths, even if a child task title is narrower than the final fix.
- In scope: architecture baseline cleanup only after the corresponding debt has
  been removed and the stale baseline path is no longer current.
- Out of scope: unrelated feature work, baseline increases, or broad behavior
  changes that are not required to isolate the identified responsibilities.

## Ship definition

- [x] Proxy runtime socket execution depends on selected-decision ports instead
    of directly linking policy/parsing engines.
- [x] VPN telemetry no longer owns lifecycle stop/fatal-failure/DNS refresh
    policy.
- [x] Service runtime/session DI and proxy-mode runtime orchestration are split
    by runtime family.
- [x] VPN Android service shell and VPN coordinator delegate platform lifecycle
    glue and runtime-family composition to focused owners.
- [x] WARP bootstrap/provisioning is separated from proxy runtime construction
    and WARP process supervision.
- [x] Service status reporting separates persistence, network identity,
    telemetry projection, and strategy reporting policy.
- [x] Diagnostics runner root exposes only execution-owned API.
- [x] Diagnostics and platform compatibility facades are explicit, narrow, and
    opt-in.
- [x] TCP chain step invalid combinations are impossible or centrally rejected
    by typed variants.
- [x] Settings and config UI aggregates are split along feature ownership.
- [x] Architecture gates no longer report worsened long-composable baselines for
    the covered screens.
- [x] `scripts/ci/check_architecture_health.py --check` reports no new or
    worsened P2/P3 entries for the covered paths, and any stale baseline entries
    are removed only after verifying the current code no longer matches them.
- [x] No baseline is increased as a substitute for refactoring. Any accepted
    debt needs a separate explicit task, not this epic's completion.
- [x] Existing Kotlin, Rust, architecture, and screenshot validations stay green.

## Child tasks

**Runtime and diagnostics**
- [[Isolate proxy runtime policy decisions behind ports]]
- [[Split service runtime coordinator by phase ownership]]
- [[Split service session DI by runtime family]]
- [[Split VPN telemetry lifecycle responsibilities]]
- [[Thin VPN service shell platform lifecycle glue]]
- [[Split VPN coordinator runtime composition]]
- [[Extract service status telemetry projection policy]]
- [[Split proxy coordinator lifecycle and telemetry duties]]
- [[Extract WARP bootstrap proxy runtime construction policy]]
- [[Split WARP provisioning from runtime supervision]]
- [[Narrow diagnostics runner public surface]]
- [[Move diagnostics-net facade behind explicit compatibility namespace]]
- [[Narrow runtime platform operation facade]]
- [[Replace TcpChainStep field bag with typed variants]]
- [[Split diagnostics context mapper by presentation concern]]

**Settings and UI**
- [[Decentralize advanced settings taxonomy]]
- [[Split advanced settings binder by feature]]
- [[Split settings screen state by feature]]
- [[Split DNS settings screen into focused sections]]
- [[Decompose detection check screen responsibilities]]
- [[Split mode editor composable below baseline]]

Child tasks roll up via the TaskNotes relationships view on this note.

## Links

- [[ripdpi-android]]
- Child issues: 21
