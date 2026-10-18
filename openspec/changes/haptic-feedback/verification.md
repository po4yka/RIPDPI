---
task_id: UIX-1792259264757428
change: haptic-feedback
commit_sha: 2099cbb498813c5f2c74264cbb65751b0ff01021
local: passed
local_evidence: Behavioral tests, static analysis, locale lint, architecture and locked Cargo metadata pass. After the user approved 14 fixtures, canonical record and strict verification passed; the two strict screenshot families passed all 21 tests without failures, errors or skips.
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: At 2099cbb, all 16 API 37 instrumented tests and all four Full real UI checks pass. The user has authorized completing UI acceptance without a genuine backend bundle. Repeat Full and Simple real UI checks on the rebased source with the existing CI fixture as an ignored debug asset; keep the real-network evidence limit explicit.
artifact: passed
artifact_evidence: Four arm64 APKs were built and hashed at 2099cbb; their installation and device receipts are retained. The 14 user-approved PNG files are committed at f12ef96a8 and were independently reviewed. All 406 other screenshot fixtures are unchanged. The genuine Simple input blocker is tracked under the device gate.
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
| REQ-HAPTIC-VALIDATION | UIX-1792259355458709 | Full, Simple and service lint pass; all 16 platform tests pass; Full real UI and Arabic font 200 percent pass; all 14 approved PNG files were reviewed and both strict screenshot families pass 21 tests; Simple real UI remains required | blocked |

## Observed local evidence

- At c8f66c79a, Full and Simple each passed 432 tests with no failures, errors or skips. The final narrow Main and diagnostics cohort passed all 25 tests. The RED runs reproduced duplicate suppression, stale state and disabled feedback defects before the fixes.
- At 2099cbb, one combined Gradle run passed static analysis, Full and Simple app lint, service lint, and all four debug APK assembly tasks: 929 tasks, build successful in 8 minutes 8 seconds.
- Architecture validation passed with 21 existing findings and no new or worse findings. Locked Cargo metadata resolved all 116 workspace crates. The native inputs were checked against their producer and all 16 input hashes matched.
- The Android test host now uses FrameLayout so Material can find its ripple container. Test bodies, assertions and feedback recording overrides did not change.
- Local receipts and logs are under `.artifacts/haptic-feedback/`: `combined-unit-summary.json`, `combined-gates/`, `host-fix-gates/`, `host-fix-scope.json`, and `apk-receipt-2099cbb49.json`. These generated files are not committed.
- The final API 37 run used a standard Pool_1 lease and a read-only checkout at the exact APK source commit, 2099cbb. Full and Simple each passed eight instrumented tests, with no failures, errors or skips. Full passed back/reopen, Off plain restart, System plain restart and Arabic font 200 percent option checks. Root and the independent device reviewer checked the real Full screenshots.
- `device-api37-2099cbb49-popup-choice/` contains the exact source and installed APK hashes, all 16 test results, four Full UI results, plain launch arguments, and restoration receipts. Locale, font, size and density match the original values. The crash buffer is empty. The overall run remains failed because Simple cannot finish startup with the old private bundle.
- After explicit approval on 2026-10-18, the golden specialist recorded only the six InstalledReadability Russian maximum-font PNG files and eight SimpleHome PNG files. Full record passed three tests; Simple record passed eight tests. Strict verification of the complete families passed 12 Full and nine Simple tests, with no failures, errors or skips. The source was e050b0af5; production code was unchanged from the device source, 2099cbb.
- The specialist and an independent read-only reviewer inspected all 14 fresh images against their old baselines. They found no defects in maximum-font, RTL, dark, running or completed layouts. SHA-256 checks of all 420 existing fixtures confirmed exactly 14 approved changes and 406 unchanged files. The excluded simpleHomeReportCompletedMaximumFont PNG is byte-identical. The fixture-only commit is f12ef96a85d6e04ab859c4f9c8a888a42e05e073.
- `approved-goldens/` contains the four canonical task logs, archived JUnit XML and counts, and `scope-verification.json`. Build outputs were removed by concurrent external cleanup before this run; retained device receipts establish the earlier APK checks. The screenshot tasks rebuilt their inputs and passed after that cleanup.

## Historical Simple input blocker and revised UI acceptance

The genuine local bundle is 4735 bytes, SHA-256 `9ed07fb571c49434e75729f781d19785e6054c773e47600c4302eff920c7140a`. Its AWG profile has one IPv4 host address. `SingBoxAwgNetworkPolicy.isDualStackHostAddresses` requires one IPv4 /32 and one IPv6 /128. The parser reports `INVALID_NETWORK_POLICY` for `address`, and `ConfigSeeder` correctly refuses a required bundle with skipped profiles.

The build task checks only that the file exists and is nonempty. It does not establish runtime compatibility. A bounded read-only check found 16 assets and three distinct contents across registered worktrees. All genuine copies have the same old IPv4-only profile; the other two contents match CI fixtures and were excluded from real acceptance. No private endpoints, addresses or credentials were printed, and the input was not changed. A current genuine bundle with an assigned IPv6 address is required.

A separate read-only source review found that an ordinary successful Simple reseed preserves the haptic preference. ConfigSeeder changes only its explicit settings fields; the relay activation path uses the current settings builder. The existing one-time pre-release reset is a separate path. This source review does not replace the missing real Simple restart check.

The user has now confirmed that no current genuine bundle is available and authorized completion without it. Use `scripts/fixtures/embedded-relay-bundle/ci-fixture.json` for the local debug build. Its SHA-256 is `cadaff4fd5daa0cfeec5182e428a3207d926dd7ea62b917edadc21876a92194c` and its size is 1932 bytes. This is the existing CI fallback, not a new production profile. The previous private asset is preserved locally with mode 0600. Neither asset is tracked or published. Production readiness checks remain active. Device acceptance covers the real UI and plain restart; it does not cover real relay connections.

## Remaining gates

- Complete Full and Simple real UI checks with the authorized CI fixture under the command-scoped emulator lease. Use plain restarts without automation extras to verify saved Off and System values.
- Fetch and rebase, repeat the applicable combined-tree gates, integrate main, push, and inspect hosted CI for the exact published SHA.
- Device tests count platform calls. They do not establish tactile quality on physical hardware.
- Runtime failure events do not contain a command ID. Authority snapshots distinguish sequential starts; they do not add protocol-level correlation to late native events.

## Publication

UIX-1792259356008226 owns independent review, combined gates, rebase, authorized main integration, push and exact-source hosted CI inspection. Main has not been changed or pushed. Hosted CI has not validated this feature SHA.
