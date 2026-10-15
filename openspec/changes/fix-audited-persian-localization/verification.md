---
task_id: UIX-1792059951283875
change: fix-audited-persian-localization
commit_sha: null
local: required
local_evidence: Resource regressions 72 PASS; ten-locale parity and export PASS; architecture 21 baseline and zero new or worsened indicators; LoC PASS; scoped ktlint PASS; locked Cargo metadata PASS. Baseline JVM RED 13 tests with 12 expected failures. First combined JVM run passed 89 of 92 tests, including all 14 new behavior tests, 68 MainViewModel tests, four Support/Pause renders and two Home renders. Strategy render retry passed all four tests after a test-only correction for nested scroll and unbounded semantics precision; normal and narrow images inspected. Full gates remain pending.
remote_ci: required
remote_ci_evidence: Await the published source SHA and hosted CI.
device: not_applicable
device_evidence: JVM behavior and production render checks own this presentation change. Physical-device and carrier acceptance are separate.
artifact: not_applicable
artifact_evidence: No signed release artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-FA-COPY | UIX-1792060147847428 | Exactly 121 corrected keys; 72 resource tests PASS; baseline new regressions reproduce 103 failures. Four Support/Pause renders PASS; warning and named recovery action inspected at normal and large text. | Resource checks passed |
| REQ-FA-STRATEGY | UIX-1792060148876253 | Five Strategy behavior tests PASS, including raw grouping, DNS lane and retained locale projection. Four Strategy/Approach production render tests PASS; images inspected at normal and large text. | Behavior checks passed |
| REQ-FA-HOME | UIX-1792060150290271 | Nine Home behavior tests, two Home production renders and 68 MainViewModel tests PASS. Normal and 360 dp with 1.5 font scale images inspected. | Passed |
| REQ-FA-PARITY | UIX-1792060152291884 | Full and Simple tests, lint, static analysis, locale/export, Roborazzi, architecture, Cargo and review | Pending |

Publication belongs to UIX-1792060153763952. Main integration, push and hosted CI are explicitly authorized. No golden fixture update is authorized. The confirmed input is 121 resource keys and six code groups at a6b286e21ebf52d4d8901c63481b304122a7556d. Exclude uncertain terms, unused stageAnnouncement and unrendered stage.headline.
