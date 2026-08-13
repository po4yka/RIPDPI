---
task_id: RLY-1786618247484998
change: add-safe-imported-profile-preflight
commit_sha: e1a53419a7455b43156020b406114640a79e0780
local: required
local_evidence: Targeted app/service tests, authorized Roborazzi verification, Android lint, staticAnalysis, architecture health, strict OpenSpec validation, task validation, and diff checks passed on the job branch before rebase.
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: Partial on Pixel 7 (panther, Android 17): controlled failure was truthful, the transient listener stopped, no crash/socket/service remained, and durable hashes were unchanged; owner-relay success remains required.
artifact: required
artifact_evidence: githubFullDebug arm64-v8a APK for e1a53419a, package com.poyka.ripdpi 0.1.4 (20000012), SHA-256 02464f8f839e79341a331d353afae5a1c72b4ac95df1cae5e52f409208931f82.
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
- A physical Pixel 7 (`panther`, Android 17) was selected explicitly by serial. The exact arm64-v8a APK was installed without clearing app data.

## Observed Pixel 7 evidence

- A structurally valid non-routable VLESS/REALITY fixture opened the import-confirmation screen with separate enabled `Check profile` and `Add` actions.
- One check opened listener `127.0.0.1:38489`, reported that the test target was not reached and that the cause was not established, then logged one listener stop about ten seconds later.
- After completion, no `com.poyka.ripdpi` service, relay listening socket, Java/Kotlin crash, or native crash was observed.
- SHA-256 values for app-owned `files/datastore/app_settings.pb` and `databases/ripdpi.db` were identical before and after the repeated check.
- The successful owner-controlled relay scenario remains unobserved. The device gate is partial and the change remains active.

## Observed artifact evidence

- Commit: `e1a53419a7455b43156020b406114640a79e0780`.
- Variant/ABI: `githubFullDebug`, `arm64-v8a`.
- Package/version: `com.poyka.ripdpi`, `0.1.4` (`20000012`).
- APK SHA-256: `02464f8f839e79341a331d353afae5a1c72b4ac95df1cae5e52f409208931f82`.

## Required external evidence

- Hosted CI for the exact pushed commit SHA; local results cannot satisfy `remote_ci`.
- A connected Pixel 7 run using an owner-controlled supported relay and controlled failure profile. Capture one attempt only, no UDP probe/retry, truthful UI states, halted service admission, unchanged durable configuration, and absence of a remaining listener/native handle/job.
- A built `githubFullDebug` APK for the exact commit, with package/version/variant identity and SHA-256 recorded before device installation; a source-only Gradle compile does not satisfy `artifact`.
