---
task_id: RTE-1793945596239402
change: unify-ip-cidr-parsing
commit_sha: df1889cd96b73e4b375da13d63d461c99ae84ef7
local: passed
local_evidence: 9284 Kotlin tests in ten modules and staticAnalysis passed; 1594 Rust library tests in nine crates passed; final config/runtime repeat passed 467 tests; all-target Clippy with warnings denied, cargo deny, locked metadata, ci-gates, and independent review passed.
remote_ci: blocked
remote_ci_evidence: CI run 42179468873 and CodeQL run 42179468789 for df1889cd were cancelled by a later documentation push. Upstream run 42178215874 already failed rust-lint on the unchanged HTTP/1 shared-state gate; full hosted acceptance remains unverified.
device: not_applicable
device_evidence: Pure parsing and subnet operations; no socket or device lifecycle change.
artifact: not_applicable
artifact_evidence: No visual or release artifact is requested; APK size is not measured.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-IP-LITERAL | RTE-1793945795878584 | NumericIpTest and caller tests; ResolverMappingPolicyTest failed before migration and passed after it | Passed |
| REQ-IP-CIDR | RTE-1793945796749611 | Rust parser, cache, routing, and interface tests; malformed cache prefix regression failed before the fix | Passed |
| REQ-IP-WIRE | RTE-1793945797466029 | Canonical routing tests, ci-gates, API snapshots, and independent review | Passed |

## Local commands

- `build-gate -- ./gradlew :core:data:model:testDebugUnitTest :core:data:settings:testDebugUnitTest :core:data:testDebugUnitTest :core:engine:testDebugUnitTest :core:service:testDebugUnitTest :core:diagnostics:testDebugUnitTest :core:diagnostics-data:testDebugUnitTest :core:detection:testDebugUnitTest :app:testGithubFullDebugUnitTest staticAnalysis -Pripdpi.skipNativeBuild=true --max-workers=4`
- `build-gate -- cargo test --locked --lib -p ripdpi-packets -p ripdpi-config -p ripdpi-proxy-config -p ripdpi-proxy-runtime -p ripdpi-runtime-policy -p ripdpi-diagnostics-dns -p ripdpi-tunnel-config -p ripdpi-tunnel-core -p ripdpi-warp-core` from `native/rust`.
- `build-gate -- cargo clippy --manifest-path native/rust/Cargo.toml --locked -p ripdpi-packets -p ripdpi-config -p ripdpi-proxy-config -p ripdpi-proxy-runtime -p ripdpi-runtime-policy -p ripdpi-diagnostics-dns -p ripdpi-tunnel-config -p ripdpi-tunnel-core -p ripdpi-warp-core --all-targets -- -D warnings`
- `build-gate -- ./gradlew :core:data:runtime-state:testDebugUnitTest -Pripdpi.skipNativeBuild=true --max-workers=4` passed 385 tests.
- `cargo deny --locked --manifest-path native/rust/Cargo.toml check`
- `build-gate -- env CARGO_TARGET_DIR=/private/tmp/ripdpi-ip-cidr-api-target just ci-gates`

API snapshots used a separate target directory after the shared rustdoc cache returned stale output. The Linux-owned runtime-platform snapshot was not checked on macOS. No device run or APK-size measurement was performed.

## Integration and hosted acceptance

- Rebased on `88116d19ec5ed80aa5873adf192ec53ad11274d9`; kept the Protobuf dependencies and aligned the separate fuzz lockfile with the shared IP parsing dependencies.
- On the rebased tree, all 9284 Kotlin tests, `staticAnalysis`, 1594 Rust tests, all-target Clippy, dependency deny, native architecture contracts, task validation, and `ci-gates` passed. Rust tests and Clippy used the pinned 1.98.1 toolchain. Locked metadata passed for both Rust workspaces.
- Fast-forwarded `main` and pushed `df1889cd96b73e4b375da13d63d461c99ae84ef7`; remote readback confirmed that SHA.
- [CI](https://github.com/po4yka/RIPDPI/actions/runs/42179468873) and [CodeQL](https://github.com/po4yka/RIPDPI/actions/runs/42179468789) for that SHA were cancelled by the later documentation push `a7e9189756451bc43773b3feeeb7abf39e6a965d`. Harness checks, secret scan, monitor-engine dependency guard, and L7 dry-run passed. Fleet fixtures were still running at this check.
- [Upstream rust-lint failure](https://github.com/po4yka/RIPDPI/actions/runs/42178215874/job/127761932976): `check_locking_and_shared_state.py` rejects `Rc<RefCell<Input>>` in `ripdpi-diagnostics-http/src/http/response_decoder.rs` at lines 48 and 185. The IP/CIDR change does not modify this file. Do not archive this task until full hosted acceptance is verified.
