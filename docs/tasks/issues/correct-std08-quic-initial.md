---
id: DGN-1794211297803022
title: Correct STD-08 QUIC Initial parsing and probes
kind: bug
status: review
area: diagnostics
priority: high
owner: QUIC audit repair team
parent: null
blocked_by: []
spec_mode: required
openspec_change: correct-std08-quic-initial
created: 2026-11-09
updated: 2026-11-09
status_detail: Nine STD-08 fix commits were fast-forwarded and pushed to main at 7c475b8720; local tests and gates passed. Required remote CI and CodeQL evidence is pending.
---

## Goal

Correct the seven confirmed STD-08 audit findings without adding a full QUIC endpoint.

## Acceptance criteria

Separate fix commits for F1 through F7 and the reachable IP-target and UDP response-size variants, regression tests, independent review, applicable native/JNI/Kotlin checks, ci-gates, fast-forward integration into main, and an observed push to origin/main. External server and physical device tests are outside this repair scope.


## Parallel ownership

- Probe writer: F1 active ClientHello generation in packets probe/response code, adapter generation, and native consumer acceptance tests. Fixed fake templates stay unchanged.
- Packet writer: F3/F4/F6/F5 in packets build, frames, parse, datagram helpers, parse_seed, tamper, and dedicated regression modules. No generation changes in fake_initial.
- Consumer writer: F2 then F7 in Kotlin probe/response validation, native validator and JNI exports/tests. Own the adapter after the probe writer hands it over.
- Integrator: this portfolio, OpenSpec files, generated API/contract snapshots, combined validation and main integration. Cargo.lock, dependency versions, schemas, locales and goldens have no assigned changes. The integrator is the sole writer for the JNI symbol inventory: the user explicitly approved its exact regeneration from the actual ARM64 release library on 2026-11-09 (one added validation export, no removed exports).
- Serialize packets mod.rs additive exports/test module declarations at integration. Serialize response.rs by moving generation out before response validator changes. No parallel edits to serialized high-risk files.
