---
task_id: ENG-1794637889757963
change: fix-dpi17-audit-findings
commit_sha: 1cf170c07a14a0d9a3ee6183dbcb53e3e64f5f27
local: blocked
local_evidence: Native suites with 389 tests, enabled v1/v2 wire regression, targeted Clippy, standalone ktlint, locale parity/export and isolated just ci-gates passed. Gradle JVM tests and Android lint could not run because required dependency artifacts are unavailable.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI is not an acceptance gate owned by this local repair; no hosted result is claimed.
device: not_applicable
device_evidence: No device or root behavior changes; no physical acceptance requested.
artifact: not_applicable
artifact_evidence: No APK or release artifact requested.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DUMMY-COUNT | ENG-1794638159294108 | Full desync and proxy-config suites, boundary and direct-model tests | Native passed; Kotlin JVM gate blocked |
| REQ-DUMMY-BYTES | ENG-1794638159294108 | v1/v2 direct counts and oversized host/output tests in desync suite | Passed |
| REQ-DUMMY-COMPAT | ENG-1794638159294108 | Full owning native suites and production loopback original byte equality | Passed locally; no device claim |
| REQ-DUMMY-UI | ENG-1794638174316919 | Ten locales, export/parity and standalone Kotlin lint | Source checks passed; Android lint blocked |
| REQ-DUMMY-WIRE | ENG-1794638179707591 | Enabled production loopback regression for v1/v2 | Passed |

## Observed local checks

Run Cargo from native/rust with pinned Rust 1.100.0 and --locked.

- cargo test -p ripdpi-desync -p ripdpi-proxy-config --locked: 389 passed, zero failed, ignored or filtered. Log: /tmp/dpi17-combined-native-tests-final.log.
- RIPDPI_RUN_WIRE_E2E=1 cargo test -p ripdpi-proxy-runtime --locked --test wire_e2e udp_quic_chain_steps_emit_dummy_split_and_fake_version_packets -- --exact --nocapture: one test covering both versions passed. Seven unrelated tests filtered. Log: /tmp/dpi17-combined-native-wire-final.log.
- cargo clippy -p ripdpi-config -p ripdpi-desync -p ripdpi-proxy-config -p ripdpi-proxy-runtime --locked --all-targets --no-deps -- -D warnings: passed. Log: /tmp/dpi17-combined-native-clippy-final.log.
- CARGO_TARGET_DIR=/tmp/dpi17-api-target just ci-gates: passed, including the API snapshot gate. It listed 13 targets and skipped the Linux-owned runtime-platform snapshot on macOS. Log: /tmp/dpi17-ci-gates-isolated.log.
- The earlier shared-target API run reported a missing config constant in dependent crates despite a correct config snapshot. The isolated run passed without another source change. No cache root cause beyond these observations is asserted.
- Standalone ktlint 1.8.0 checked both changed Kotlin files after formatting: passed. All ten locale sets and the unchanged translation-key export passed their owning source checks.
- Independent read-only review found no remaining actionable defect. Pre-commit and commit-message hooks passed for all three fix commits.

## Unresolved validation

The exact combined Gradle command was ./gradlew :core:data:model:testDebugUnitTest :app:lintGithubFullDebug :core:service:lintDebug --offline -Pripdpi.skipNativeBuild=true. It failed before test/lint execution because protobuf Gradle plugin 0.10.1 was not cached. Earlier online lint blocked on Maven/Plugin Portal connections; bounded official endpoint attempts timed out. Log: /tmp/dpi17-combined-android-offline.log.

A bounded actual-source kotlinc 2.4.21 fallback also failed before JUnit execution: generated protobuf code required a newer runtime than cached protobuf-java 3.25.5, and the required IPAddress artifact was absent. No stubs, source stripping, dependency changes or weakened checks were used. Commands and logs: /tmp/ripdpi-dpi17-kotlin-validation/README.md. This fallback does not replace the Gradle gate.

Rerun the exact Gradle command without --offline once official dependency access is restored. Device, root, external QUIC peer, DPI efficacy and visual acceptance are not claimed. This record remains blocked and is not archive-ready.

## Delivered fixes

- e87f2e02241c4097647bf02b7103a34b148ede4a: bound resource use.
- 147c98b5b87dda276d000019458a722538113239: correct all locale descriptions.
- 1cf170c07a14a0d9a3ee6183dbcb53e3e64f5f27: repair wire regression.
