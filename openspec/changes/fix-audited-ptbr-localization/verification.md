---
task_id: UIX-1791978914456411
change: fix-audited-ptbr-localization
commit_sha: null
local: required
local_evidence: Full and Simple unit tests, app and service lint, staticAnalysis, resource contracts, Portuguese renders, Roborazzi, architecture health and locked Cargo metadata pass. Combined checks after rebase remain required.
remote_ci: required
remote_ci_evidence: Main publication and exact-SHA hosted CI remain pending.
device: not_applicable
device_evidence: This change uses JVM behavior and render checks. No physical-device or carrier-network acceptance is claimed.
artifact: not_applicable
artifact_evidence: No signed release artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-PTBR-COPY | UIX-1791979413201100 | All 14 audited keys and two related DNS captions corrected. Nine behavior RED/GREEN cycles and 50 resource tests pass. The existing Noise help key is clarified in all ten locales. | Pass |
| REQ-PTBR-CONTEXT | UIX-1791979414097189 | The API32 production History factory follows EN to pt-BR to EN with an English application context. All thirteen captions change; raw state and fingerprint stay unchanged. Semantic RED and GREEN observed. | Pass |
| REQ-PTBR-RETAINED | UIX-1791979414826984 | Three retained production Routes pass EN to pt-BR to EN checks with frozen source data, raw selected details, stable filters, IDs, sensitive visibility and load counts. All three semantic RED cases were observed before the source fix. | Pass |
| REQ-PTBR-NETWORK | UIX-1791979415687207 | Three production Compose tests pass for both generated sentinels, custom summaries, known fingerprints, unchanged raw records and 360dp large text. Semantic RED observed; four images inspected. | Pass |
| REQ-PTBR-PARITY | UIX-1791979416938305 | All-locale parity, export, lint, combined tests, Portuguese renders, screenshot comparison and independent review pass. | Pass |

## Local gates

- Unfiltered `:app:testGithubFullDebugUnitTest`: 2565 tests pass, zero failures, errors or skips.
- Unfiltered `:app:testGithubSimpleDebugUnitTest`: 2734 tests pass, zero failures, errors or skips.
- `:app:lintGithubFullDebug`, `:app:lintGithubSimpleDebug`, `:core:service:lintDebug` and `staticAnalysis`: the combined command passes.
- `python3 -m unittest discover -s scripts/ci -p 'test_*localization_regressions.py'`: 50 tests pass. Translation export discovery: three tests pass. The export manifest stays unchanged with 5002 keys.
- `scripts/ci/check-locale-parity.sh`: app, Simple and service resources match in all ten locales. Settings labels and option order match.
- Portuguese production component renders: twelve tests pass. Fourteen component images and four Detection History images were inspected. Audited text is readable at 420dp/fontScale 1 and 360dp/fontScale 1.5. The visible resume action invokes its callback.
- `:app:verifyRoborazziGithubFullDebug` and `:app:verifyRoborazziGithubSimpleDebug`: pass with the real bundle. Full has 297 executed cases and one existing ignored RTL gallery case. Simple has 281 executed cases and 29 existing skips: 28 Full-only cases and the same ignored RTL gallery case. Skipped cases do not prove coverage. No screenshot baseline changed.
- Architecture health: 21 current and 21 baseline indicators, zero new or worsened indicators. File LoC limits and standalone ktlint pass. No suppression or baseline was added.
- `cargo metadata --manifest-path native/rust/Cargo.toml --locked`: passes with 116 workspace members and 910 resolved packages.
- Independent resource, combined code and final test reviews are clean. The existing ModeEditor Noise assertion now matches the corrected English source. Test helpers preserve all assertions and raw-data checks.

## Test inputs and limits

A real Simple relay bundle was recovered from successful push CI run `38553074341`, artifact `11888857403`. The repository verifier matched source `af004adb89dbf443be8f190a3cead0e275026b24` and all six APK checksums. The extracted asset differs from the CI fixture. It is stored only in the ignored local asset path, with mode 0600. No private configuration is part of this change.

The new component render tests use SDK35, as existing French and German tests do. The first SDK37 attempt failed before the test bodies because the pinned Espresso input adapter calls an absent `InputManager.getInstance` method. Production SDK, dependencies and test inventory are unchanged.

JVM gates use the repository-owned build mode without Rust native tasks and a verified real Xray AAR. These checks do not claim native, physical-device or carrier acceptance. Hosted CI remains required.

Legacy entries with fingerprint `unknown` and a real ISP name equal to `Unknown` or `Unknown network` cannot be distinguished from generated fallbacks without new provenance. The presentation guard treats that reserved pair as generated. Storage and export stay unchanged.

## Publication

Hosted source CI and final main state belong to `UIX-1791979418293451`. Publication and exact-SHA hosted CI remain pending. Local gates do not replace hosted CI.
