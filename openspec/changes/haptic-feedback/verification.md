---
task_id: UIX-1792259264757428
change: haptic-feedback
commit_sha: 020d36fdc3e5becf53f8484f67ce100680281c71
local: passed
local_evidence: At f005bad646a56f36575c9eef27f12bb7f72d55f5, all 923 behavioral, storage, backup and CI fixture tests passed. Static analysis, Full and Simple locale lint, service lint and 21 strict screenshot tests passed. After rebase to 020d36fdc3e5becf53f8484f67ce100680281c71, production and build inputs are identical; architecture health, locked Cargo metadata and strict task and OpenSpec validation pass. All 16 affected bundle contract tests pass with zero failures, errors and skips.
remote_ci: required
remote_ci_evidence: null
device: passed
device_evidence: At f005bad646a56f36575c9eef27f12bb7f72d55f5, API 37 Full and Simple passed all 16 instrumented tests and eight real UI checks. Off and System survived plain restarts without automation extras. Both Arabic RTL controls were reachable at font 200 percent. Installed APK hashes match, device settings were restored, and no crash or ANR was found. The existing CI fixture supplied the local Simple debug asset.
artifact: passed
artifact_evidence: Four arm64 APKs were built and hashed at f005bad646a56f36575c9eef27f12bb7f72d55f5. Device installation matched the sealed hashes and sizes. Exactly 14 approved PNG files changed; 406 other fixtures are unchanged. All eight real device UI images were inspected by root and the device specialist.
deployment: not_applicable
deployment_evidence: No deployment is owned by this app preference change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-HAPTIC-PREFERENCE | UIX-1792259354389850 | Storage, defaults, backup and restart tests; Full and Simple real Off and System plain restarts | passed |
| REQ-HAPTIC-CONNECTION | UIX-1792259354945871 | Actual Running, acceptance, cancellation, equal retries, IO failure, restored state and deferred failure race regressions | passed |
| REQ-HAPTIC-RESULT | UIX-1792259354945871 | Detection and diagnostics once-only, lifecycle, Off, IO and cancellation tests | passed |
| REQ-HAPTIC-VALIDATION | UIX-1792259355458709 | 923 unit tests, 21 screenshot tests, lint, static analysis, 16 instrumented tests and eight real UI checks | passed locally; hosted CI required |

## Source and local evidence

- The compiler and device source is `f005bad646a56f36575c9eef27f12bb7f72d55f5`. Full and Simple each passed 432 behavioral tests, settings storage passed 12 tests, and backup plus CI fixture tests passed 47 tests. All 923 executions had zero failures, errors and skips. Earlier RED runs reproduced duplicate feedback, stale state and disabled feedback defects before the fixes.
- One combined Gradle run passed static analysis, Full and Simple app lint, service lint and all four debug APK assembly tasks: 929 tasks, build successful in 17 minutes 13 seconds. Strict Roborazzi verification passed all 12 InstalledReadability and nine SimpleHome tests, with zero failures, errors and skips.
- Native input verification matched all 16 files to producer `1993443f95232c1f7fd89e81891d350e93b6344d`. Locked Cargo metadata resolved all 116 workspace crates. Architecture health found 21 existing indicators and no new or worse findings.
- Rebase onto `7409cd0154234fee588bd148ed2b07e3365cc286` produced `020d36fdc3e5becf53f8484f67ce100680281c71`. The complete tree diff from the tested source contains only two upstream bundle test JSON files and three task documents. Production sources, build inputs, instrumented tests and screenshots are byte-identical. Device evidence remains attributed to its actual source; it is not relabelled as a new APK run.
- The independent reviewer found no material defects. Feedback eligibility, once-only consumption and authority/epoch checks remain intact. Off changes only the haptic gate. Reduced-motion and progress behavior remain unchanged.
- Logs, archived JUnit XML, counts, APK receipts and source-equivalence checks are retained under `.artifacts/haptic-feedback/test-bundle-acceptance/`. Generated evidence and local bundle assets are not committed.

## Device acceptance

The successful run used the standard Pool_1 command-scoped lease on API 37, arm64, pixel_10_pro_xl. Full and Simple each passed eight instrumented tests and four real UI checks: back/reopen Off, Off after plain restart, System after plain restart, and Arabic RTL with font scale 2.0. Each variant used three plain launches without automation extras. Root and the device specialist inspected all eight real UI PNG files and found no clipping or overlap in the preference, helper text or navigation.

Installed app APK hashes and sizes match the sealed inputs. Locale values, font scale 1.0, size 1344 by 2992 and density 480 were restored exactly. Restoration errors are empty; the crash buffer is empty; no new app ANR or fatal log matches were found. The lease was released.

Attempt 1 stopped before any tests because the installed ABI-split APK had versionCode 20000012 and the new universal debug APK had versionCode 12. Attempt 2 first used Android debug `install -r -d` under the lease, preserving app data. The unchanged runner then passed all tests. The setup failure remains in the evidence; no assertions were relaxed and no uninstall was used.

## Approved screenshots

The user approved exactly six InstalledReadability Russian maximum-font PNG files and eight SimpleHome PNG files, excluding simpleHomeReportCompletedMaximumFont. The golden specialist and independent reviewer inspected all 14 images. Canonical record and strict verification passed. SHA-256 checks of all 420 tracked PNG files confirmed 14 approved changes and 406 unchanged files, with dimensions preserved. The excluded maximum-font image is byte-identical. Receipts are retained in `.artifacts/haptic-feedback/approved-goldens/`.

## Local test bundle and evidence limits

The old genuine local bundle could not pass the current AWG dual-stack address policy. That historical startup failure was preserved. The user then confirmed that no current genuine bundle is available and authorized UI acceptance without one.

The local Simple debug build uses the existing `scripts/fixtures/embedded-relay-bundle/ci-fixture.json`, the same fallback used by CI. It is 1932 bytes with SHA-256 `cadaff4fd5daa0cfeec5182e428a3207d926dd7ea62b917edadc21876a92194c`. The previous private input was preserved locally with mode 0600. Both local asset copies remain ignored and are not published. Production parser and readiness checks remain active.

This proves real UI and preference persistence with explicit test data. It does not prove live relay connections, backend availability or tactile quality on physical hardware. Platform tests count haptic calls. Runtime failure events have no command ID; authority and epoch checks do not add protocol-level correlation to late native events.

## Remaining publication gates

The affected bundle contract gate passed on the rebased tree: 16 tests across RipdpiBundleContractTest, AwgDeployedTransportTest, AwgCohortFingerprintImportTest and CiRelayBundleFixtureTest, with zero failures, errors and skips. Integrate the job branch into main with fast-forward, push the authorized changes, and inspect hosted CI for the exact published SHA. Main and remote have not yet received this feature.
