---
task_id: ENG-1794637887235609
change: dpi20-remediation
commit_sha: null
local: required
local_evidence: Regression, affected-package tests, review, and integration gates pending.
remote_ci: not_applicable
remote_ci_evidence: Local acceptance is required for this correction; hosted CI is reported separately after push if available.
device: not_applicable
device_evidence: No physical-device or external-network acceptance is part of these local regression fixes.
artifact: not_applicable
artifact_evidence: No APK, native release artifact, or golden fixture update is requested.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DPI20-LIMIT | ENG-1794638035480946 | Kotlin and Rust count tests; direct planner limits | Pending |
| REQ-DPI20-VERSION | ENG-1794638038580454 | Full JSON conversion and direct v1/v2 wire tests | Pending |
| REQ-DPI20-RESPONSE | ENG-1794638041569104 | Session and actual loopback receive-path tests | Pending |
| REQ-DPI20-LIMIT | ENG-1794638042434161 | Independent review, strict Clippy, ci-gates, architecture health, locked metadata | Pending |
