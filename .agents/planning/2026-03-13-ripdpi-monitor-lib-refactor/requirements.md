# Requirements

## Scope

Create a planning-only, behavior-preserving refactor for `native/rust/crates/ripdpi-monitor/src/lib.rs`.
The goal is to decompose the 5,126-line crate entry file into cohesive internal modules while keeping the crate's public behavior stable for Rust, JNI, and Kotlin consumers.

No production refactor is implemented in this pass. The outcome is a test-first design and migration plan.

## Verified Current Responsibilities

Code inspection confirms that `lib.rs` currently owns all of the following:

1. Public request, progress, report, strategy-probe, and event contracts.
   The crate root defines the public serde-backed API types consumed by `ripdpi-android` and mirrored in Kotlin diagnostics models.

2. Session lifecycle and JSON polling.
   `MonitorSession` owns worker-thread startup, cancellation, report/progress/event retrieval, and JSON serialization for FFI-facing polling methods.

3. Connectivity scan orchestration.
   `run_scan`, `run_connectivity_scan`, and cancellation/report persistence coordinate DNS, domain reachability, and TCP fat-header probes.

4. Strategy-probe orchestration.
   `run_strategy_probe_scan` owns base config validation, suite construction, baseline DNS tampering detection, TCP and QUIC candidate execution, short-circuit behavior, recommendation selection, and final summary building.

5. Candidate planning and scoring.
   The file contains candidate catalogs, suite builders, pacing and ordering helpers, runtime config shaping, candidate warmup, scoring, and winning-candidate selection.

6. Probe execution.
   The file contains DNS, HTTP, HTTPS/TLS, QUIC, and TCP fat-header probing plus lower-level helpers for parsing responses and building observations.

7. Transport and UDP relay plumbing.
   The file contains direct and SOCKS5 TCP connection logic, UDP ASSOCIATE relay logic, SOCKS5 UDP frame encoding and decoding, TLS stream setup, and temporary embedded proxy runtime management.

8. Result classification and event shaping.
   The file classifies baseline failures, TLS and transport signals, QUIC failures, fat-header outcomes, probe success levels, event severity, and probe-event summary strings.

9. JSON contract stability support.
   The file shapes progress, report, candidate summary, and passive event payloads that are validated by golden JSON tests and consumed by app-side Kotlin serialization.

10. Fixture and test support.
   The in-file `#[cfg(test)]` module provides local UDP DNS, HTTP, TLS, SOCKS5 relay, and fat-header servers; golden JSON helpers; and scan/session helpers. The crate also has `tests/golden/*.json` and `tests/soak.rs`.

## External Consumers And Stability Constraints

The refactor must preserve compatibility with these existing consumers:

1. `native/rust/crates/ripdpi-android/src/lib.rs`
   JNI entry points construct and poll `MonitorSession` directly.

2. `core/diagnostics/src/main/java/com/poyka/ripdpi/diagnostics/Models.kt`
   Kotlin serialization mirrors the Rust JSON schema for requests, progress, reports, strategy-probe payloads, and native events.

3. `native/rust/crates/ripdpi-monitor/tests/golden/*.json`
   Golden files lock the observable JSON contract for progress, reports, and passive events.

4. `native/rust/crates/ripdpi-monitor/tests/soak.rs`
   Soak coverage exercises long-running session and polling behavior.

## Current Safety Net

The target file already has meaningful behavior coverage:

1. Unit and characterization tests for DNS, domain, TLS, TCP fat-header, candidate ordering, deterministic pacing, DNS tampering classification, strategy recommendations, and JSON goldens.

2. End-to-end `MonitorSession` tests that exercise structured report generation and passive-event draining.

3. Separate soak tests for repeated session creation, polling, and fixture-backed scans.

## Known Baseline Issues To Plan Around

The current crate is not fully green before any refactor work:

1. `cargo test -p ripdpi-monitor` currently fails because `minimal_ui_config()` in `lib.rs` no longer initializes the newly required `strategy_preset` field on `ProxyUiConfig`.

2. `cargo clippy -p ripdpi-monitor --all-targets` is blocked by the same compile failure.

3. `cargo fmt -p ripdpi-monitor --check` reports existing rustfmt drift in `src/lib.rs`.

The implementation plan must treat baseline restoration as an explicit first-class step before structural extraction.

## Required Outcome

The refactor is complete only when all of the following remain true:

1. Public API shape is preserved.
   Existing public types remain available from `ripdpi_monitor::...`, and `MonitorSession` retains its externally visible constructor and polling methods.

2. JSON behavior is preserved.
   Serde field names, defaults, `skip_serializing_if` behavior, output summaries, and passive-event payload shape remain stable unless a test is explicitly updated for an intended, reviewed change.

3. Performance-sensitive paths are preserved.
   Probe execution, SOCKS5/UDP relay logic, TLS connection setup, candidate pacing, and scoring stay allocation-conscious and avoid unnecessary dynamic dispatch or architectural indirection.

4. `lib.rs` becomes thin and readable.
   The crate root should primarily declare modules, re-export the public surface, and keep only minimal top-level glue if needed.

5. Ownership and error handling stay explicit.
   Favor private structs, `pub(crate)` helpers, and existing `Result<T, String>` boundaries over trait-heavy abstractions or opaque error stacks.

6. The refactor stays local to this crate.
   It must not require documentation churn in other planning directories or behavioral changes in unrelated components.

## Target Module Boundaries

The design must support at least these internal boundaries:

1. `api` or `contracts`
   Public serde-facing structs, enums, and default-value helpers.

2. `session` or `orchestration/session`
   `MonitorSession`, shared state, worker lifecycle, cancellation, and JSON polling entry points.

3. `orchestration`
   Connectivity and strategy-probe scan coordination, progress updates, and cancellation/report finalization.

4. `candidate_planning`
   Strategy suites, candidate catalogs, config shaping, deterministic pacing, and candidate scoring support.

5. `probes`
   DNS, domain/HTTP/TLS, TCP fat-header, and QUIC probe execution plus probe-specific parsing helpers.

6. `udp_relay`
   Direct UDP and SOCKS5 UDP ASSOCIATE transport helpers and frame encode/decode logic.

7. `classification`
   Baseline failure classification, probe-outcome interpretation, ranking, and recommendation decision helpers.

8. `json_shaping` or `reporting`
   Progress/report/event updates, event summaries, probe detail lookup helpers, and output-stability helpers.

9. `fixture_support` or `test_support`
   In-crate test-only fixture servers, scan helpers, and golden scrubbers.

Additional low-level modules such as `transport` are acceptable if they improve cohesion without obscuring the design.

## Testing Requirements

1. Characterization-first.
   Before moving a responsibility, add or tighten tests around the currently observable behavior it produces.

2. Unit tests for extracted modules.
   Candidate planning, classification, probe parsing, UDP relay framing, and JSON/reporting helpers should gain direct module-level tests once moved.

3. Integration tests for end-to-end flows.
   `MonitorSession` connectivity and strategy-probe flows, cancellation, and JSON-output stability remain covered at the crate level.

4. Property-style tests only where they add real value.
   Acceptable targets are roundtrip invariants for SOCKS5 UDP frame encode/decode and deterministic hash/pacing behavior. Avoid broad randomized testing for simple table-driven logic.

5. Golden stability remains part of the contract.
   Existing JSON goldens stay authoritative unless an intentional contract change is explicitly reviewed.

## Non-Goals

1. No public behavior redesign.
   This refactor does not change scan semantics, recommendation rules, summary wording, or Kotlin/JNI integration.

2. No concurrency model rewrite.
   The background thread, `Arc<Mutex<SharedState>>`, and cancellation flag model stays in place during this decomposition.

3. No transport or probe algorithm rewrite.
   Internal code may move, but the networking behavior, thresholds, timeouts, and scoring heuristics are not being redesigned in this pass.
