---
task_id: RLY-1786618247484998
change: add-safe-imported-profile-preflight
commit_sha: null
local: required
local_evidence: Targeted app/service tests, authorized Roborazzi verification, Android lint, staticAnalysis, architecture health, strict OpenSpec validation, task validation, and diff checks passed on the job branch before rebase.
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: required
artifact_evidence: null
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

Local, hosted-CI, physical-device, and APK artifact evidence are separate gates. The change cannot be archived while any required category remains unobserved or while any requirement row is not passed against the exact implementation commit.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-PREFLIGHT-ACTION | UIX-1786618555696038 | Compose semantics test passed; the authorized populated/imported light/dark fixtures were recorded, reviewed, and verified | passed-local |
| REQ-PREFLIGHT-SERVICE-ISOLATION | SVC-1786618555646864 | Both interlock race orderings and VPN/proxy coordinator regressions passed; physical service-start race remains required | passed-local |
| REQ-PREFLIGHT-EPHEMERAL-RUNTIME | SVC-1786618555663036 | Service test observed one ephemeral loopback runtime, one TCP probe, no UDP probe, and no retry; Pixel 7 trace remains required | passed-local |
| REQ-PREFLIGHT-NON-MUTATION | RLY-1786618555627276 | Projection equivalence and ViewModel tests passed with repository/profile/credential stores unchanged | passed-local |
| REQ-PREFLIGHT-CLEANUP | SVC-1786618555663036 | Success, busy, timeout, and caller-cancellation cleanup tests passed; device resource inspection remains required | passed-local |
| REQ-PREFLIGHT-TRUTHFUL-RESULT | UIX-1786618555680732 | Typed ViewModel and Compose result-state tests passed; success copy is limited to test-target reachability | passed-local |
| REQ-PREFLIGHT-PRIVACY | SVC-1786618555663036 | Projection/preflight tests use structured credentials and typed outcomes; no secret-bearing result or UI state is exposed | passed-local |
| REQ-PREFLIGHT-COMPATIBILITY | UIX-1786618555680732 | Existing proxy-import and import-confirmation UI suites passed, including invalid and unchecked imports | passed-local |

## Required local evidence

- Exact RED and GREEN commands for every sequential TDD cycle, with each RED failing for the intended missing behavior.
- Targeted app projection, import-confirmation ViewModel, Compose, service preflight, and interlock suites from `tasks.md`.
- Profile-import screenshot verify output; any recorded baseline must have explicit fixture-family authorization and a reviewed semantic diff.
- `./gradlew :app:lintGithubFullDebug :core:service:lintDebug` for all nine locale sets and Android lint.
- `./gradlew staticAnalysis`.
- `python3 scripts/ci/check_architecture_health.py`.
- Strict OpenSpec validation, task portfolio validation, generated board freshness, `git diff --check`, and final staged-diff review.

## Observed local evidence

- The targeted app suite for `com.poyka.ripdpi.proxyimport.*` and `com.poyka.ripdpi.ui.screens.proxyimport.*` passed.
- The preflight, interlock, proxy coordinator, VPN coordinator, and service-session module tests passed.
- The narrow `ProfileImportConfirmScreenshotTest` Roborazzi verify task passed after the explicitly authorized four-fixture update; empty-state fixtures were unchanged.
- `:app:lintGithubFullDebug`, `:core:service:lintDebug`, `staticAnalysis`, architecture health, strict OpenSpec validation, task generation/validation, and `git diff --check` passed.
- No physical Pixel 7 was connected during local verification; `adb devices -l` exposed only `emulator-5554`, so device evidence remains unobserved.

## Required external evidence

- Hosted CI for the exact pushed commit SHA; local results cannot satisfy `remote_ci`.
- A connected Pixel 7 run using an owner-controlled supported relay and controlled failure profile. Capture one attempt only, no UDP probe/retry, truthful UI states, halted service admission, unchanged durable configuration, and absence of a remaining listener/native handle/job.
- A built `githubFullDebug` APK for the exact commit, with package/version/variant identity and SHA-256 recorded before device installation; a source-only Gradle compile does not satisfy `artifact`.
