---
task_id: SVC-1791025113925875
change: svc-1791025113925875-actionable-diagnostics-verified-selector
commit_sha: 335336b5eac0c29cd3185c46f05d8bbf8f885358
local: passed
local_evidence: "Final source 335336b5e: engine 328/service 2033/app selector 23 tests, zero failures/errors/skips; staticAnalysis and app Full/service locale lint passed (730 tasks, /tmp/selector-final-tree-static-lint.log); architecture 23/23, locked metadata 116 crates/354 edges and scoped formatting passed."
remote_ci: passed
remote_ci_evidence: "https://github.com/po4yka/RIPDPI/actions/runs/37128129033 terminal SUCCESS on exact source 335336b5eac0c29cd3185c46f05d8bbf8f885358: 47 successful jobs, 17 route/nightly skips, zero failures. Required API 27/33/35/36/37 jobs all succeeded. API37 artifact 11275893559: 69 tests, zero failures/errors, 7 existing assumption skips; all 3 new actual-JNI cases executed without skip/failure. CodeQL, Secret Scan, harness-checks and fleet-fixtures also terminal SUCCESS."
device: passed
device_evidence: "API 37 arm64 emulator: 27/27 tests passed in /tmp/selector-final-device-smoke.log; 3 actual native JNI payload/retirement tests and 24 Android service lifecycle tests with controlled native runtimes. APK SHA256 a916cd4bee120bf9d6f1abde5732e8a9750e3a4d432be6e7c632fc3d977614f0; test APK cdf12e5466352534af9db9f776ff911a40fe4ee941ee23a30e0f2a5dc0f7dba9."
artifact: not_applicable
artifact_evidence: No distributable release artifact is owned; build and device proof are recorded separately.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-ADS-ACTIONS | SVC-1791025380381572 | `372bda4ac3ecd7eaf1004dbbc6bac9cb5184eba5`: recommendation generation/navigation and full-row callback tests passed (9 core +42 app); locale lint and app/core ktlint/detekt passed. English/German/Arabic previews visually inspected; 10-locale key parity verified after rebase. | passed |
| REQ-ADS-SELECTION | SVC-1791025380877118 | `671f5addd1d1043f121ec4b7c5940848a2fa694b`: service 2010, settings 53 and app selector/activation 27 tests passed. Final combined source `335336b5eac0c29cd3185c46f05d8bbf8f885358`: service 2033 and app selector 23 tests passed with zero failures/errors/skips. Seed-before-snapshot, child timeout recovery, pre-dispatch cancellation, owner overlap and stale profile guards covered. API 37 Android service lifecycle smoke: 24/24 passed with controlled native runtimes. | passed |
| REQ-ADS-PAYLOAD | SVC-1791025381376340 | `d019bda1d06b9512153ceb422b2cac53259b86c2`: service 2030 and app 164 tests passed; complete/truncated/oversized/stalled payload, cleanup, cancellation and scope/selection ABA guards covered. Final source `335336b5eac0c29cd3185c46f05d8bbf8f885358`: engine 328, service 2033 and app selector 23 tests passed, zero failures/errors/skips. API 37 actual JNI fixture: 3/3 passed, proving decrypted candidate target, configured HTTP path/query, complete/truncated response behavior, native handle retirement and unchanged persisted settings. Four Android-only PT launch fields excluded from native JSON while strict Rust schema v10 remains unchanged. | passed |
| REQ-ADS-LOCALITY | SVC-1791025379377245 | `ca586188e95b20c51f984db0eb9a1d2c50a7012b` and `1dbd70f032385d36ffc03c8720c58efeff59f5f5`: proxy 232 unit +59 integration; monitor 242 unit +18 integration; locked metadata, native contracts, 30 Python policy tests, workspace Clippy and cargo-deny passed. Opt-in soak/load tests retain their existing defaults. Exactly two discouraged canonical-model dependencies removed with explicit user approval; no baseline edits. | passed |
| REQ-ADS-LOCALITY | SVC-1791025379880611 | `build-gate -- env CARGO_BUILD_JOBS=3 ./gradlew :core:diagnostics:testDebugUnitTest :app:testGithubFullDebugUnitTest -Pripdpi.skipNativeBuild=true --max-workers=4`: passed; standalone ktlint and independent semantic review passed. | passed |
| REQ-ADS-LOCALITY | SVC-1791025381882198 | Final source `335336b5eac0c29cd3185c46f05d8bbf8f885358`: `staticAnalysis :app:lintGithubFullDebug :core:service:lintDebug` passed (730 tasks); architecture 23/23 with no new/worsened/stale violations, locked metadata 116 crates/354 edges and 27/27 API 37 smoke tests passed. Hosted CI run `37128129033` terminal SUCCESS: 47 successful jobs, all five required Android API jobs succeeded; all three new actual-JNI cases executed without skip/failure in API 37 artifact `11275893559`. Existing optional route/nightly and assumption skips are reported separately. | passed |

## Device build provenance

The API 37 arm64 APKs were built from final Kotlin source with the repository-owned prebuilt inputs staged by their Gradle tasks: arm64 JNI/root/relay helper artifacts from CI `37123536236` at `671f5addd1d1043f121ec4b7c5940848a2fa694b`, source-mode PT assets from CI `37121663694`, and verified Xray v26.3.27 patched AAR. Corresponding native source/build inputs remained unchanged. This is actual JNI fixture evidence on loopback, plus Android service lifecycle evidence with controlled native runtimes; it does not claim external relay interoperability or live VPN network acceptance. Build log: `/tmp/selector-jni-final-combined-gates-clean.log`.
