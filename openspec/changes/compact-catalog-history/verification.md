---
task_id: UIX-1792213600805378
change: compact-catalog-history
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: required
artifact_evidence: null
deployment: not_applicable
deployment_evidence: No deployment is owned by this app presentation change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-CH-CATALOG | UIX-1792213814900821 | Catalog behavior tests, screenshots and emulator before/after | Required |
| REQ-CH-HISTORY | UIX-1792213815435833 | Source-vs-filter projection tests, CTA and actual route checks | Required |
| REQ-CH-ACCESS | UIX-1792213816018346 | Full/Simple tests, theme, locale lint/parity, font2 and RTL checks | Required |
| REQ-CH-ACCEPTANCE | UIX-1792213816520760 | Exact APK manifests and emulator evidence | Required |
| REQ-CH-ACCEPTANCE | UIX-1792213817008369 | Independent review, rebase, architecture, Cargo, main/push and CI | Required |

## Planned evidence

Use one serially built combined tree. Run catalog and history behavior tests,
all theme tests, app/service locale lint, static analysis and affected screenshot
comparisons. Do not record PNG without family-scoped approval. Build real native
Full and Simple APKs and record SHA256 plus source. On a leased API37 emulator,
check normal and 2.0 font, compact light/dark and a real RTL locale. Validate
profile disclosure/cancel/recovery and real history navigation without network
or automatic service/scan actions. Record screenshots, fresh XML and crash logs.
