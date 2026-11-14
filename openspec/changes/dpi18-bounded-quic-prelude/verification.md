---
task_id: ENG-1794637894202698
change: dpi18-bounded-quic-prelude
commit_sha: 2d855b3b0b6ca602597750da28d491c9dc41a67a
local: blocked
local_evidence: "974 Rust tests and 85 Kotlin model tests passed with zero skipped; strict Clippy, just ci-gates and locked metadata passed. Full staticAnalysis was run but blocked by Maven Central No route to host."
remote_ci: not_applicable
remote_ci_evidence: Remote runs after push are separate from the observed local checks.
device: not_applicable
device_evidence: No Android API or service lifecycle change; validation uses host and real loopback tests.
artifact: passed
artifact_evidence: "Owning API snapshots were regenerated and checked. Code review found no actionable issue. Model XML, reviewed patches, hook output and staticAnalysis blocker logs are in Downloads/RIPDPI_network_audits/DPI-18-fix-evidence-20261114."
deployment: not_applicable
deployment_evidence: No release or deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DPI18-BUDGET | ENG-1794638194964777 | Full Kotlin model:85 passed; five-crate Rust suites:974 passed | Passed |
| REQ-DPI18-SIZE | ENG-1794638196470855 | Full Rust suites include padded/unpadded v1/v2 boundary cases | Passed |
| REQ-DPI18-SEND | ENG-1794638196470855 | Real IPv4/IPv6 loopback, original once, TTL and error cases; upstream pending-ICMP test retained | Passed |
| REQ-DPI18-SCOPE | ENG-1794638197694196 | Strict Clippy, just ci-gates, locked metadata and independent rebased code review passed; full staticAnalysis blocked | Partial |

## Commands and observed outcomes

- Full nextest suites for ripdpi-packets, ripdpi-config, ripdpi-proxy-config, ripdpi-desync and ripdpi-proxy-runtime-desync-adapter, with --locked and four jobs:974 passed,0 skipped.
- Strict Clippy for those five crates, --all-targets -- -D warnings:exit0.
- Doctests for those crates:0 cases,exit0; no doctest behavior claim.
- :core:data:model:testDebugUnitTest --offline:85 tests,0 failures/errors/skipped. Original XML is retained in the local evidence directory.
- Owning API update and just ci-gates:exit0. Only the new MAX_QUIC_SNI_SPLIT_COUNT API declaration changed. Linux-only runtime-platform snapshot remains Linux-owned.
- Full cargo metadata --locked and rustfmt check:exit0.
- Both implementation commits ran normal pre-commit hooks successfully. Source hashes stayed unchanged by hooks.

## Full static analysis blocker

The canonical staticAnalysis aggregate was attempted without task skips, repository changes or version changes. Command-scoped JVM IPv4 preference addressed an earlier route failure. A missing pinned NaiveProxy input was then staged from an identical hash-verified main-checkout artifact and rechecked by its owning fetch script.

The last staticAnalysis retry stopped on :quality:detekt-rules:testRuntimeClasspath while fetching org.jetbrains.kotlin:kotlin-main-kts:2.0.21 from canonical Maven Central:No route to host, with126 failures of the same cause. Exit1. No failing source class or method was observed. The full aggregate is not passed.

The exact retry command, source hashes and failure output are retained under the local evidence directory's gradle/static-results.json and gradle/static-retry.log. Re-run that canonical gate when the host can reach Maven Central. Archival and task closure remain blocked until this acceptance gap is resolved.

## Evidence retention

An earlier raw-log directory in the user cache became unavailable after the runs were observed. The local evidence directory contains an explicit observation ledger, not reconstructed stdout, plus the retained model XML, reviewed patches and new command logs. Old-base643 Rust/82 Kotlin results are historical only; they are not the rebased acceptance numbers above.

No physical device, external interoperability, root activation, release or deployment acceptance was run. Single-datagram and ECH Outer scope remains explicit.
