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

- `python3 -m unittest discover -s test-lab/acceptance/peers -p test_peers.py -q`: 25 tests passed. These verify the harness, not protocol acceptance.
- `python3 -m unittest discover -s test-lab/acceptance/tests -q`: 38 tests passed.
- Manifest generation and check passed; `lab.py validate` reports 50 scenarios and seven profiles.
- Architecture health: zero new, worsened or stale indicators. Native architecture contracts: zero violations.
- `actionlint .github/workflows/local-acceptance.yml`, Cargo locked-command check, task validation and strict OpenSpec validation passed.
- Independent source review found a missing GRO receive capability. The adapter now preserves the initial receive capacity and tests plain/Salamander capabilities across rotations. The reviewer found no remaining source-level lock or lifetime defect in this slice; compilation and Linux execution remain required.
- Native source commit `c99ccfe107a1a355465048bf7cf54dba419a7fbf`: nine targeted socket tests passed, including fairness and rebind races. Normal commit hooks and workspace Clippy passed.
- `cargo test --locked -p ripdpi-hysteria2 -p ripdpi-relay-core --jobs 4`: exit 0; Hysteria 105 unit and two loopback tests passed; relay 188 unit tests passed. Separately gated external-peer tests were not counted as acceptance in this command.
- The initial app/v2.9.0 hopping matrix failed before the first hop. A direct fixed-port control sent an 8208-byte UDP payload to the destination but got no reply; the peer has a 4096-byte reply buffer including its header. A separate 2736-byte direct control also failed: peer QUIC debug showed only a trailing reply fragment was sent. Disabling peer PMTU discovery did not fix it.
- The same direct 2736-byte control passed with app/v2.13.0 and default QUIC settings: complete TCP and UDP destination receipts matched, exit 0, cleanup passed. Upstream fix `7612ad1036957cde45d672bebb8b9ec9c2e9b2a7` corrects the QUIC size estimate. The test-peer pin now uses official release SHA-256 digests for all three supported platforms. This is not a native UDP fix.
- The full eight-case matrix and three shared peer auth/TLS/payload cases still require terminal results with the new pin. Kotlin combined-tree gates, Linux hosted results and the Android artifact are not claimed here. All task steps remain open until combined acceptance.


The PR hopping job requires all eight independent peer cases and exact execution of the Linux GRO capability test. It publishes metadata only. No production or private-data action occurred.
