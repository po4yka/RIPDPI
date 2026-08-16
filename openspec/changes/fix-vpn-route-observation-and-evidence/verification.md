---
task_id: DGN-1786867116840500
change: fix-vpn-route-observation-and-evidence
commit_sha: 2dcfb0d96bd064e6092bde6c72a58cd4d252d61b
local: required
local_evidence: "Service 1814/1814, app 1740/1740, and diagnostics 1264/1264 unit tests passed; the explicitly approved five-file schema-10 golden family, focused detekt, ktlint, assemble, architecture, task, diff, signing, and ELF evidence is recorded below."
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: required
artifact_evidence: "githubFull arm64-v8a debug APK SHA-256 b7e8f85ece0dd9c89096a19d7ef2562e191fd104eb025c7308f17cb53dbf0408; v2 Android Debug certificate c048a6d6124a61149f7d6d3d8aa80055d39335ff76f14e1584d508408798de15; packaged native ELF verification passed."
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

Implementation evidence below was collected in the isolated change worktree
before integration. It does not substitute for hosted CI or API 36 device
proof. The five-file schema-10 golden family was explicitly approved and
reviewed.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPN-ROUTE-001 | SVC-1786867116840502 | Receipt/callback and UI classifier tests pass; API 36 callback observation remains required | local pass; device pending |
| REQ-VPN-ROUTE-002 | SVC-1786867116840502 | Builder-intent and coherent callback route-family tests pass | pass |
| REQ-VPN-ROUTE-003 | DGN-1786867116840503 | Axis classifier, repeated forwarding-failure, recovery, poll-revision, and generation tests pass | pass |
| REQ-VPN-ROUTE-004 | SVC-1786867116840502 | Replacement/loss, rebuild retry, bridge rollback, descriptor-close failure, and retained-session cleanup tests pass | pass |
| REQ-VPN-ROUTE-005 | DGN-1786867116840503 | Schema projection, legacy decode, and hostile whole-ZIP redaction tests pass | local pass |
| REQ-VPN-ROUTE-006 | DGN-1786867116840503 | Real schema-9 fixture decode and the explicitly approved five-file schema-10 golden family pass | pass |
| REQ-VPN-ROUTE-007 | DGN-1786867116840503 | Local unit/static/build/artifact checks recorded below; hosted CI and API 36 owner/client scenario absent | partial |

## Observed local evidence

- `./gradlew :core:service:testDebugUnitTest`: 1814 tests, zero failures.
- `./gradlew :app:testGithubFullDebugUnitTest`: 1740 tests, zero failures.
- A preceding combined app/service/build invocation hit a Gradle test-results
  `NoSuchFileException`; the isolated app test rerun above completed
  successfully.
- `./gradlew :core:diagnostics:testDebugUnitTest`: 1264 tests, zero failures.
- The renderer and composite-exporter owning tests passed without the bless
  flag after the five schema-10 archive fixtures were generated and reviewed.
- Focused detekt and ktlint for `:core:service`, `:core:diagnostics`, and `:app`:
  passed.
- `./gradlew :app:assembleGithubFullDebug`: passed.
- `python3 scripts/ci/verify_native_elfs.py --lib-dir
  app/build/intermediates/merged_native_libs/githubFullDebug/mergeGithubFullDebugNativeLibs/out/lib
  --abis arm64-v8a`: passed.
- `python3 scripts/ci/check_architecture_health.py`: 22/22 checks passed.
- `./taskctl validate`: 49 tasks and 233 execution steps passed.
- `git diff --check`: passed.
- `./gradlew staticAnalysis`: passed on the rebased implementation commit.

## Artifact evidence

- APK: `app/build/outputs/apk/githubFull/debug/app-github-full-arm64-v8a-debug.apk`.
- SHA-256: `b7e8f85ece0dd9c89096a19d7ef2562e191fd104eb025c7308f17cb53dbf0408`.
- APK Signature Scheme v2 verified; signer is the Android Debug certificate,
  SHA-256 `c048a6d6124a61149f7d6d3d8aa80055d39335ff76f14e1584d508408798de15`.
- Packaged arm64-v8a native ELF metadata verification passed.

## Remaining evidence gates

- Golden approval completed: the explicitly authorized fixtures are
  `manifest_v10.json`, `analysis_v10.json`, `completeness_v10.json`,
  `integrity_v10.json`, and `manifest_home_composite_v10.json`; no other golden
  files changed.
- Device: the connected Pixel reports API 37, so the required physical API 36
  owner/client proof was not run. No APK was installed and no device state was
  changed.
- Remote CI: no commit was created or published; no hosted checks exist for the
  current tree.

## Required category boundaries

- Local: targeted tests, static analysis, app build, architecture health,
  task-board validation, diff check, and independent combined-diff review on the
  exact implementation SHA.
- Remote CI: required checks observed on the exact published SHA. Local green or
  a successful push is not remote-CI evidence.
- Device: physical API 36 owner callback, route families, UI transition,
  lifecycle handover, and distinct-client TUN-counter evidence on the exact APK.
- Artifact: application ID, version/build type, SHA-256, signing identity,
  packaged native verification, and installation match for the device-tested
  APK. A successful Gradle task alone is not an artifact record.
- Deployment: not applicable; this change does not own a backend or production
  deployment.
