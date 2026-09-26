---
task_id: DGN-1790430871310010
change: unlink-relay-profile-archive-identifiers
commit_sha: null
local: required
local_evidence: DiagnosticsArchiveRelayTraceExporterTest failed before the fix (8 tests, 1 failed) and passed after the fix with -Pripdpi.skipNativeBuild=true (8 tests); strict OpenSpec and taskctl validation passed.
remote_ci: required
remote_ci_evidence: Pending branch integration and CI run.
device: not_applicable
device_evidence: Archive projection is covered by local JVM tests; no device-only path changes.
artifact: required
artifact_evidence: Archive regression test opens two ZIP exports and verifies archive-local aliases, within-archive correlation, and absence of raw stable identifiers from report.json; existing relay health JSONL test verifies projected fields.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RELAY-ARCHIVE-UNLINKABLE | DGN-1790430977843442 | Red-before-green `DiagnosticsArchiveRelayTraceExporterTest`; two ZIP exports inspect JSONL and report JSON | passed |
| REQ-RELAY-ARCHIVE-UNKNOWN | DGN-1790430977843442 | Existing incomplete relay decision test passed in the focused 8-test gate | passed |
