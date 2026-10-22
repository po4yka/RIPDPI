---
task_id: VPN-1792657107995294
change: repair-vpn-rollout-audit
commit_sha: 130827437750fddd58ebda47a7dba71ffb238258
local: passed
local_evidence: Targeted Kotlin and Rust tests, staticAnalysis, workspace Clippy, Cargo Deny, architecture health, native contracts, and locked metadata passed.
remote_ci: required
remote_ci_evidence: null
device: passed
device_evidence: API 37.0 arm64 pooled emulator passed 1 socket ownership test and all 24 service lifecycle tests in separate runs, with no failures or skips. This does not prove live backend or physical-device acceptance.
artifact: passed
artifact_evidence: GithubFullDebug arm64-v8a APK and AndroidTest APK built successfully; expected native libraries and root helper are present. Release signing and other ABIs remain outside this debug gate.
deployment: not_applicable
deployment_evidence: This change does not deploy or change live server configurations.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SHARE-CREDENTIALS | VPN-1792657277467804 | BackupGroupRedactionTest: 7 passed; SHARE URL carrier matrix and FULL preservation | Passed |
| REQ-RELAY-DNS | VPN-1792657278981644 | ripdpi-relay-android and ripdpi-native-protect: 26 passed, including 5 JVM/JNI regressions | Passed |
| REQ-RUNTIME-CLEANUP | VPN-1792657282574861 | core:service:testDebugUnitTest: 2257 passed, including 12 RuntimeExitCleanupTest scenarios | Passed |
| REQ-SUBSCRIPTION-REFRESH | VPN-1792657284863659 | App subscription/import tests: 104 passed again after scheduler adapter change; Hilt/KSP and staticAnalysis passed | Passed |
| REQ-RECIPIENT-COMPATIBILITY | VPN-1792657285994594 | core:data import/backup suite: 101 passed; generated backend bundle and AWG-only refresh passed | Passed |
| REQ-COMPATIBILITY-EVIDENCE | VPN-1792657287269408 | 122 backend checks passed; 22 common contract JSON files byte-identical; combined staticAnalysis, debug APK and 25 device smoke tests passed | Passed |

## Scope and commands

- Backend reference: `794202c393648986ceea7afd7d022933fbe44dce` in a clean detached checkout.
- Generate the regression document with `python3 scripts/tests/generate_deployment_bundle_fixture.py --backend <clean-backend-checkout>`.
- Generated payload SHA-256: `583d1a51c7f864b66726255d6656dd9bb40e8cf08a97a1cdb47c748232d501d3`.
- Run `cargo test -p ripdpi-relay-android -p ripdpi-native-protect --locked --jobs 4` from `native/rust`.
- Kotlin checks use `:core:data:testDebugUnitTest`, `:core:service:testDebugUnitTest`, and `:app:testGithubFullDebugUnitTest`. Data tests cover backup, selector, bundle, sing-box, and AWG contracts. App tests cover subscription and import classes. The service suite runs in full. These JVM checks use `ripdpi.skipNativeBuild=true`; they do not prove APK contents.
- Backend checks cover recipient boundaries, port hopping, recipient rendering, emit roundtrip, and transport semantics. The official parser check passed with backend-CI-pinned sing-box 1.14.3.
- Three backend native transport tests require a disposable Linux root environment and did not pass on macOS. They are not counted among the 122 passing tests.
- The generated document uses synthetic authority data and the real materializer. It proves serialization and import behavior, not server reachability or a user VPN session.
- No live server configuration, deployment, signing key, or real user credential was changed.

## Independent review

Separate agents reviewed runtime cleanup, backup privacy, subscription scheduling,
selector topology, JNI ownership, and backend compatibility. Confirmed review
findings were fixed. Follow-up review found no remaining actionable issue in
those changes. Runtime and device results remain separate gates.

The final `staticAnalysis` and 104 app tests passed after all Detekt fixes.
The selector regression suite passed again after the root-selection refactor.

The post-reboot Rust rerun passed all 26 tests and full workspace Clippy.
`cargo deny --locked --manifest-path native/rust/Cargo.toml check` passed
(advisories, bans, licenses and sources); the existing warning policy was unchanged.
Final architecture health measured 116 crates and 4109 source files, with no new
or worsened indicator. The backend fixture regenerated to the same payload hash.
New validation logs are stored in the worktree under `build/rollout-evidence/`.

## Android artifact

`assembleGithubFullDebug` and `assembleGithubFullDebugAndroidTest` passed
in 6m 37s after the host reboot. The arm64 debug APK contains the relay, tunnel,
AWG, WARP and DPI libraries, libXray, and the root helper.

- App APK SHA-256: `1f18ba1be3e158e7e49b7cf8e68aaea6f61cf6e846c55a6a9cabe700e009430a`.
- AndroidTest APK SHA-256: `b278f9f611470374fa40bf1c0db16c9a19336626925f579287c4b7c00cd3c185`.
- Local logs: `build/rollout-evidence/apk-build.log` and `apk-manifest.json`.

This debug build does not prove release signing, R8 behavior, or all-ABI packaging.

## Combined-tree and device rerun

The branch was rebased onto `origin/main` (`8f77064124c3981e1983834bc5603a3981cf5983`);
no source change was needed. The combined gate then passed in 3m 5s:
101 data tests, 2257 service tests, 106 app tests, and full `staticAnalysis`.
No JVM test failed or was skipped.

The API 37.0 arm64 emulator passed the socket-ownership test and all 24 tests
in `ServiceLifecycleIntegrationTest`. The first comma-separated class request
ran only the socket test; a separate lifecycle request ran all 24 cases.
No discovery cause was established, and no test or runner was changed.
The XML reports, not only the Gradle exit status, were checked. Service lifecycle
tests use injected runtime fixtures; they do not prove real VPN traffic.

- Device logs: `build/rollout-evidence/device-smoke.log` and `lifecycle-device.log`.
- XML snapshots: `protect-device-results/` and `lifecycle-device-results/` in that directory.
- Combined gate: `build/rollout-evidence/combined-final.log` and `jvm-results.json`.
- Three Linux transport tests, live backend sessions, physical-device acceptance,
  release/R8 and all-ABI qualification remain unverified.

The device gate repackaged the APKs with instrumentation coverage enabled:

- `app/build/outputs/apk/githubFull/debug/app-github-full-arm64-v8a-debug.apk`: `1528f2517079f4640a6447671771a84617bde4f35b7cab5daffd7fc424790c29`.
- `app/build/outputs/apk/androidTest/githubFull/debug/app-github-full-debug-androidTest.apk`: `5569bb5c3da96b24701cc7f22fe82f8a209957d3f1483d8c95eee54194dfd415`.
