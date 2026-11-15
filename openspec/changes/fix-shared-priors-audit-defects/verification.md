---
task_id: SVC-1794755208139697
change: fix-shared-priors-audit-defects
commit_sha: bcdf0098961a1b7808ce2ac1635fa629cffa6b62
local: required
local_evidence: HTTP 8 tests, provisioning 4 tests, Rust 143 tests and all-target Clippy passed. Source gates and full staticAnalysis passed before rebase; affected checks are being repeated on the rebased tree.
remote_ci: not_applicable
remote_ci_evidence: Local fixes and push are requested; hosted CI is reported separately.
device: not_applicable
device_evidence: These defects have local HTTP, build configuration and arithmetic oracles.
artifact: passed
artifact_evidence: Actual AGP BuildConfig contains both -P example.invalid URLs and PUBLIC_KEY_CONFIGURED=true. This is not APK or production-key evidence.
deployment: not_applicable
deployment_evidence: No deployment or release activation is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SP-BODY | SVC-1794755266053394 | 8 HTTP tests including 12 decoded-byte loopback combinations and cleanup checks | passed |
| REQ-SP-CONFIG | SVC-1794755268365064 | 4 build-logic tests; actual AGP BuildConfig contains both supplied URL properties and key flag | passed |
| REQ-SP-MEAN | SVC-1794755271283928 | 135 unit and 8 integration tests; all-target Clippy with -D warnings | passed |

## Observed checks

- HTTP: `:core:service:testDebugUnitTest --tests com.poyka.ripdpi.services.SharedPriorsCatalogNetworkTest -Pripdpi.skipNativeBuild=true`, 8 tests passed. The real loopback matrix covers manifest/payload, known length/chunked/gzip, and HTTP 200/503.
- Provisioning: `./gradlew -p build-logic :convention:test --tests com.poyka.ripdpi.buildlogic.SharedPriorsBuildPropertyTest --tests com.poyka.ripdpi.buildlogic.SharedPriorsKeyTest`, 4 tests passed. Six real TestKit builds exercise source precedence and blank fallback.
- Artifact: `:core:service:generateDebugBuildConfig` with a test public key and both `-PsharedPriors` URLs produced the requested constants. No release key, APK, signing credential or channel activation is owned by this change.
- Rust: `cargo test -p ripdpi-runtime-strategy --locked --jobs 4`, 135 unit and 8 integration tests passed. `cargo clippy -p ripdpi-runtime-strategy --all-targets --locked --jobs 4 -- -D warnings` passed.
- Source gates: `just ci-gates` passed. Full `staticAnalysis` passed (828 tasks). Both gates are being repeated after rebase.
- Independent read-only pr-reviewer found no actionable issues. Structured autoreview passed with no accepted findings. Normal commit hooks passed for each fix.
- Worker inputs and the combined implementation were checked byte-for-byte. Rebase changed no owned implementation file or Rust strategy dependency.

## Evidence limits

No emulator, physical device, external publisher, release-key activation or concrete released APK was tested. These are not required to reproduce or fix the three scoped defects. The original release-provenance uncertainty remains. Existing Xray receipt files were copied to ignored worktree outputs; its artifact verifier stayed enabled.
