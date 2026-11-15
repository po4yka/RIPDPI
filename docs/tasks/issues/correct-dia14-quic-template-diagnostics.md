---
id: DGN-1794744778962822
title: Correct QUIC Initial and VN template diagnostics
kind: bug
status: doing
area: diagnostics
priority: high
owner: Diagnostics repair team
parent: null
blocked_by: []
spec_mode: required
openspec_change: correct-dia14-quic-template-diagnostics
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Report comparable QUIC v1 Initial and Version Negotiation observations in the DPI probe suite.

## Acceptance criteria

Cover the seven DIA-14 findings with separate behavior commits. Exercise correlated, malformed, delayed, missing, and interrupted replies with independent vectors and local UDP. Check localized results, run the CI source gates, and verify the push to main.

## Parallel ownership

- The native writer owns the packet validator, Rust JNI return type, and native binding assertions.
- The runner writer owns the Kotlin socket, per-attempt evidence, JNI decoding, and socket tests.
- The target writer owns host admission, suite target wiring, and their tests.
- The integrator owns classification, app presentation, all locales, and the combined JNI commit. The integrator serializes shared test and source hunks. There are no dependency or golden updates.
