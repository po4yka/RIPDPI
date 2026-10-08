---
task_id: DGN-1791481962888112
change: require-http-blockpage-evidence
commit_sha: null
local: passed
local_evidence: All 53 HTTP crate tests passed. Empty and generic forbidden responses remain HTTP403; specific blockpage evidence remains positive. Independent review passed. Full staticAnalysis, workspace Clippy, architecture checks, and locked Cargo metadata passed. Rebased combined-tree verification is required before integration.
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: Pure classification is checked with deterministic response tests.
artifact: not_applicable
artifact_evidence: No packaged artifact is produced in this scope.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-HTTP-EVIDENCE-001 | DGN-1791482026197153 | Tests and implementation prepared. Baseline RED, GREEN, Clippy, and review remain pending. | passed |

## Combined checks and limits

- Kotlin diagnostics module: 1487 tests passed. Home measurement tests and detection module tests passed.
- Native classification, HTTP, and runner crates: 198 tests passed; two existing ignored tests were also run explicitly. The DNS fixture passed. The HTTP trigger-fuzzing fixture failed on both this change and original commit 94c91477c with the same host_header_format assertion.
- Full staticAnalysis passed. Workspace Clippy with all targets and warnings denied passed in the commit hook. Architecture and native-contract checks passed; no baselines were changed.
- Initial builds stopped for low disk space. Task-owned Gradle outputs were cleaned with Gradle. Shared Rust cache was not deleted.
- Pre-fix failing tests were observed for DNS conclusions and offline VPN inference. Other pre-fix runs were blocked by disk space; final regression tests passed.
- JVM commands use ripdpi.skipNativeBuild=true and offline cached dependencies. These checks do not prove Android packaging or live provider behavior.
- Remote CI is required and remains separate from local checks.

Commands: `./gradlew :core:diagnostics:testDebugUnitTest -Pripdpi.skipNativeBuild=true --max-workers=2 --console=plain --no-daemon --offline`; `./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true --max-workers=2 --console=plain --no-daemon --offline`; from native/rust, `CARGO_INCREMENTAL=0 cargo test --locked --jobs 2 -p ripdpi-diagnostics-classification -p ripdpi-diagnostics-http -p ripdpi-diagnostics-runner`.
