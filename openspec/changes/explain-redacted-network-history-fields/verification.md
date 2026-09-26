---
task_id: UIX-1790434646817990
change: explain-redacted-network-history-fields
commit_sha: null
local: required
local_evidence: Four session mapper regressions and two connection-history regressions pass after observed red failures. All 1926 GithubFullDebug app unit tests pass with zero failures or skips. App detekt, app lint, and service lint pass using ripdpi.skipNativeBuild=true.
remote_ci: required
remote_ci_evidence: Pending branch integration and CI run.
device: not_applicable
device_evidence: String and mapper behavior are covered by JVM tests and Android lint.
artifact: required
artifact_evidence: Mapper tests assert stored redaction marker and address counts, hidden legacy cellular and Wi-Fi identities, private DNS hostname and ASN, and unknown sentinel behavior. Connection history tests assert hidden legacy DNS/private DNS/public IP and explicit not-stored markers for new snapshots. Existing ViewModel test confirms visibility toggle.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-NETWORK-HISTORY-REDACTION | UIX-1790434903545876 | Session and connection mapper tests, full app unit gate | Pass |

## Residual

Legacy raw database rows are handled by a separate data migration. A live-only raw detail flow is not added. Native binaries were skipped for the JVM and lint gates, so APK assembly and device behavior remain unverified.
