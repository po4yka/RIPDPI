---
task_id: DGN-1786264762917717
change: dgn-1786264762917717-report-owned-stack-only-verdict-from-diagnostic
commit_sha: null
local: required
local_evidence: "2026-08-30: cargo fmt --manifest-path native/rust/Cargo.toml --all -- --check; build-gate -- cargo test --manifest-path native/rust/Cargo.toml --locked -p ripdpi-runtime-adaptive -p ripdpi-runtime-policy -p ripdpi-runtime-decision-ports -p ripdpi-runtime-services -p ripdpi-proxy-runtime -p ripdpi-proxy-runtime-adapter; build-gate -- cargo clippy --manifest-path native/rust/Cargo.toml --workspace --all-targets --all-features --locked -- -D warnings; build-gate -- ./gradlew :core:data:testDebugUnitTest --tests 'com.poyka.ripdpi.data.NativeRuntimeSnapshotTest'. Full build-gate -- env -u CARGO_BUILD_JOBS ./gradlew staticAnalysis is locally blocked because native/xray/artifacts/libxray.aar is absent and scripts/native/build-libxray.sh --check-toolchain reports missing gomobile."
remote_ci: required
remote_ci_evidence: null
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
| REQ-DGN-1786264762917717-001 | DGN-1786264762919430 | Existing completed step retained; runtime policy now preserves `OWNED_STACK_REQUIRED` as a known direct-path event for the diagnostic result. | passed |
| REQ-DGN-1786264762917717-002 | DGN-1786264762919079 | Existing completed step retained; no UI behavior changed in this implementation slice. | passed |
| REQ-DGN-1786264762917717-003 | DGN-1786264762919319 | Existing completed step retained; runtime admission consumes the persisted `OWNED_STACK_ONLY` direct-path capability outcome. | passed |
| REQ-DGN-1786264762917717-004 | DGN-1786264762919187 | `ripdpi-proxy-runtime` tests verify transparent hostname-attributed TCP rejection before relay/WS, SOCKS5 `REP=0x02`, HTTP `403` with `X-RIPDPI-Reason: OWNED_STACK_REQUIRED`, runtime telemetry event emission, hostless preservation, and IP-set scope preservation. | passed |

## Local Gate Notes

- Full `staticAnalysis` was attempted with `build-gate -- ./gradlew staticAnalysis` and with the repository-supported `build-gate -- env -u CARGO_BUILD_JOBS ./gradlew staticAnalysis`; both were blocked before completion by the missing gitignored `native/xray/artifacts/libxray.aar`.
- `scripts/native/build-libxray.sh --check-toolchain` cannot prepare that artifact on this machine because `gomobile` is unavailable.
- `build-gate -- env -u CARGO_BUILD_JOBS ./gradlew staticAnalysis -x :core:engine:verifyLibXrayArtifacts` also cannot complete because downstream Android classpath tasks still require the same missing AAR.
