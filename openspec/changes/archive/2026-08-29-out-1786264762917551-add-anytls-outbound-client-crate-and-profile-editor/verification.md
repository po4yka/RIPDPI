---
task_id: OUT-1786264762917551
change: out-1786264762917551-add-anytls-outbound-client-crate-and-profile-editor
commit_sha: bacc106a665f311b4e0f0708f4bf91a7ae40b6ca
local: passed
local_evidence: "Combined targeted Rust nextest passed 263 tests with zero failures (4 skipped) across ripdpi-anytls, ripdpi-relay-core, ripdpi-runtime-platform and ripdpi-cli. Targeted AnyTLS Mode Editor, persistence and URI/import Kotlin tests passed in a 296-task Gradle build."
remote_ci: passed
remote_ci_evidence: "Exact-SHA CI run 33251657196 passed all 45 jobs on bacc106a665f311b4e0f0708f4bf91a7ae40b6ca, including relay interoperability, Android tests, native checks, static analysis and release verification."
device: not_applicable
device_evidence: No Android device behavior is owned by this portfolio area.
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this portfolio area.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-OUT-1786264762917551-001 | OUT-1786264762917091 | Frame, padding, TLS-session and AnyTLS client tests passed in `ripdpi-anytls`. | passed |
| REQ-OUT-1786264762917551-002 | OUT-1786264762917103 | Relay-core TCP and UDP-over-TCP AnyTLS fixtures passed in the combined 263-test run. | passed |
| REQ-OUT-1786264762917551-003 | OUT-1786264762917403 | Targeted URI, Clash, Sing-box and subscription parser tests passed. | passed |
| REQ-OUT-1786264762917551-004 | OUT-1786264762917988 | Native configuration conversion tests passed with password and root-certificate fields. | passed |
| REQ-OUT-1786264762917551-005 | OUT-1786264762917903 | Pinned `anytls-go` interop covers 64 KiB TCP, sibling close, UDP sizes 0/1/1200/8192 and wrong-password rejection. | passed |
| REQ-OUT-1786264762917551-006 | OUT-1786264762917396 | URI tests confirm unsupported fallback and fallback-SNI nodes fail explicitly. | passed |
| REQ-OUT-1786264762917551-007 | OUT-1786264762917776 | AnyTLS profile editor validation tests cover password, endpoint, port and SNI. | passed |
| REQ-OUT-1786264762917551-008 | OUT-1786264762917917 | Mode Editor tests confirm masked password, SNI, UDP controls and identity-safe persistence. | passed |
| REQ-OUT-1786264762917551-009 | OUT-1786264762917429 | Strategy catalog tests load the AnyTLS QUIC-heavy-neighborhood compatibility hint. | passed |
| REQ-OUT-1786264762917551-010 | OUT-1786264762917479 | Rust `Debug` and Kotlin profile-string tests confirm password and root-certificate redaction. | passed |

## Current verification

- `build-gate -- cargo nextest run --manifest-path native/rust/Cargo.toml --locked -p ripdpi-anytls -p ripdpi-relay-core -p ripdpi-runtime-platform -p ripdpi-cli`: 263 passed, 4 skipped, 0 failed.
- Targeted `:app:testGithubFullDebugUnitTest` and `:core:data:testDebugUnitTest` AnyTLS editor, persistence, URI and import tests: BUILD SUCCESSFUL, 296 tasks.
- [CI 33251657196](https://github.com/po4yka/RIPDPI/actions/runs/33251657196) passed all 45 jobs on the exact recorded SHA.
