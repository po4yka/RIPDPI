# ADR-002: Diagnosis Classifier Authority

**Status:** Proposed | 2026-04-15
**Deciders:** RIPDPI maintainers

## Context

Blocking-diagnosis classification is currently duplicated across two layers:

- `native/rust/crates/ripdpi-monitor/src/classification/diagnosis.rs` (43.8 KB)
  — Rust engine that inspects packet captures, TLS fingerprints, DNS responses,
  and timing signals to produce blocking observations.
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsServicesImpl.kt`
  and `DiagnosticsFindingProjector.kt`
  — Kotlin layer that finalises scan sessions and runs a second classification
  pass over the Rust-produced observations before persisting findings.

Running dual-classification in scan finalization means the two layers can
disagree silently, and Kotlin overrides can mask Rust signal without a clear
policy on which result wins.

The two candidate authorities are:
1. Rust authoritative (`ripdpi-monitor/src/classification/diagnosis.rs`);
   Kotlin only decorates (UI labels, user-facing strings, persistence mapping).
2. Kotlin authoritative (`DiagnosticsServicesImpl` + `DiagnosticsFindingProjector`);
   Rust emits raw observations only, no final verdict.

## Decision

Rust is the authoritative diagnosis classifier.
`native/rust/crates/ripdpi-monitor/src/classification/diagnosis.rs`
produces the final blocking verdict. Kotlin receives a structured result and
maps it to UI presentation and database records without re-classifying.

## Rationale

All evidence used for classification (raw packets, TLS records, DNS wire
responses, timing deltas) is available only inside the Rust monitor. Kotlin
receives a summarised observation struct over JNI; it lacks the byte-level
context needed to override or refine the verdict reliably. Keeping the decision
in the layer that holds the evidence eliminates the dual-classification path and
makes the Rust test suite (`ripdpi-monitor/src/tests.rs`) the definitive
correctness gate.

Option 2 (Kotlin authoritative) would require either shipping raw packet data
over JNI (expensive and fragile) or accepting that Kotlin classifies from
incomplete information.

## Consequences

Positive:
- Single classification path; Rust tests are the ground truth.
- JNI surface shrinks: Rust emits a verdict enum, not raw observations.
- `DiagnosticsFindingProjector` becomes a pure mapping layer with no logic.

Negative:
- Kotlin cannot independently adjust classification without a Rust change and
  native rebuild.
- Verdict enum must be versioned carefully across APK and native library
  updates.

## Owner

`native/rust/crates/ripdpi-monitor/src/classification/diagnosis.rs`
