# ADR-010: Direct-Mode Ranked-Arm Dispatcher

**Status:** Accepted. P4.3.1 / P4.3.2 / P4.3.3 implemented (Phase C,
2026-04-28). P4.3.4 implemented (Phase F.3, 2026-04-28): a singleton
`LatestDirectModeOutcomeStore` shuttles the most recent
`DirectModeVerdict` from the diagnostics workflow into ConfigViewModel,
which now passes the corresponding `TransportRemediationKind` to
`resolveRelayPresetSuggestion` exactly as Diagnostics and Home do.

---

## Context

The direct-path connection path (`runtime/direct_path_learning.rs`) collects
per-host/IP-set transport observations (UDP failure, TLS post-ClientHello
interference, QUIC success, all-IPs failed, TCP-fallback timeout) and emits
telemetry signals.  Before this ADR, the implicit priority of which transport
arm to try next was encoded only in the caller's ad-hoc conditionals and in the
upstream `StrategyEvolver` UCB1 bandit — neither of which is aware of the
specific failure evidence stored by `DirectPathLearningState`.

The ROADMAP (epic "Direct-Mode Diagnostic Orchestrator", ROADMAP:138) calls for
four improvements:

| ID | Description |
|----|-------------|
| P4.3.1 | Explicit ranked-arm dispatcher |
| P4.3.2 | Per-class attempt-budget enforcement |
| P4.3.3 | Deterministic integration coverage for the full class-to-arm execution ladder |
| P4.3.4 | Unify Config relay preset suggestions onto the same transport-remediation selector |

---

## Decision (P4.3.1 — implemented in this PR)

### New types

Two new `pub(super)` types are added to `direct_path_learning.rs`:

**`DirectPathBlockClass`** — an enum derived from `TupleState` that names the
observed failure pattern for a given (host, IP-set) tuple:

| Variant | Condition |
|---------|-----------|
| `Clean` | No negative evidence recorded |
| `QuicBlocked` | `udp_failed == true` |
| `TlsPostClientHello` | `tls_post_client_hello_failed == true` |
| `QuicBlockedAndTlsPostClientHello` | Both flags true |
| `NoTcpFallback` | Terminal = `NoTcpFallbackDetected` |
| `AllIpsFailed` | Terminal = `AllIpsFailed` |
| `QuicConfirmed` | Terminal = `QuicSuccess` |

**`RankedArm`** — a single candidate transport arm with a normalised score, the
block class that produced it, and a placeholder `attempt_budget` field.

### New methods on `DirectPathLearningState`

- `block_class_for(host, targets) -> DirectPathBlockClass` — derive the current
  class for a tuple without side effects.
- `ranked_arms_for(host, targets) -> Vec<RankedArm>` — return a score-descending
  list of transport arms for the current block class.

### Arm priority table

| Block class | Rank 0 | Rank 1 |
|-------------|--------|--------|
| `Clean` | `quic` (0.9) | `tcp_plain` (0.8) |
| `QuicBlocked` | `tcp_plain` (0.9) | `tcp_tls_split` (0.7) |
| `TlsPostClientHello` | `tcp_tls_split` (0.9) | `tcp_plain` (0.6) |
| `QuicBlockedAndTlsPostClientHello` | `tcp_tls_split` (0.9) | `tcp_plain` (0.5) |
| `NoTcpFallback` | `quic` (0.8) | `tcp_plain` (0.3) |
| `AllIpsFailed` | `relay_fallback` (0.9, budget=1) | — |
| `QuicConfirmed` | `quic` (1.0) | `tcp_plain` (0.4) |

Scores are fixed expert priors; dynamic UCB1 weighting is deferred to P4.3.2.

### `attempt_budget`

Set to the constant `DEFAULT_ATTEMPT_BUDGET = 3` for all arms except
`relay_fallback` (budget = 1).  This field is a forward-compatibility hook for
P4.3.2; no enforcement logic is wired yet.

---

## Consequences

- Callers that want to iterate arms in priority order now have a stable,
  testable API instead of implicit if/else cascades.
- The `StrategyEvolver` UCB1 bandit continues to operate independently; this
  dispatcher adds a direct-path-specific layer on top of the existing
  transport-failure evidence.
- No existing behaviour changes — `ranked_arms_for` is a read-only query; no
  call sites are migrated in this PR (see Future Work below).

---

## Future Work

### P4.3.2 — Per-class attempt-budget enforcement (IMPLEMENTED 2026-04-28)

Implementation (`runtime/direct_path_learning.rs`):

- `TupleState` now carries an `arm_attempts: HashMap<&'static str, u32>` map
  keyed by the static arm label.
- New `DirectPathLearningState::note_arm_attempt(host, targets, arm_label)`
  bumps the counter for the matching tuple/arm.
- `ranked_arms_for` subtracts the recorded attempts from each arm's
  `attempt_budget`, drops arms whose budget is exhausted, and falls back to a
  single `relay_fallback` entry (class = `AllIpsFailed`, budget = 1) when
  every preferred arm is exhausted.
- `clear_negative_state` resets the per-arm map alongside the rest of the
  negative evidence, so a positive signal restores the original budgets for
  the new block class.

Verified by 4 new unit tests (decrement, drop-on-exhaust, escalate-to-relay,
positive-signal-resets-budget) plus the multi-step ladder integration test
under P4.3.3.

### P4.3.3 — Deterministic integration coverage (IMPLEMENTED 2026-04-28)

A single multi-step integration test
(`class_to_arm_ladder_walks_clean_quic_blocked_exhausted_relay_and_back`)
drives one tuple through every transition: clean → QuicBlocked → exhausted
tcp_plain → exhausted tcp_tls_split → relay_fallback escalation → positive
TCP signal restores the clean ranking. Each step asserts the ranked-arm
ordering, the remaining attempt budget, and the active block class, so any
future change to the priority table or the budget bookkeeping has to update
one canonical assertion site.

### P4.3.4 — Unify Config relay preset suggestions (IMPLEMENTED 2026-04-28)

The Phase C landing wired `ConfigRelaySupport.resolveRelayPresetSuggestion`
to optionally accept a `TransportRemediationKind` and, when non-null,
source the reason string from `TransportRemediationKind.toRelayPresetReason()`
instead of the telemetry-evidence path. That made the Config surface
selector-compatible with Diagnostics and Home but left the Config call
site without a verdict to pass.

Phase F.3 closed the loop:

- New `LatestDirectModeOutcomeStore` interface
  (`core/data/runtime-state/.../LatestDirectModeOutcomeStore.kt`) holds
  the most recent `DirectModeVerdict` fields (`result`, `reasonCode`,
  `transportClass`, `recordedAt`) as a `StateFlow<…?>`. The richer
  `DiagnosticsCapabilityEvidence` list is intentionally excluded so the
  store can live in `core/data/runtime-state` without adding a
  `core/diagnostics` dependency. Surfaces that need the QUIC-vs-browser
  evidence nuance (Home) read it directly from the run outcome they
  already hold.
- Concrete `DefaultLatestDirectModeOutcomeStore` (singleton) +
  `LatestDirectModeOutcomeStoreModule` Hilt binding live in
  `core/service`, mirroring the existing `PolicyHandoverEventStore`
  pattern.
- `MainHomeDiagnosticsActions` injects the store and publishes a
  `LatestDirectModeOutcomeSnapshot` (or null) every time a composite-run
  outcome arrives in `runFullAnalysis` and `runQuickAnalysis`. Verdicts
  without a direct-mode result clear the store so a stale entry from a
  previous run never leaks into the Config surface.
- `ConfigViewModel` injects the store, adds its `outcome` flow as a
  fourth source to the existing `combine(...)` that produces
  `uiState`, and threads the resulting kind into
  `resolveRelayPresetSuggestion(transportRemediation = ...)`. Pre-existing
  paths (telemetry evidence, capability records) remain in charge when
  no verdict is available.

The chain — verdict → snapshot → store → ConfigViewModel.combine →
recommendTransportRemediation → resolveRelayPresetSuggestion → UI
suggestion — is exercised end-to-end by the existing unit suite plus a
new ConfigViewModelTest case (`direct mode snapshot threads through to
relay preset reason`) that confirms the OWNED_STACK_ACTION reason
surfaces, and a fall-through test
(`null direct mode snapshot leaves resolution to telemetry path`)
guarding the no-regression case.
