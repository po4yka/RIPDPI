---
task_id: UIX-1792259264757428
change: haptic-feedback
commit_sha: 2099cbb498813c5f2c74264cbb65751b0ff01021
local: blocked
local_evidence: Behavioral tests, static analysis, locale lint, architecture and locked Cargo metadata pass. Strict screenshot verification needs authorization for 14 reviewed fixtures; no fixtures have changed.
remote_ci: required
remote_ci_evidence: null
device: blocked
device_evidence: At 2099cbb, all 16 API 37 instrumented tests and all four Full real UI checks pass. Simple real UI acceptance is blocked because the genuine embedded AWG profile has only IPv4 while the current contract requires IPv4 and IPv6. Installed APK hashes match and emulator settings were restored without errors.
artifact: blocked
artifact_evidence: Four arm64 APKs were built and hashed at 2099cbb. All 14 intended screenshot changes were reviewed, but their fixture update is not yet authorized. The genuine private Simple bundle needs a current dual-stack AWG profile before real Simple UI acceptance.
deployment: not_applicable
deployment_evidence: No deployment is owned by this app preference change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-HAPTIC-PREFERENCE | UIX-1792259354389850 | Storage and backup tests pass; Full and Simple behavior cohorts each pass 432 tests; Full plain restart passes for Off and System; Simple real restart remains blocked | host and Full device passed; Simple blocked |
| REQ-HAPTIC-CONNECTION | UIX-1792259354945871 | Actual Running, acceptance, cancellation, equal retries, IO failure, restored state and deferred failure race regressions pass | host passed |
| REQ-HAPTIC-RESULT | UIX-1792259354945871 | Detection and diagnostics once-only, lifecycle, Off, IO and cancellation tests pass | host passed |
| REQ-HAPTIC-VALIDATION | UIX-1792259355458709 | Full, Simple and service lint pass; all 16 platform tests pass; Full real UI and Arabic font 200 percent pass; 14 complete actual/diff images reviewed; Simple real UI and authorized fixture verification remain required | blocked |

## Observed local evidence

- At c8f66c79a, Full and Simple each passed 432 tests with no failures, errors or skips. The final narrow Main and diagnostics cohort passed all 25 tests. The RED runs reproduced duplicate suppression, stale state and disabled feedback defects before the fixes.
- At 2099cbb, one combined Gradle run passed static analysis, Full and Simple app lint, service lint, and all four debug APK assembly tasks: 929 tasks, build successful in 8 minutes 8 seconds.
- Architecture validation passed with 21 existing findings and no new or worse findings. Locked Cargo metadata resolved all 116 workspace crates. The native inputs were checked against their producer and all 16 input hashes matched.
- The Android test host now uses FrameLayout so Material can find its ripple container. Test bodies, assertions and feedback recording overrides did not change.
- Local receipts and logs are under `.artifacts/haptic-feedback/`: `combined-unit-summary.json`, `combined-gates/`, `host-fix-gates/`, `host-fix-scope.json`, and `apk-receipt-2099cbb49.json`. These generated files are not committed.
- The final API 37 run used a standard Pool_1 lease and a read-only checkout at the exact APK source commit, 2099cbb. Full and Simple each passed eight instrumented tests, with no failures, errors or skips. Full passed back/reopen, Off plain restart, System plain restart and Arabic font 200 percent option checks. Root and the independent device reviewer checked the real Full screenshots.
- `device-api37-2099cbb49-popup-choice/` contains the exact source and installed APK hashes, all 16 test results, four Full UI results, plain launch arguments, and restoration receipts. Locale, font, size and density match the original values. The crash buffer is empty. The overall run remains failed because Simple cannot finish startup with the old private bundle.

## Simple input blocker

The genuine local bundle is 4735 bytes, SHA-256 `9ed07fb571c49434e75729f781d19785e6054c773e47600c4302eff920c7140a`. Its AWG profile has one IPv4 host address. `SingBoxAwgNetworkPolicy.isDualStackHostAddresses` requires one IPv4 /32 and one IPv6 /128. The parser reports `INVALID_NETWORK_POLICY` for `address`, and `ConfigSeeder` correctly refuses a required bundle with skipped profiles.

The build task checks only that the file exists and is nonempty. It does not establish runtime compatibility. A bounded read-only check found 16 assets and three distinct contents across registered worktrees. All genuine copies have the same old IPv4-only profile; the other two contents match CI fixtures and were excluded from real acceptance. No private endpoints, addresses or credentials were printed, and the input was not changed. A current genuine bundle with an assigned IPv6 address is required.

## Remaining gates

- Obtain a current genuine Simple bundle and complete its real UI checks under the command-scoped emulator lease. Keep the existing successful 16 platform tests and Full real UI evidence distinct from this missing gate.
- Obtain fixture authorization, update only the 14 reviewed PNG files with the golden specialist, and pass strict screenshot verification.
- Fetch and rebase, repeat the applicable combined-tree gates, integrate main, push, and inspect hosted CI for the exact published SHA.
- Device tests count platform calls. They do not establish tactile quality on physical hardware.
- Runtime failure events do not contain a command ID. Authority snapshots distinguish sequential starts; they do not add protocol-level correlation to late native events.

## Publication

UIX-1792259356008226 owns independent review, combined gates, rebase, authorized main integration, push and exact-source hosted CI inspection. Main has not been changed or pushed. Hosted CI has not validated this feature SHA.
