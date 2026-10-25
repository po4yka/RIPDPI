---
task_id: OUT-1792820412611017
change: hysteria-remote-port-hopping
commit_sha: e22c8d66d8fa0c9245eb5bf8ef802e24028d0f48
local: passed
local_evidence: Targeted Kotlin and native tests, full static analysis, Simple app tests, controlled peer acceptance and combined contract checks passed; exact source and counts are below.
remote_ci: passed
remote_ci_evidence: PR head b8b2eca0294973df68f52ecddcafcef6ccc62c8a passed its required checks and Linux hopping job. Exact merged main e22c8d66d8fa0c9245eb5bf8ef802e24028d0f48 passed run 40085973754 on its first attempt, with 48 successful jobs and 16 routed skips.
device: not_applicable
device_evidence: Controlled native peer and protection-contract tests are in scope; physical-device acceptance is not authorized or claimed.
artifact: passed
artifact_evidence: PR run 40082693780 built all four Android ABIs; artifact 12556472475 passed the canonical verifier for six APKs and sealed source e87c9428636e72d641d6940afd3269efa759b27a.
deployment: not_applicable
deployment_evidence: No live deployment or provider mutation is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-HYH-IMPORT | OUT-1792820587825821 | URI, sing-box, Clash, bundle, duration and persistence tests | Passed |
| REQ-HYH-WIRE | OUT-1792820588684825 | Direct/chain Kotlin JSON and Rust validation tests | Passed |
| REQ-HYH-RUNTIME | OUT-1792820589483909 | Scheduled remote/local rotation, previous-path delivery and Salamander tests | Passed |
| REQ-HYH-LIFECYCLE | OUT-1792820589483909 | Protection failure, stop, cancellation and migration tests | Passed |
| REQ-HYH-PEER | OUT-1792820590309126 | Pinned upstream TCP/UDP matrix, forwarder receipts, combined gates and Android artifact | Passed |

## Planned local gates

- `build-gate -- cargo test --locked -p ripdpi-hysteria2 -p ripdpi-relay-core --jobs 4` from native/rust.
- Affected runtime-state/data/service/engine-api Gradle tests under `build-gate --`, at most four workers; select actual existing module tasks and record exact commands before claiming results.
- Controlled peer scenarios through `test-lab/acceptance/peers/run.py` under the build gate. Preserve existing peer pin and require real remote-port and destination receipts.
- `build-gate -- ./gradlew staticAnalysis --max-workers=4`, `python3 scripts/ci/check_architecture_health.py`, `cargo metadata --manifest-path native/rust/Cargo.toml --locked`, and `just task-check` on the combined tree.
- Android debug artifact/native build under the build gate; inspect packaged native output. No release signing or physical-device acceptance claim.

## Current evidence

The following entries preserve the implementation and regression sequence. The first native regression run failed on the multi-port authority; a later regression failed on conflicting fixed/random intervals before the parser was replaced. These are red-phase observations only.

Observed local checks on 2026-10-24:

- `python3 -m unittest discover -s test-lab/acceptance/peers -p test_peers.py -q`: 25 tests passed. These verify the harness, not protocol acceptance.
- `python3 -m unittest discover -s test-lab/acceptance/tests -q`: 38 tests passed.
- Manifest generation and check passed; `lab.py validate` reports 50 scenarios and seven profiles.
- Architecture health: zero new, worsened or stale indicators. Native architecture contracts: zero violations.
- `actionlint .github/workflows/local-acceptance.yml`, Cargo locked-command check, task validation and strict OpenSpec validation passed.
- Independent source review found a missing GRO receive capability. The adapter now preserves the initial receive capacity and tests plain/Salamander capabilities across rotations. The reviewer found no remaining source-level lock or lifetime defect in this slice; native compilation passed below; Linux execution remains required.
- Native source commit `c99ccfe107a1a355465048bf7cf54dba419a7fbf`: nine targeted socket tests passed, including fairness and rebind races. Normal commit hooks and workspace Clippy passed.
- `cargo test --locked -p ripdpi-hysteria2 -p ripdpi-relay-core --jobs 4`: exit 0; Hysteria 105 unit and two loopback tests passed; relay 188 unit tests passed. Separately gated external-peer tests were not counted as acceptance in this command.
- The initial app/v2.9.0 hopping matrix failed before the first hop. A direct fixed-port control sent an 8208-byte UDP payload to the destination but got no reply; the peer has a 4096-byte reply buffer including its header. A separate 2736-byte direct control also failed: peer QUIC debug showed only a trailing reply fragment was sent. Disabling peer PMTU discovery did not fix it.
- The same direct 2736-byte control passed with app/v2.13.0 and default QUIC settings: complete TCP and UDP destination receipts matched, exit 0, cleanup passed. Upstream fix `7612ad1036957cde45d672bebb8b9ec9c2e9b2a7` corrects the QUIC size estimate. The test-peer pin now uses official release SHA-256 digests for all three supported platforms. This is not a native UDP fix.
- The full matrix passed on clean source `764d7519483e5879d3ae58c1b532723df16dbfbd`: eight IPv4/IPv6, plain/Salamander, fixed/random hopping cases and three shared peer TCP/UDP, auth rejection and TLS rejection cases. The command exited 0 at 08:07:57 UTC on 2026-10-24. The report is `build/acceptance/hysteria-hopping-native/matrix-213/report.json`, SHA-256 `7a81ef74bd5f3dffd16f0da2cb91a4a8f7f4c177c825795162f3eebeddc11561`.
- Each hopping case preserved one authenticated connection and one destination TCP connection across three physical remote/local port changes. Each case has 20 verified TCP receipts of 8208 bytes and 60 distinct verified UDP receipts of 2736 bytes. Each UDP packet requires Hysteria fragmentation. All 11 cases passed cleanup.
- The eight cases used actual Kotlin serializer fixtures: fixed SHA-256 `3b1046b483f784bc52dedcb59df3d01fad519edc5d655e30ccdf0bda96e1871d`; random SHA-256 `2a9b6f5fadbc2f2dde6bb0ce10302031bbd1086786055c9a5043b8d548c07444`. Fixtures were produced at `463dccaa1ccd8f7f3fb9195e0ab4f46effc71660`; the later test-class split at `7e34170d6eeeadb33ae746c6c8b3a9dcba733ca5` did not regenerate them. Per-case receipts retain the input hash. Only ephemeral peer connection values were supplied by the harness; hopping ports and cadence came from the fixture.
- This controlled peer run covers direct RelayRuntime JSON, SOCKS TCP and UDP. Chain coverage uses the JSON-to-factory policy/TLS unit test; this run does not add a chain exit-peer claim. The combined acceptance below supersedes the pending gates in this local native run.


The PR hopping job requires all eight independent peer cases and exact execution of the Linux GRO capability test. It publishes metadata only. No production or private-data action occurred.

## Combined source acceptance

Source PR 661 merged as `e22c8d66d8fa0c9245eb5bf8ef802e24028d0f48`.
The submitted head was `b8b2eca0294973df68f52ecddcafcef6ccc62c8a`;
its tested merge source was `e87c9428636e72d641d6940afd3269efa759b27a`.

- Kotlin tests passed: runtime-state 325, data 988, service 2280, engine 315,
  and three app migration/share cases. Full `staticAnalysis` and all 3188
  Simple app tests passed, including 25 seeder cases. No failures or skips
  occurred in these local tests.
- The async-safety scanner, 28 soundness tests and nine native socket tests
  passed after socket-release assertions used awaited Tokio binds.
- Task contracts, architecture checks, locked Cargo metadata, acceptance
  manifests, actionlint and cross-repository task links passed.
- The exact PR head had 59 successful checks and 19 routed skips, including
  CI run `40082693780`. These skips are not protocol evidence.
- Linux acceptance run `40082693795`, job `120839369907`, passed the exact
  Linux GRO test and all eight hopping peer cases, with zero failures or skips.
  Each case verifies sustained TCP/UDP traffic, payload integrity and at least
  three physical local and remote port changes in one authenticated session.
  The report SHA-256 is
  `12cca6ce8c5cebfbb99e0547d230193916fe87b8eea7de3f64deda4cd369e507`.
- Android artifact `12556472475` has ZIP SHA-256
  `4d3b54c898c32c68d66c0c660f62148b905297a0d8d5aef152444e86628f19c6`.
  The repository verifier checked all six APK hashes, package names and sealed
  source. Both app APKs contain 38 DEX files and 16 x86_64 ELF libraries with
  at least 16 KiB load alignment. All four native ABI producers passed.
- The prior PR revision had one permission UI timeout on API 35 and API 36.
  The unchanged focused test passed on a leased local API 35 emulator, and all
  API jobs passed at the final PR head. No cause was confirmed and no timeout,
  click or assertion was weakened. This is not a claimed UI defect fix.

Main run `40085973754` completed successfully on its first attempt at
2026-10-24 11:14:34 UTC: 48 successful jobs, 16 routed skips, zero failures.
All five Android API jobs passed. CodeQL, Secret Scan, harness, L7 dry-run and
fleet-fixture workflows also passed on the exact main commit. Physical-device,
production and complete chain exit-peer acceptance remain outside this scope.

## Local command scope

The Kotlin commands below used JDK 17, the configured Android SDK, the machine
build gate, at most four workers and `-Pripdpi.skipNativeBuild=true`. They prove
JVM/static behavior; native and device results are recorded separately.

- `./gradlew :core:data:testDebugUnitTest :core:service:testDebugUnitTest :app:testGithubFullDebugUnitTest --tests '*DeviceMigrationHysteriaUpgradeTest' --tests '*RelayProfileShareMapperHysteria2Test'`: exit 0; 988 data, 2280 service and three app tests. The filters apply only to the last app task.
- `./gradlew :core:data:runtime-state:detekt :core:data:runtime-state:ktlintCheck :core:data:runtime-state:testDebugUnitTest`: exit 0; 325 tests and both module style checks passed.
- `./gradlew :app:testGithubSimpleDebugUnitTest staticAnalysis`: exit 0; 3188 tests and full static analysis passed on source `865722cf17fe6210f836bd1e75973c53f487d959`, included in the merged source. The ignored Simple asset used the public repository CI fixture.
- Engine tests completed with 315 passes in two earlier combined invocations.
  Those invocations later failed on unrelated downstream test expectations or
  test-class size. They are not recorded as passing combined commands; the
  subsequent scoped correction and final full static gate passed.
