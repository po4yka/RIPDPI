---
task_id: UIX-1791875091386561
change: fix-audited-spanish-localization
commit_sha: 74a58feb24bd017b4527e28ef08c0289ccd32f3a
local: passed
local_evidence: All 2459 app and 2230 service unit tests passed without failures or skips. Resource contracts, parity, export, static analysis, lint, architecture, file limits, locked Cargo metadata, independent review, and six inspected Spanish UI renders passed.
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: Resource and local rendered UI checks own this change. Physical-device acceptance is not claimed.
artifact: not_applicable
artifact_evidence: No signed APK or release artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-ES-MEANING | UIX-1791875198288208 | Five Spanish resource tests and six inspected UI renders | Passed |
| REQ-ES-RESULTS | UIX-1791875199191066 | Spanish/English enum, snapshot, SNI quantity, and unknown-value tests | Passed |
| REQ-ES-NUMBERS | UIX-1791875200254173 | Spanish app on English system, notification content, boundaries, and language-switch tests | Passed |
| REQ-ES-PARITY | UIX-1791875201228206 | Ten-locale contracts, export, lint, static analysis, architecture, and full app/service tests | Passed |

## Boundaries

No protocol, raw evidence, stored code, dependency, baseline, or golden fixture changes. Hosted CI and native-speaker acceptance are separate from local checks.

## Scope

Corrected all 125 active and four unused resource findings from the audit at 2f6803083d74880f3c47e209aa83dfe9d6953e33. Added 19 strings and two plurals in all ten locales. Known DoQ, bootstrap, BYOH, SNI, and snapshot labels use resources. Visible diagnostic and notification numbers use the selected app locale. Unknown values and technical identifiers remain exact.

## Local commands and results

- `python3 scripts/ci/test_spanish_localization_regressions.py`: five passed. The four text checks report 123 failures against the audit baseline.
- Existing Russian and Chinese resource suites: four and five passed.
- `scripts/ci/check-locale-parity.sh` and `scripts/ci/check-translation-export.sh`: passed. Source totals are 4926 app entries, 11 service entries, 15 Simple strings, and 4913 manifest keys.
- Targeted app and service Kotlin tests: 22 passed without failures, errors, or skips.
- `:app:testGithubFullDebugUnitTest`: 2459 passed without failures, errors, or skips.
- `:core:service:testDebugUnitTest`: 2230 passed without failures, errors, or skips.
- `staticAnalysis :app:lintGithubFullDebug :core:service:lintDebug`: passed. Static analysis also checks the Simple variant.
- Six native Compose renders were inspected: sensitive support settings, SNI, and BYOH at normal and 1.5 font scale. Captures are in the ignored `app/build/compose-previews/renders/` directory. Warning text and translated captions have no visual overflow. SNI value chips use the existing horizontal scroll row.
- `python3 scripts/ci/check_architecture_health.py`: no new, worsened, or stale indicators.
- `python3 scripts/ci/check_file_loc_limits.py`: no violations or baseline growth.
- `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1`: passed; 116 workspace members and 910 packages.
- Independent source review, task contracts, staged diff review, commit hooks, and `git diff --check`: passed.

All Gradle commands used build-gate, one worker, nativeCpuBudget=4, no-daemon, and skipNativeBuild=true. Validation linked the existing verified libXray artifact directory. No native APK or device acceptance is claimed. No tests or quality gates were removed.

## Integration evidence

The local main checkout had 18 unpublished readability commits. Origin/main advanced independently to 7108f7ac5dc8b2457de403b99455e58714825bc7. The job branch merged that remote change, then ran `git rebase --rebase-merges origin/main`. All original local commit IDs and the source correction commit aa28e5aaa were retained. Main at 2f6803083 remains an ancestor of the verified job head.

The complete Gradle gate passed before integration and passed again on the rebased head above. Generated BuildConfig reports 74a58feb2 for the second run. The first UI test draft had an ImmutableList compile error, and one test had an overlong line; both test defects were corrected before the passing gates.

## Hosted CI boundary

Check the exact pushed main head after publication. Hosted CI remains required for task closure. Native-speaker review and physical-device acceptance are not claimed.
