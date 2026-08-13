# DGN-1786592449526581: Add privacy-safe relay attempt stage trace

## Objective

Export a bounded, ordered, privacy-safe trace of the protocol stages actually
observed during one VLESS Reality relay attempt, including partial evidence
from failed attempts and explicit completeness when events were evicted.

## Ownership

- Native event collection and relay producers in `android-support`,
  `ripdpi-vless`, `ripdpi-relay-core`, `ripdpi-relay-android`, and
  `ripdpi-android-telemetry-adapter`.
- Kotlin contracts and runtime projection in `core/engine` and `core/service`.
- Typed Room persistence and migration in `core/diagnostics-data`.
- Redaction, archive schema, JSONL rendering, and fixtures in
  `core/diagnostics`.
- One writer owns native event serialization, the Room schema/migration, and
  archive schema/golden fixtures for the duration of the change.

## Execution

- [x] DGN-1786594877078339 Persist existing relay native events with connection-session provenance in live, terminal, terminal-outbox, and scan-finalization paths; retain allowlisted relay lifecycle kind and prove current `native-events.csv` compatibility plus privacy-safe terminal normalization with focused RED/GREEN Kotlin tests. #feature !high @item:DGN-1786592449526581
- [x] DGN-1786597438110594 Export a versioned full SHA-256 fingerprint of the canonical effective allowlisted strategy in `runtime-config.json`, keep the raw signature absent, and make offline analytics prefer the exported fingerprint with backward-compatible extraction tests. #feature !high @item:DGN-1786592449526581
- [x] DGN-1786601264405063 Correlate each typed runtime failure with only its exact same-session, same-timestamp persisted failure network snapshot; export identifier-free VPN/underlay path observations plus available resolver latency and handover state in `analysis.json`, preserve the projection in offline analytics, and prove that newer snapshots are never substituted. #feature !high @item:DGN-1786592449526581
- [ ] DGN-1786592505422071 Add optional typed stage fields, allowlisted span inheritance, runtime-scoped relay drain, and bounded drop accounting in `android-support` and `ripdpi-relay-android`; first prove cross-runtime isolation, ordering, defaults, and eviction with focused RED/GREEN locked Rust tests. #feature !high @item:DGN-1786592449526581
- [ ] DGN-1786592505437774 Emit attempt-correlated TCP, Reality TLS, VLESS request/one-shot response, SOCKS result, carrier-reuse, and terminal stream transitions in `ripdpi-vless` and `ripdpi-relay-core`; cover success, reset, cancellation, mux reuse, missing later stages, and no repeated read-path emission with focused RED/GREEN locked Rust tests. #feature !high @item:DGN-1786592449526581
- [ ] DGN-1786592505454192 Mirror every optional attempt-stage field through `ripdpi-android-telemetry-adapter` and `core/engine` without changing runtime schema version 3; prove absent old-producer fields and unknown newer-producer fields remain compatible with contract tests. #feature !high @item:DGN-1786592449526581
- [ ] DGN-1786592505470063 Persist relay native events in live and terminal `core/service` paths, add typed nullable fields to `NativeSessionEventEntity`, and migrate `core/diagnostics-data` Room schema from 10 to 11; prove session ownership, terminal retention, null preservation, and migration integrity with focused Kotlin tests. #feature !high @item:DGN-1786592449526581
- [ ] DGN-1786592505485564 Redact and export ordered `relay-attempt-traces.jsonl`, advance the diagnostics archive schema from 14 to 15, and expose retained/dropped completeness counts in `core/diagnostics`; prove ordering, partial traces, redaction, causal qualification, and deterministic rendering with archive tests and explicitly authorized fixture updates. #feature !high @item:DGN-1786592449526581
- [ ] DGN-1786592505501003 Validate the combined implementation with affected `cargo test --locked`, `cargo fmt --all -- --check`, affected locked Clippy gates, focused Gradle tests, `./gradlew staticAnalysis`, architecture health, privacy scans, task/OpenSpec validation, and owner-style plus async/performance review; record local, hosted CI, device, artifact, and deployment evidence as separate states. #test !high @item:DGN-1786592449526581

## Verification

- Native: focused crate tests for event-field serialization, runtime isolation,
  bounded eviction, stage ordering, direct/reset/cancel/mux flows, and one-shot
  response emission; then affected `cargo test --locked`, formatting, and
  Clippy gates.
- Kotlin: focused `core/engine`, `core/service`, `core/diagnostics-data`, and
  `core/diagnostics` tests for decoding, persistence, migration, redaction,
  archive rendering, and completeness.
- Repository: `./gradlew staticAnalysis`,
  `python3 scripts/ci/check_architecture_health.py`, `./taskctl validate`, and
  strict OpenSpec validation.
- Review: owner-style correctness review plus async cancel-safety and read-path
  performance review where the implementation changes those paths.
- Evidence boundary: local green does not imply hosted CI, physical-device,
  signed-artifact, release, or deployment proof.
