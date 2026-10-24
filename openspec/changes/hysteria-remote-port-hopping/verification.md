---
task_id: OUT-1792820412611017
change: hysteria-remote-port-hopping
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: Controlled native peer and protection-contract tests are in scope; physical-device acceptance is not authorized or claimed.
artifact: required
artifact_evidence: null
deployment: not_applicable
deployment_evidence: No live deployment or provider mutation is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-HYH-IMPORT | OUT-1792820587825821 | URI, sing-box, Clash, bundle, duration and persistence tests | Required |
| REQ-HYH-WIRE | OUT-1792820588684825 | Direct/chain Kotlin JSON and Rust validation tests | Required |
| REQ-HYH-RUNTIME | OUT-1792820589483909 | Scheduled remote/local rotation, previous-path delivery and Salamander tests | Required |
| REQ-HYH-LIFECYCLE | OUT-1792820589483909 | Protection failure, stop, cancellation and migration tests | Required |
| REQ-HYH-PEER | OUT-1792820590309126 | Pinned upstream TCP/UDP matrix, forwarder receipts, combined gates and Android artifact | Required |

## Planned local gates

- `build-gate -- cargo test --locked -p ripdpi-hysteria2 -p ripdpi-relay-core --jobs 4` from native/rust.
- Affected runtime-state/data/service/engine-api Gradle tests under `build-gate --`, at most four workers; select actual existing module tasks and record exact commands before claiming results.
- Controlled peer scenarios through `test-lab/acceptance/peers/run.py` under the build gate. Preserve existing peer pin and require real remote-port and destination receipts.
- `build-gate -- ./gradlew staticAnalysis --max-workers=4`, `python3 scripts/ci/check_architecture_health.py`, `cargo metadata --manifest-path native/rust/Cargo.toml --locked`, and `just task-check` on the combined tree.
- Android debug artifact/native build under the build gate; inspect packaged native output. No release signing or physical-device acceptance claim.

## Current evidence

Native source implementation is present; task steps remain open until the combined client and peer gates pass. The first native regression run failed on the multi-port authority; a later regression failed on conflicting fixed/random intervals before the parser was replaced. These are red-phase observations only.

Observed local checks on 2026-10-24:

- `python3 -m unittest discover -s test-lab/acceptance/peers -p test_peers.py -q`: 22 tests passed. These verify the harness, not protocol acceptance.
- `python3 -m unittest discover -s test-lab/acceptance/tests -q`: 38 tests passed.
- Manifest generation and check passed; `lab.py validate` reports 50 scenarios and seven profiles.
- Architecture health: zero new, worsened or stale indicators. Native architecture contracts: zero violations.
- `actionlint .github/workflows/local-acceptance.yml`, Cargo locked-command check, task validation and strict OpenSpec validation passed.
- Independent source review found a missing GRO receive capability. The adapter now preserves the initial receive capacity and tests plain/Salamander capabilities across rotations. The reviewer found no remaining source-level lock or lifetime defect in this slice; compilation and Linux execution remain required.
- The pinned Hysteria app/v2.9.0 binary was prepared and its existing SHA-256 digest verified. No peer acceptance run has completed.
- The native library test and first commit-hook Clippy attempt were cancelled while queued, before compilation, to leave one compiler command in the machine build gate. The next normal hook attempt reached compilation and failed with Clippy exit 101 on an owned manual-let-else lint; the source was corrected. Normal hooks are being retried. No native compile/test success, Kotlin gate, Linux hosted result or Android artifact is claimed.

The PR hopping job requires all eight independent peer cases and exact execution of the Linux GRO capability test. It publishes metadata only. No production or private-data action occurred.
