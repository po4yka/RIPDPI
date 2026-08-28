---
task_id: OUT-1786264762917107
change: out-1786264762917107-run-xray-as-managed-vpn-relay-runtime
commit_sha: 6e4bfccdd585d61107ab5f2d8bad158caf770d81
local: passed
local_evidence: Committed-tree tests and APK assembly with the Linux CI AAR passed in 2m10s; 61 engine-api, 311 engine, 1894 service tests; /private/tmp/ripdpi-xray-ci-aar-apk-gate-6e4bfccdd-20260828.log. Full staticAnalysis passed before the action-only bootstrap fix and on the corrected exact-SHA CI.
remote_ci: blocked
remote_ci_evidence: Corrected exact-SHA CI 33132895844 completed with failure; 37 jobs passed, including both Xray jobs, Android API27/33/35, all debug and release variants. Eight failed jobs stem from unchanged Rust hotspot/Clone guards, DNS assertions and an AddrInUse failure in an unchanged Rust fixture test, including aggregates. CodeQL, Secret Scan and fleet-fixtures passed separately. https://github.com/po4yka/RIPDPI/actions/runs/33132895844
device: passed
device_evidence: API34 arm64 isolated emulator; AndroidTestOrchestrator 1.6.1; XrayRuntimeInstrumentedTest 2/2 passed in 11.592s on clean committed 6e4bfccdd APK containing the Linux CI AAR; /private/tmp/ripdpi-xray-ci-aar-fresh-6e4bfccdd-api34-orchestrator-20260828.log
artifact: passed
artifact_evidence: Four-ABI Linux CI AAR SHA256 c088962b644268a497d6862f38c92d7f3dba8790481e3e25ce47a2ecac3c31fc; arm64 APK SHA256 243613cbc85be7e37c3f80dbd16be2ba8cbfab81c6119d55b9f57b784a38212e; signature, runtime DEX and six required native ELF payloads verified.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-OUT-1786264762917107-001 | OUT-1786264762919162 | native lifecycle/protection Go tests; linked bridge and XrayProtectFdContractTest; real Android denied-socket smoke | passed |
| REQ-OUT-1786264762917107-002 | OUT-1786264762919691 | SOCKS5 handshake readiness; blocked/cancelled native start and service destruction regressions; real Android payload | passed |
| REQ-OUT-1786264762917107-003 | OUT-1786264762919377 | failed/hung/late native stop and partial construction cleanup regressions; actual Android stop/rebind/restart | passed |
| REQ-OUT-1786264762917107-004 | OUT-1786264762919314 | secret-free snapshot tests, stale-generation stop guard and STOPPING recovery tests | passed |
| REQ-OUT-1786264762917107-005 | OUT-1786264762919536 | 61 engine-api + 311 engine + 1894 service tests; real Android VLESS loopback 2/2; verified AAR/APK | passed |

## Acceptance environment

Use an isolated Android emulator and controlled loopback peer for real gomobile execution. Physical-device installation and VPS/deployment are outside scope. Fake/unit tests are separate evidence and do not satisfy device or artifact acceptance.

## Local artifact and execution boundary

The final emulator APK was rebuilt from clean, pushed commit `6e4bfccdd585d61107ab5f2d8bad158caf770d81` using its downloaded Linux CI AAR; its DEX contains that BuildConfig revision. Real Android execution passed 2/2 tests in 11.592s and the owned emulator stopped. A preceding APK using the locally built AAR also passed 2/2; it is historical evidence rather than the final artifact. Exact-SHA hosted CI is tracked separately below. APK byte/hash checks are not a release-signing or physical-device claim.

- AAR: `/private/tmp/ripdpi-xray-ci-aar-6e4bfccdd-20260828/libxray.aar` (50,424,040 bytes), CI artifact `9671279439`; Go 1.27.0, pinned NDK29, all four ABIs. The downloaded artifact independently passes provenance/API/ELF checks, and its arm64 payload exactly matches the final APK.
- Android: `/private/tmp/ripdpi-xray-ci-aar-fresh-android-smoke-run-6e4bfccdd-20260828.log`; owned `emulator-5580` stopped in the runner's finally block. Tests use real gomobile/VLESS and a local echo peer, but do not establish a physical-device, external TLS/REALITY server or full TUN route acceptance.
- Kotlin: `/private/tmp/ripdpi-xray-combined-final-pass6-20260828.log`; tests and engine-api/service detekt pass. Final strengthened destruction test also passes in `/private/tmp/ripdpi-xray-apk-build-20260828.log`.
- Packaging: missing real runtime is rejected on both initial and reused configuration cache (`ripdpi-xray-packaging-negative2/3-20260828.log`). A package-task dry run confirms the dependency edge; this is separate from the observed successful real APK assembly.
- Native/CI: native protection, partial-construction/start and sticky-close RED/GREEN logs under `/private/tmp/ripdpi-xray-*`; Python packaging/CI contracts, actionlint, pinact, architecture health, locked Cargo metadata and strict task/OpenSpec validation pass.
- A permanently hung native call requires process restart for recovery: callers return Pending, ownership and protection revocation remain intact, and no second native runtime is admitted. There is no unsafe thread termination.

## Committed-tree artifacts

- App: `/private/tmp/ripdpi-xray-managed-artifacts-20260828/ripdpi-xray-6e4bfccdd-ci-aar-fresh-arm64.apk`, 166,307,771 bytes, SHA256 `243613cbc85be7e37c3f80dbd16be2ba8cbfab81c6119d55b9f57b784a38212e`.
- AndroidTest: `/private/tmp/ripdpi-xray-managed-artifacts-20260828/ripdpi-xray-6e4bfccdd-ci-aar-androidTest.apk`, 2,107,387 bytes, SHA256 `24ff107a619942b644fb07604eeab358ea162ebafe00be5b862b79ab2a6aa20f`.
- Signature: `/private/tmp/ripdpi-xray-ci-fresh-apk-signature-20260828.log`; v2 verified. APK runtime DEX, embedded revision and all six required arm64 native ELF payloads were inspected; LOAD and ZIP data alignment are at least 16 KiB. Fresh packaging removed an incremental ZIP gap without changing any of the 1810 entry payloads; the final APK has no unallocated inter-entry gap.
- Hosted CI: <https://github.com/po4yka/RIPDPI/actions/runs/33131473606>, exact source commit `ae4926a9ffb6533cfb20ac920ec5dc565ac19711`; static analysis, CodeQL, Secret Scan and fleet-fixtures passed. The new native producer rejected a mismatched gobind before compilation: upstream `gomobile init` installs `gobind@latest` over the previously pinned binary. This is a CI bootstrap regression, not successful native CI evidence. The corrected bootstrap required a new exact-SHA run; its results are recorded below. Architecture-health and Rust workspace guards also fail the unchanged listener hotspot budget (72 > 54); Rust lint repeats the existing `FlowAttributionToken` Clone guard failure.
- Baseline CI <https://github.com/po4yka/RIPDPI/actions/runs/33125265188> on previous `9f5b4c233` failed before this implementation: listener hotspot budget 72 > 54; owner-name Clone guard for `FlowAttributionToken`; two DNS candidate planner assertions. These source paths are unchanged by the Xray commits. They are not waived or counted as successful checks.

## CI bootstrap correction

The action no longer invokes `gomobile init`: the pinned upstream command installs `gobind@latest`, while Android binding needs only the two explicitly pinned binaries. The artifact recipe, native source patches and strict toolchain guard are unchanged. An executable bootstrap regression fails before the correction and passes afterward; a real arm64 bind with a fresh GOPATH and no gomobile initialization also passes. This small binding test is toolchain evidence, not a replacement production AAR or runtime smoke. Logs: `/private/tmp/ripdpi-xray-bootstrap-regression-red-20260828.log`, `/private/tmp/ripdpi-xray-bootstrap-regression-green-20260828.log`, and `/private/tmp/ripdpi-xray-bootstrap-noinit-20260828.log`. Combined-tree validation passes 44 native/CI contracts, actionlint, pinact, architecture health and locked Cargo metadata.

The corrected Linux run now passes native producer job `98728599320` (8 Go tests plus 12 subtests, 7 Python packaging tests, verified four-ABI AAR) and linked Kotlin job `98729501317`. Its remaining failures are not waived: the listener hotspot exceeds 54 at 72, the owner-name guard rejects `FlowAttributionToken`'s Clone derive, two `ConnectivityDnsTargetPlannerTest` assertions fail, and `repeated_tcp_resets_confirm_blocked_host_and_expose_telemetry` encounters `AddrInUse` while starting its second fixture. These Rust/DNS source paths are unchanged by this task; the port collision was observed in this run and is not claimed as a baseline failure. Overall CI remains blocked, so the task is not archived or closed.

Final run `33132895844` completed on exact `6e4bfccdd`: 37 jobs passed and 8 failed, including aggregate gates. All three Android instrumented jobs (API27/33/35), all three debug distribution builds, and all three release-verification shards (Full and Simple) passed. The final job snapshot is `/private/tmp/ripdpi-xray-final-ci-33132895844-20260828.json`. These checks are build/emulator evidence, not a production release or physical-device deployment.
