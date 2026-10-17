---
task_id: UIX-1792213600805378
change: compact-catalog-history
commit_sha: e82cf62f940a427bfe1d6b289440e71554799704
local: passed
local_evidence: "Final combined e82cf62f: Full 146 and Simple 146 targeted tests passed with no failures, errors or skips; staticAnalysis and Full/Simple/service lint passed (757 tasks). Approved golden e82cf62f: Full record/verify and Simple verify each 14 passed; other 406 PNG unchanged. See docs/audits/2026-10-17-catalog-history.md."
remote_ci: required
remote_ci_evidence: null
device: passed
device_evidence: "API37 exclusive pool, exact APK source 8b8849ba7, 17 passed with 0 failures/skips. All 59 PNG reviewed, 63 stable capture pairs, exact settings restored in 3 samples, crash empty and wrapper exit 0. Commit e82cf62f changes only 14 approved test PNG. See docs/audits/2026-10-17-catalog-history.md for hashes, raw paths and explicit coverage limits."
artifact: passed
artifact_evidence: "Real native ARM64 Full and Simple APKs built from 8b8849ba7c2885f7bcfe910dbfb9c00914b2a0dc; 13 ELF each. SHA256 Full c603114328195067cca77804aaae982c210c59ff11ec5633043f9223ce07e802, Simple 05695b923d77fb6939829a33f0cf988e7030a9777f6f6d3a873bf04e7c9b78a1. Immutable manifest and limits recorded in the audit report."
deployment: not_applicable
deployment_evidence: No deployment is owned by this app presentation change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-CH-CATALOG | UIX-1792213814900821 | 16 catalog behavior checks, 13 approved goldens and four device configurations | Passed |
| REQ-CH-HISTORY | UIX-1792213815435833 | Source-vs-filter tests, 3 real NavController regressions and device CTA/clear cycle | Passed |
| REQ-CH-ACCESS | UIX-1792213816018346 | 146 Full and 146 Simple tests, locale lint, theme and font2/RTL checks | Passed |
| REQ-CH-ACCEPTANCE | UIX-1792213816520760 | Exact real APK manifests, 17/0/0 API37 matrix and restoration | Passed |
| REQ-CH-ACCEPTANCE | UIX-1792213817008369 | Independent review, rebase, architecture, Cargo, main/push and CI | Required |

## Planned evidence

Use one serially built combined tree. Run catalog and history behavior tests,
all theme tests, app/service locale lint, static analysis and affected screenshot
comparisons. Do not record PNG without family-scoped approval. Build real native
Full and Simple APKs and record SHA256 plus source. On a leased API37 emulator,
check normal and 2.0 font, compact light/dark and a real RTL locale. Validate
profile disclosure/cancel/recovery and real history navigation without network
or automatic service/scan actions. Record screenshots, fresh XML and crash logs.
