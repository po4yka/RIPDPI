---
task_id: UIX-1791958951401122
change: fix-audited-french-localization
commit_sha: abb49ce0daca560d6acd7d606d4650f50e44793a
local: passed
local_evidence: 40 resource tests, all-locale parity, 2546 unfiltered app tests, 16 inspected French PNGs, 297 executed Full screenshot tests, app Full and Simple lint, service lint, staticAnalysis, architecture health and locked Cargo metadata passed. One pre-existing RTL screenshot test is ignored; local Simple screenshots need an authentic absent relay bundle.
remote_ci: passed
remote_ci_evidence: Source abb49ce0daca560d6acd7d606d4650f50e44793a is published on main. All seven hosted workflows passed; CI run 38547366408 and ci-required passed, including all five emulator jobs and all Full and Simple release builds. Exact links are listed below.
device: not_applicable
device_evidence: Presentation changes use resource, behavior and render checks. No physical-device acceptance is claimed.
artifact: not_applicable
artifact_evidence: No signed APK or release artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-FR-COPY | UIX-1791959158352440 | French resource regressions and exact 138-key comparison | Passed |
| REQ-FR-DISPLAY | UIX-1791959159362391 | Factory behavior tests, retained samples and raw-data checks | Passed |
| REQ-FR-DEBUG | UIX-1791959160232645 | Debug prose and decimal locale regressions | Passed |
| REQ-FR-PARITY | UIX-1791959161776824 | All-locale parity, manifest, app/service lint and rendered UI | Passed |

## Published source evidence

Source commit `abb49ce0daca560d6acd7d606d4650f50e44793a` was fast-forward integrated into main and pushed to origin. All seven workflows completed with success on this exact SHA on 2026-10-14:

- [CI and ci-required](https://github.com/po4yka/RIPDPI/actions/runs/38547366408): 47 jobs passed, 17 were skipped by the existing route conditions, and none failed. All five Android API 27/33/35/36/37 emulator jobs, Roborazzi, static analysis, coverage, all three debug builds and all GitHub/Play/F-Droid Full and Simple release builds passed.
- [Locale parity](https://github.com/po4yka/RIPDPI/actions/runs/38547366287).
- [Translation export](https://github.com/po4yka/RIPDPI/actions/runs/38547366331).
- [CodeQL](https://github.com/po4yka/RIPDPI/actions/runs/38547366443).
- [Secret Scan](https://github.com/po4yka/RIPDPI/actions/runs/38547366282).
- [Harness checks](https://github.com/po4yka/RIPDPI/actions/runs/38547366336).
- [Fleet fixtures](https://github.com/po4yka/RIPDPI/actions/runs/38547366272).

These receipts apply to the source SHA. The later evidence-only commit does not change production resources, code, tests or fixtures. Local Simple screenshots remain unverified because the authentic relay bundle is absent. Hosted results do not claim physical-device, carrier-network or native-speaker acceptance.
