---
task_id: TRN-1794199027304873
change: std04-hybrid-tls-audit-fixes
commit_sha: 92b149a2f7d1a0d97d1d485513e2d0628be8d900
local: passed
local_evidence: 573 Rust tests passed with 8 existing external interop tests ignored; 3 Kotlin tests, 4 REALITY vector tests, CI source gates, architecture checks, locked metadata, harness checks, commit hooks, and independent review passed.
remote_ci: not_applicable
remote_ci_evidence: Hosted workflow execution is not an acceptance requirement for this local fix. Main push is authorized separately by the user.
device: not_applicable
device_evidence: This fix uses host protocol tests. No device activation or Android system lifecycle change is owned, and Android execution is not claimed.
artifact: not_applicable
artifact_evidence: No packaged APK or release artifact is produced by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TRN-1794199027304873-001 | TRN-1794199173301062 | Five pq_kem_alerts tests and the existing fallback test passed, including invalid hybrid lengths, zero hybrid/classical X25519, unoffered group, and malformed vector framing. | Passed |
| REQ-TRN-1794199027304873-002 | TRN-1794199174324327 | All 68 profile unit tests passed, including captured Firefox/Safari 517-byte records and hybrid Chrome; 3 NativeOwnedTlsHttpFetcherTest cases passed. | Passed |
| REQ-TRN-1794199027304873-003 | TRN-1794199175321110 | Independent read-only review matched the corrected API/module documentation to the mandatory REALITY prefix and the separate MASQUE backends. The VLESS suite passed. | Passed |
| REQ-TRN-1794199027304873-004 | TRN-1794199177072195 | Independent provenance review matched IANA group assignments. Existing packet tests and drift checks passed; capture bytes are unchanged. | Passed |

## Observed checks

Validation used the exact source commit above after rebasing onto origin/main at cd08ced3125a7f730f5c363b6876d3c1ac98ae7d. All compiler-backed commands used the machine build gate, isolated outputs, at most four jobs, and lean host debug settings. No guard, fixture, API snapshot, or architecture baseline was changed.

| Command | Observed result |
|---|---|
| cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-tls-profiles --locked --jobs 4 --test pq_kem_alerts --test pq_kem_fallback | 5 new wire tests and 1 fallback test passed before the fix commits. |
| cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-tls-profiles -p ripdpi-vless -p ripdpi-packets -p ripdpi-xhttp --locked --jobs 4 | 573 passed, 0 failed, 8 existing external interop tests ignored. |
| python3 -m unittest scripts.tests.test_reality_boring_vector | 4 passed. |
| just ci-gates | Passed, including unchanged public API snapshots. The Linux-owned runtime-platform snapshot uses the gate's documented Darwin exclusion. |
| python3 scripts/ci/check_architecture_health.py | Passed: no new or worsened indicators. |
| cargo metadata --manifest-path native/rust/Cargo.toml --format-version 1 --locked | Passed: 112 workspace members, 938 resolved packages. |
| just harness-check | Passed after both stale skill sections were corrected. |
| ./gradlew :core:engine:testDebugUnitTest --tests com.poyka.ripdpi.core.NativeOwnedTlsHttpFetcherTest --max-workers=4 -Pripdpi.skipNativeBuild=true -Pripdpi.nativeCpuBudget=4 | BUILD SUCCESSFUL; 3 tests, 0 failures, 0 errors, 0 skipped. |
| Commit hooks | Formatting, affected-crate Clippy, task contracts, harness links, and native architecture contracts passed. |

## Review and evidence limits

Independent read-only review found no unresolved issue in F1-F4. It first found two stale skill claims for F2; both were corrected and re-reviewed. No application wire-shape change or golden update was needed.

F2 failed on the old metadata status before the fix. F1's failure was already observed by the independent wire probe at the unchanged starting SHA 7b475fb9e1b433c80daee34db6cd2ec6b77f23a3. Duplicate queued worker commands were cancelled before compilation; they are not reported as test results. The permanent F1 regression and fallback checks then passed in the combined tree.

The eight ignored tests require owner-provided live REALITY profiles or a pinned external peer. They were already marked ignored and are not acceptance evidence for this change. No physical device, external relay, live transport credential, packaged Android artifact, hosted CI run, or deployment is claimed. The parent compiler queue obtained the reserved RIPDPI slot; the later authorization for a free common slot was not needed for this completed batch.
