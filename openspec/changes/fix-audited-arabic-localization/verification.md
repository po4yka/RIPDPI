---
task_id: UIX-1792040423920590
change: fix-audited-arabic-localization
commit_sha: null
local: required
local_evidence: Planned resource regressions, app tests, lint, static analysis, Arabic renders, Roborazzi, architecture and locale contracts.
remote_ci: required
remote_ci_evidence: Await successful hosted CI on the published source.
device: not_applicable
device_evidence: JVM behavior and render checks cover this app presentation change. No physical-device or carrier acceptance is claimed.
artifact: not_applicable
artifact_evidence: No signed release artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AR-COPY | UIX-1792040522331980 | Resource semantic regressions and Arabic production renders | Pending |
| REQ-AR-HOME | UIX-1792040522854476 | Production Home projection covers all eight groups and raw fallback | Pending |
| REQ-AR-RETAINED | UIX-1792040522854476 | Same raw result follows EN to Arabic to EN | Pending |
| REQ-AR-PARITY | UIX-1792040523369123 | Locale/export, placeholders, lint, Roborazzi and independent review | Pending |

Publication belongs to UIX-1792040523843403. Main integration, push and hosted CI have explicit user authorization. The user approved only `app/src/test/screenshots/com.poyka.ripdpi.ui.screenshot.DiagnosticsMeasurementsScreenshotTest.health_rtl.png` after review. No other golden or quality baseline may change.
