---
task_id: OUT-1786264762917107
change: out-1786264762917107-run-xray-as-managed-vpn-relay-runtime
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: passed
device_evidence: API34 arm64 isolated emulator; AndroidTestOrchestrator 1.6.1; XrayRuntimeInstrumentedTest 2/2 passed in 14.082s; /private/tmp/ripdpi-xray-api34-orchestrator-20260828.log
artifact: passed
artifact_evidence: Four-ABI AAR SHA256 df49079d10a781b6bf03483308784e23a9de2b0ef197cc5fcd492c2e663cb1d3; arm64 APK SHA256 92fc6b169334c08a0f43d7365b377a91eeae2693ee562316a4444916be0f03ad; signature, runtime DEX and six required native ELF payloads verified.
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

The initial emulator APK was built from the implementation worktree (BuildConfig parent `9502005dd`, runtime source subsequently committed as `71c05937c`). Final committed-tree rebuild and exact-SHA hosted CI are pending. APK byte/hash checks are not a release-signing or physical-device claim.

- AAR: `/private/tmp/ripdpi-xray-managed-aar-all-v3-20260828/libxray.aar` (50,425,194 bytes); Go 1.27.0, pinned NDK29, all four ABIs.
- Android: `/private/tmp/ripdpi-xray-android-smoke-run-20260828.log`; owned `emulator-5580` stopped in the runner's finally block. Tests use real gomobile/VLESS and a local echo peer, but do not establish a physical-device, external TLS/REALITY server or full TUN route acceptance.
- Kotlin: `/private/tmp/ripdpi-xray-combined-final-pass6-20260828.log`; tests and engine-api/service detekt pass. Final strengthened destruction test also passes in `/private/tmp/ripdpi-xray-apk-build-20260828.log`.
- Packaging: missing real runtime is rejected on both initial and reused configuration cache (`ripdpi-xray-packaging-negative2/3-20260828.log`). A package-task dry run confirms the dependency edge; this is separate from the observed successful real APK assembly.
- Native/CI: native protection, partial-construction/start and sticky-close RED/GREEN logs under `/private/tmp/ripdpi-xray-*`; Python packaging/CI contracts, actionlint, pinact, architecture health, locked Cargo metadata and strict task/OpenSpec validation pass.
- A permanently hung native call requires process restart for recovery: callers return Pending, ownership and protection revocation remain intact, and no second native runtime is admitted. There is no unsafe thread termination.
