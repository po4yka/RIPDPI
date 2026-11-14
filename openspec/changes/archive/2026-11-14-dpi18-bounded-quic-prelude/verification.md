---
task_id: ENG-1794637894202698
change: dpi18-bounded-quic-prelude
commit_sha: dae08cbf033b89081f9fc5dcee52f1a2d4f1351d
local: passed
local_evidence: "Fresh rebase2: 1021 Rust tests and 92 Kotlin model tests passed with zero skipped. Strict Clippy, full staticAnalysis --offline, just ci-gates and locked metadata passed. Tracked inputs stayed unchanged."
remote_ci: not_applicable
remote_ci_evidence: Remote runs after push are separate from the observed local acceptance.
device: not_applicable
device_evidence: No Android API or service lifecycle change; validation uses host and real loopback tests.
artifact: passed
artifact_evidence: "API snapshots match. Independent rebased code review found no actionable issue. Exact commands, logs, source hashes and model XML are in Downloads/RIPDPI_network_audits/DPI-18-fix-evidence-20261114."
deployment: not_applicable
deployment_evidence: No release or deployment is owned by this change.
---

# Verification

Portfolio task: ENG-1794637894202698

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DPI18-BUDGET | ENG-1794638194964777 | Full Kotlin model: 92 passed; five-crate Rust suites: 1021 passed | Passed |
| REQ-DPI18-SIZE | ENG-1794638196470855 | Full Rust suites include padded/unpadded v1/v2 boundary cases | Passed |
| REQ-DPI18-SEND | ENG-1794638196470855 | Real IPv4/IPv6 loopback, original once, TTL and error cases; upstream pending-ICMP test retained | Passed |
| REQ-DPI18-SCOPE | ENG-1794638197694196 | Strict Clippy, full staticAnalysis, just ci-gates, locked metadata and independent rebased review | Passed |

## Fresh commands and results

All fresh results below use source at dae08cbf033b89081f9fc5dcee52f1a2d4f1351d, rebased on origin/main 4502477f217a0b866a518a594467f51329e59b1f. F1 is commit 3a6762d9f1; F2 is commit 3c2f863018. The shared emitter recovery now comes from upstream; the rebase keeps its single PreludeWriteFailed path.

- Full nextest suites for ripdpi-packets, ripdpi-config, ripdpi-proxy-config, ripdpi-desync and ripdpi-proxy-runtime-desync-adapter, with --locked and four jobs: 1021 passed, 0 skipped.
- Strict Clippy for those five crates, --all-targets -- -D warnings: exit 0.
- Doctests for those crates: 0 cases, exit 0; no doctest behavior claim.
- :core:data:model:testDebugUnitTest --offline: 92 tests, 0 failures/errors/skipped, 19 XML suites.
- Full staticAnalysis --offline: exit 0, BUILD SUCCESSFUL in 6m 37s; 824 actionable tasks, 144 executed, 40 from cache, 640 up-to-date. Both app variants completed lint.
- just ci-gates: exit 0, including API snapshots. The Linux-only runtime-platform snapshot remains Linux-owned.
- Full cargo metadata --locked, rustfmt, task contracts and diff checks: exit 0.
- Independent pr-reviewer found no actionable CRITICAL/WARNING issue in the rebased source.

Heavy checks used build-gate lane ripdpi, at most four workers/jobs, and CMAKE_BUILD_PARALLEL_LEVEL=4. Gradle retained JVM 5 GiB/Kotlin 3 GiB limits and command-scoped IPv4 preference. No repository, version, cache or task overrides were used. Exact commands and input hashes are retained in rebase2-rust/results.json, rebase2-gradle/results.json and rebase2-static-space-retry/results.json under the local evidence directory.

## Resolved environment failures

An earlier online staticAnalysis attempt could not fetch kotlin-main-kts:2.0.21 from Maven Central. A later concurrent canonical build populated the dependency cache, so one offline attempt was justified. That attempt reached helper JNI staging but failed with No space left on device. Root removed only this job's disposable host Cargo build outputs, about 1.2 GiB. Other concurrent activity had also freed space; no claim is made that this cleanup freed all available capacity. With 42,500,198,400 bytes free before the final attempt, the same full offline gate passed. Source inputs stayed unchanged. The earlier failures remain in their original logs.

## Evidence retention and scope

The local evidence directory is Downloads/RIPDPI_network_audits/DPI-18-fix-evidence-20261114. Fresh logs, XML and before/after hashes are retained. An earlier raw-log cache directory became unavailable; its observation ledger is not reconstructed stdout. Old-base 643/82 and first-rebase 974/85 results are historical only.

Single-datagram and ECH Outer scope remains explicit. No physical device, external interoperability, root activation, release or deployment acceptance was run. These local checks do not establish connection-level or external DPI effectiveness.
