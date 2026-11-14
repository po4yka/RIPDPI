---
task_id: ENG-1794637887235609
change: dpi20-remediation
commit_sha: 05f91ba333e12df29460d271c2b86f57e6ac3164
local: passed
local_evidence: Six Rust crates passed 1589 tests; explicit wire target passed 9/9; strict Clippy, 94 model tests, model Detekt/lint, ci-gates, architecture, locked metadata, and task contracts passed. Independent review found no actionable defects.
remote_ci: not_applicable
remote_ci_evidence: Local acceptance is the gate for this correction. Normal direct main push was accepted by server bypass privileges; hosted CI and CodeQL were not awaited or claimed as passed.
device: not_applicable
device_evidence: No physical device, root activation, or external network acceptance was authorized for these local regressions.
artifact: not_applicable
artifact_evidence: No release artifact or golden fixture update was requested.
deployment: not_applicable
deployment_evidence: No deployment is part of this correction.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DPI20-LIMIT | ENG-1794638035480946 | Kotlin StrategyChainValidationTest; config/proxy-config quic_fake_version_count targets; desync quic_fake_version_budget target; Passed: 0/1/64 and 32+32 accepted;65/MAX and aggregate65 rejected; direct planner limited to64;65535/65536 input boundaries preserve original | Passed |
| REQ-DPI20-VERSION | ENG-1794638038580454 | Full JSON conversion quic_fake_version_config target; actual SOCKS5 UDP wire test ui_quic_fake_version_preserves_custom_and_default_versions_on_wire; Passed for v1/v2 and default/custom versions: two identical decoys with expected four raw version bytes, then unchanged original | Passed |
| REQ-DPI20-RESPONSE | ENG-1794638041569104 | dpi20_late_vn_does_not_count_as_real_response; actual-loopback quic_decoy_response_does_not_complete_the_real_flow, extended to QuicFakeVersion; Red regression failed before fix; full suite passed after fix. Real replies pass, VN is transparent without success/activity progress, target reset restores generic behavior | Passed |
| REQ-DPI20-LIMIT | ENG-1794638042434161 | Separate read-only pr-reviewer pass after final source rebase; combined gates below; Passed; no dependency, schema, API snapshot, golden, or baseline change | Passed |

## Observed commands

Cargo commands ran from native/rust with Rust 1.100.0, --locked, jobs 4, an isolated absolute CARGO_TARGET_DIR, and build-gate lane ripdpi. CMAKE_BUILD_PARALLEL_LEVEL=4 and CARGO_INCREMENTAL=0 were set.

- `cargo test -p ripdpi-packets -p ripdpi-config -p ripdpi-desync -p ripdpi-proxy-config -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-proxy-runtime --locked --jobs 4`: 1589 passed, 0 failed, 13 ignored across 51 result blocks.
- `RIPDPI_RUN_WIRE_E2E=1 cargo test -p ripdpi-proxy-runtime --locked --jobs 4 --test wire_e2e -- --nocapture`: 9 passed, 0 failed/ignored, 3.42 s; no internal skipping return.
- `cargo clippy -p ripdpi-packets -p ripdpi-config -p ripdpi-desync -p ripdpi-proxy-config -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-proxy-runtime --locked --jobs 4 --all-targets --no-deps -- -D warnings`:passed.
- `env -u CARGO_BUILD_JOBS ./gradlew :core:data:model:testDebugUnitTest :core:data:model:detekt :core:data:model:lintDebug --max-workers=4 -Pripdpi.nativeCpuBudget=4`:BUILD SUCCESSFUL, 1m03s, 56 tasks; 94 model tests, 0 failures/errors/skipped.
- `just ci-gates`:passed, including Rust API snapshots. The Linux-owned runtime-platform snapshot was skipped on Darwin by the gate.
- `python3 scripts/ci/check_architecture_health.py`:passed; 0 stale entries, 112 crates, 354 internal edges.
- `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1 --no-deps`:passed.
- `./taskctl validate`:passed before and after the final documents-only rebase.

## Source identity and integration

The final full local package run used 84800ec2cb on base 8ae43fe025. The later rebase changed only docs and OpenSpec records. Git tree IDs for native/rust, core, app, gradle, and build-logic remained identical. The three fixes were integrated with a fast-forward and normal push: 71a1e1f6b2, 81bae96fb6, 05f91ba333. Remote main matched 05f91ba333 after that push.

The earlier full `:core:data:model:testDebugUnitTest staticAnalysis` run passed on base 451fd66fc8 plus the DPI-20 changes: 90 model tests, 840 tasks, 19m54s. That result is historical; it is not a full staticAnalysis result for the later source base. The current base received the owning model tests, Detekt/lint and all required source gates listed above.

The first commit attempt failed with OS error 28 (disk full). Normal hooks passed after space was available. No hook, quality gate, baseline, or protection setting was disabled.

## Evidence limits

These checks prove C and V-local behavior. The audit separately records S for the checked RFC rules and independent cryptographic oracle. Physical Android/VPN operation, root activation, Linux AF_PACKET/TTL capture, an external QUIC endpoint, and hosted CI/CodeQL are not proved by this correction. E was not increased. Existing thirteen ignored tests remain ignored.
