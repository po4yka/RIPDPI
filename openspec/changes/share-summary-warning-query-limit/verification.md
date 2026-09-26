---
task_id: DGN-1790431550325964
change: share-summary-warning-query-limit
commit_sha: null
local: required
local_evidence: Selected-session summary test failed before the fix (1 of 9 tests); after the fix both module unit suites passed (diagnostics-data 65/65, diagnostics 1427/1427) with -Pripdpi.skipNativeBuild=true. A review fix adds fake ordering and its unsorted-input test (focused 3/3 passed). Diagnostics-data detekt passed. Diagnostics detekt remains blocked by six MaxLineLength findings in the pre-integration base; task and strict OpenSpec validation passed.
remote_ci: required
remote_ci_evidence: Pending branch integration and CI run.
device: not_applicable
device_evidence: Query behavior is verified in local Room and summary unit tests.
artifact: not_applicable
artifact_evidence: No distributable artifact is produced by this query fix.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SHARE-WARNINGS-FILTER-BEFORE-LIMIT | DGN-1790431615793563 | Red-before-green share test; selected/live summary, unsorted fake, and Room warning query tests passed | passed |
