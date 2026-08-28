---
task_id: OUT-1786264762917107
change: out-1786264762917107-run-xray-as-managed-vpn-relay-runtime
commit_sha: ae4926a9ffb6533cfb20ac920ec5dc565ac19711
local: passed
local_evidence: Rebased committed-tree gate passed in 5m30s; 61 engine-api, 311 engine, 1894 service tests; staticAnalysis and real APK assembly; /private/tmp/ripdpi-xray-rebased-gate-20260828.log.
remote_ci: blocked
remote_ci_evidence: CI 33131473606 found a new Xray bootstrap failure because gomobile init replaced pinned gobind with latest; strict verification rejected it. Static analysis passed; unchanged listener hotspot and Clone guard also failed. Corrected bootstrap requires a new exact-SHA run. https://github.com/po4yka/RIPDPI/actions/runs/33131473606/job/98724726239
device: passed
device_evidence: API34 arm64 isolated emulator; AndroidTestOrchestrator 1.6.1; XrayRuntimeInstrumentedTest 2/2 passed in 11.614s on committed ae4926a9f APK; /private/tmp/ripdpi-xray-final-ae4926a9f-api34-orchestrator-20260828.log
artifact: passed
artifact_evidence: Four-ABI AAR SHA256 df49079d10a781b6bf03483308784e23a9de2b0ef197cc5fcd492c2e663cb1d3; arm64 APK SHA256 75b053f89f79e80daedc4d0c57b033bbd08e7797e8f0cec060a01521ef0585ca; signature, runtime DEX and six required native ELF payloads verified.
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

The final emulator APK was rebuilt from clean, rebased commit `ae4926a9ffb6533cfb20ac920ec5dc565ac19711`; its DEX contains that BuildConfig revision. The same commit was fast-forwarded and pushed to `origin/main`. A second real Android run passed 2/2 tests in 11.614s and the owned emulator stopped. Exact-SHA hosted CI is tracked separately below. APK byte/hash checks are not a release-signing or physical-device claim.

- AAR: `/private/tmp/ripdpi-xray-managed-aar-all-v3-20260828/libxray.aar` (50,425,194 bytes); Go 1.27.0, pinned NDK29, all four ABIs.
- Android: `/private/tmp/ripdpi-xray-android-smoke-run-20260828.log`; owned `emulator-5580` stopped in the runner's finally block. Tests use real gomobile/VLESS and a local echo peer, but do not establish a physical-device, external TLS/REALITY server or full TUN route acceptance.
- Kotlin: `/private/tmp/ripdpi-xray-combined-final-pass6-20260828.log`; tests and engine-api/service detekt pass. Final strengthened destruction test also passes in `/private/tmp/ripdpi-xray-apk-build-20260828.log`.
- Packaging: missing real runtime is rejected on both initial and reused configuration cache (`ripdpi-xray-packaging-negative2/3-20260828.log`). A package-task dry run confirms the dependency edge; this is separate from the observed successful real APK assembly.
- Native/CI: native protection, partial-construction/start and sticky-close RED/GREEN logs under `/private/tmp/ripdpi-xray-*`; Python packaging/CI contracts, actionlint, pinact, architecture health, locked Cargo metadata and strict task/OpenSpec validation pass.
- A permanently hung native call requires process restart for recovery: callers return Pending, ownership and protection revocation remain intact, and no second native runtime is admitted. There is no unsafe thread termination.

## Committed-tree artifacts

- App: `/private/tmp/ripdpi-xray-managed-artifacts-20260828/ripdpi-xray-ae4926a9f-arm64.apk`, 166,790,046 bytes, SHA256 `75b053f89f79e80daedc4d0c57b033bbd08e7797e8f0cec060a01521ef0585ca`.
- AndroidTest: `/private/tmp/ripdpi-xray-managed-artifacts-20260828/ripdpi-xray-ae4926a9f-androidTest.apk`, 2,107,378 bytes, SHA256 `72a18249cf8abf5e31ca05347be860fd3a58c1567c2bc42404824082b21959f1`.
- Signature: `/private/tmp/ripdpi-xray-final-apk-signature-20260828.log`; v2 verified. APK runtime DEX, embedded revision and all six required arm64 native ELF payloads were inspected; LOAD alignment is at least 16 KiB.
- Hosted CI: <https://github.com/po4yka/RIPDPI/actions/runs/33131473606>, exact source commit `ae4926a9ffb6533cfb20ac920ec5dc565ac19711`; static analysis, CodeQL, Secret Scan and fleet-fixtures passed. The new native producer rejected a mismatched gobind before compilation: upstream `gomobile init` installs `gobind@latest` over the previously pinned binary. This is a CI bootstrap regression, not successful native CI evidence. The corrected bootstrap must pass a new exact-SHA run. Architecture-health and Rust workspace guards also fail the unchanged listener hotspot budget (72 > 54); Rust lint repeats the existing `FlowAttributionToken` Clone guard failure.
- Baseline CI <https://github.com/po4yka/RIPDPI/actions/runs/33125265188> on previous `9f5b4c233` failed before this implementation: listener hotspot budget 72 > 54; owner-name Clone guard for `FlowAttributionToken`; two DNS candidate planner assertions. These source paths are unchanged by the Xray commits. They are not waived or counted as successful checks.

## CI bootstrap correction

The action no longer invokes `gomobile init`: the pinned upstream command installs `gobind@latest`, while Android binding needs only the two explicitly pinned binaries. The artifact recipe, native source patches and strict toolchain guard are unchanged. An executable bootstrap regression fails before the correction and passes afterward; a real arm64 bind with a fresh GOPATH and no gomobile initialization also passes. This small binding test is toolchain evidence, not a replacement production AAR or runtime smoke. Logs: `/private/tmp/ripdpi-xray-bootstrap-regression-red-20260828.log`, `/private/tmp/ripdpi-xray-bootstrap-regression-green-20260828.log`, and `/private/tmp/ripdpi-xray-bootstrap-noinit-20260828.log`. Combined-tree validation passes 44 native/CI contracts, actionlint, pinact, architecture health and locked Cargo metadata. A new Linux CI run is still required.
