# ADR-010: Direct-Mode Ranked-Arm Dispatcher

**Status:** Partial — P4.3.1 accepted; P4.3.2 / P4.3.3 / P4.3.4 deferred.

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

### P4.3.2 — Per-class attempt-budget enforcement

Wire the `attempt_budget` field to a runtime counter that backs off an arm
after the budget is exhausted.  Requires a new metric layer in
`DirectPathLearningState` to track per-(tuple, arm) attempt counts and reset
them on the next positive signal.  Estimated scope: ~120 lines + tests.

### P4.3.3 — Deterministic integration coverage

Add a test harness that exercises the full class-to-arm execution ladder:
for every `DirectPathBlockClass` variant, drive a synthetic connection attempt
through the real adaptive path and assert that `ranked_arms_for` returns the
expected ordering.  Requires a fake transport adapter or extension of
`packet-smoke`.  Estimated scope: ~150 lines of test infrastructure.

### P4.3.4 — Unify Config relay preset suggestions

`ConfigRelaySupport.resolveRelayPresetSuggestion` and
`TransportSpecificRemediationSupport.recommendTransportRemediation` are two
parallel selectors that map transport-capability evidence to a relay
recommendation.  P4.3.4 should route the Config surface through the same
`recommendTransportRemediation` selector (or a shared successor) so the two UI
surfaces stay in sync.  Requires a cross-crate refactor touching
`ripdpi-config`, `app/activities/`, and their unit tests.  Estimated scope:
~80 lines of Kotlin + tests.
