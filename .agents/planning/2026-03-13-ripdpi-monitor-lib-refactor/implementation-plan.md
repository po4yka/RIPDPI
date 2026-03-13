# Implementation Plan

## Checklist

- [ ] Step 1: Restore a clean crate-local baseline before any extraction
- [ ] Step 2: Expand characterization coverage around the public session and JSON contract
- [ ] Step 3: Extract public contracts into `api.rs` and make `lib.rs` a re-exporting entry point
- [ ] Step 4: Extract reporting and low-level transport plumbing
- [ ] Step 5: Extract UDP relay helpers and add focused roundtrip coverage
- [ ] Step 6: Extract probe modules without changing probe outcomes
- [ ] Step 7: Extract classification and candidate-planning logic
- [ ] Step 8: Extract scan orchestration and slim `MonitorSession`
- [ ] Step 9: Move fixture support out of `lib.rs` and finish validation

## Plan

1. Step 1: Restore a clean crate-local baseline before any extraction
   Objective: Turn the current red baseline into a trustworthy safety net.
   Guidance: Make the smallest possible non-structural fixes inside `ripdpi-monitor` so the crate builds and validates again. Based on the current inspection, that means updating the in-file test fixture constructor to include the required `strategy_preset` field and deciding on a rustfmt normalization strategy for this crate before structural moves begin. If the formatter churn is large, isolate it in a dedicated mechanical commit so later module moves stay reviewable.
   Test requirements: Run `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-monitor`, `cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-monitor --all-targets`, and `cargo fmt --manifest-path native/rust/Cargo.toml -p ripdpi-monitor --check` until the crate-local baseline is green.
   Integration: No behavioral changes. This step exists only to establish a reliable starting point.
   Demo: The crate is green before the first refactor slice, and baseline failures are no longer mixed with structural work.

2. Step 2: Expand characterization coverage around the public session and JSON contract
   Objective: Lock down the behavior that downstream consumers actually observe before code moves.
   Guidance: Add or tighten tests around `MonitorSession::start_scan`, concurrent-start rejection, cancellation, `poll_progress_json`, `take_report_json`, and `poll_passive_events_json`. Extend end-to-end tests for both connectivity and strategy-probe flows if any public behavior is still inferred rather than asserted. Keep golden JSON tests authoritative for shape and summary wording.
   Test requirements: New tests should assert only public behavior and serialized output, not private helper implementation details.
   Integration: These tests become the guardrail for the later session, reporting, and orchestration extractions.
   Demo: The crate now fails fast on any change to progress sequencing, final reports, recommendation payloads, or passive-event draining.

3. Step 3: Extract public contracts into `api.rs` and make `lib.rs` a re-exporting entry point
   Objective: Separate the stable public surface from the internal implementation first.
   Guidance: Move all public enums, structs, and default helper functions into `api.rs`. Keep every public item re-exported from `lib.rs` so external imports do not change. Leave behavior untouched; this is a file move plus compile-fix step. After the move, `lib.rs` should mainly declare modules and `pub use` the crate API.
   Test requirements: Run the full crate-local validation gates unchanged. Add a narrow compile-level smoke test only if the re-export surface needs explicit guarding.
   Integration: This step reduces the root file immediately and makes later internal module extraction simpler because the contracts are already isolated.
   Demo: Downstream code still imports `ripdpi_monitor::ScanRequest` and `ripdpi_monitor::MonitorSession` exactly as before, but the public contracts no longer live inside a monolithic implementation file.

4. Step 4: Extract reporting and low-level transport plumbing
   Objective: Move concrete, low-level helpers out before touching the scan loops.
   Guidance: Extract shared-state update helpers and passive-event summary shaping into `reporting.rs`. Extract `TransportConfig`, `TargetAddress`, `ConnectionStream`, TLS setup, direct/SOCKS5 TCP connect logic, address resolution, and certificate-verification helpers into `transport.rs`. Keep signatures concrete and preserve existing `String` error messages.
   Test requirements: Keep all public characterization tests green. Add focused unit tests for transport helpers only where behavior is not already covered through higher-level probes.
   Integration: Probes and orchestration should call the new modules through `pub(crate)` functions without changing their control flow.
   Demo: The scan loops still behave the same, but transport setup and report/event shaping are no longer embedded inline inside `lib.rs`.

5. Step 5: Extract UDP relay helpers and add focused roundtrip coverage
   Objective: Isolate the most protocol-specific transport code into its own module.
   Guidance: Move UDP direct relay, SOCKS5 UDP ASSOCIATE, relay-address normalization, and UDP frame encode/decode helpers into `udp_relay.rs`. Keep the public behavior unchanged and preserve current timeout handling and relay semantics.
   Test requirements: Preserve the existing SOCKS5-backed DNS test. Add module-level tests for IPv4 and IPv6 SOCKS5 UDP frame roundtrips. Property-style coverage is acceptable here if it remains narrow and only strengthens encode/decode invariants.
   Integration: Probe modules should depend on `udp_relay` through small concrete helpers rather than duplicating protocol logic.
   Demo: DNS and QUIC probes still work through direct and SOCKS5 paths, while UDP relay logic becomes independently testable and reviewable.

6. Step 6: Extract probe modules without changing probe outcomes
   Objective: Separate probe execution by protocol while keeping result-shaping stable.
   Guidance: Move DNS-specific logic into `probes::dns`, domain/HTTP/TLS logic into `probes::domain`, TCP fat-header logic into `probes::tcp`, and QUIC logic into `probes::quic`. Keep shared parsing helpers near the probe that owns them unless a helper is genuinely reused. Move code mostly verbatim first; avoid opportunistic redesign.
   Test requirements: Existing unit tests for DNS substitution, DoH blocking, TLS certificate anomalies, blockpage detection, fat-header cutoff, whitelist-SNI success/failure, and QUIC classification must stay green. Add direct unit tests only for extracted helpers that lose indirect coverage.
   Integration: Orchestration keeps calling the same logical probe operations, but the protocol-specific code is now isolated behind module boundaries.
   Demo: The same targets produce the same `ProbeResult` outcomes and details, while the probe logic is organized by protocol instead of buried in one file.

7. Step 7: Extract classification and candidate-planning logic
   Objective: Remove strategy-specific reasoning from the scan loop bodies.
   Guidance: Move suite construction, candidate specs, scoring, pacing, deterministic ordering, candidate runtime preparation, baseline DNS tampering detection, failure weighting, ranking, and recommendation helpers into `candidate_planning.rs` and `classification.rs`. Keep these modules mostly pure and `pub(crate)`. Preserve current candidate IDs, labels, families, notes, scoring rules, pause ranges, and recommendation selection.
   Test requirements: Keep current tests for candidate catalog order, reordering by failure class, family interleaving, blocked-family selection, stable probe seeds, pause ranges, parser-candidate config shaping, DNS tampering baseline detection, and QUIC-breakage classification. Add direct unit tests for any helper whose coverage becomes too indirect after extraction.
   Integration: `run_strategy_probe_scan` should become a coordinator over planning, execution, and reporting rather than the place where every rule is encoded.
   Demo: Automatic probing still chooses the same winners and emits the same recommendation JSON, but the candidate and classification rules are now isolated from orchestration.

8. Step 8: Extract scan orchestration and slim `MonitorSession`
   Objective: Finish the main decomposition by making the session shell and scan runners explicit.
   Guidance: Move `run_scan`, request validation, connectivity scan coordination, strategy-probe coordination, and cancellation/report finalization into `orchestration.rs`. Keep `MonitorSession` in `session.rs` as a small owner of shared state, worker start/join, cancellation, and JSON polling. Preserve progress phases, messages, completion flags, and report summaries.
   Test requirements: Re-run the public session characterization suite and all golden tests. Add explicit cancellation assertions if the extraction changes control-flow structure enough to warrant a dedicated end-to-end test.
   Integration: This is the point where `lib.rs` should become genuinely thin. The orchestration module becomes the top-level internal dependency, not the crate root.
   Demo: `MonitorSession` remains the same external entry point, but nearly all scan behavior now lives in targeted internal modules instead of a single root file.

9. Step 9: Move fixture support out of `lib.rs` and finish validation
   Objective: Remove the last large in-file bulk that is unrelated to crate-root behavior.
   Guidance: Move the `#[cfg(test)]` fixture servers, wait helpers, golden scrubbers, and report helpers into `test_support.rs` or a small test-support module tree. Keep `tests/golden/*.json` and `tests/soak.rs` stable. If `tests/soak.rs` can reuse support helpers cleanly, do so sparingly; otherwise keep soak helpers separate to avoid awkward production-module exposure.
   Test requirements: Run the full crate-local validation gates again, and manually confirm the ignored soak tests still compile cleanly even if they are not executed by default.
   Integration: This step leaves a thin `lib.rs`, cohesive internal modules, and test support that no longer overwhelms production code.
   Demo: The crate layout is readable, the public surface is unchanged, and the final validation gates pass with module boundaries that match the real responsibilities of the original file.

## Final Validation Gates

Use these gates after every extraction slice and at the end:

1. `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-monitor`
2. `cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-monitor --all-targets`
3. `cargo fmt --manifest-path native/rust/Cargo.toml -p ripdpi-monitor --check`

Optional follow-up gate after the main refactor:

4. `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-monitor --test soak -- --ignored`

The refactor should not advance to the next step until the current slice is green on the three required gates.
