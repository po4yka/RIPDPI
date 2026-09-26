---
task_id: DGN-1790436692670903
change: redact-legacy-network-snapshots
commit_sha: null
local: required
local_evidence: Production-builder 13→14 upgrade regression passed after an observed red failure. Diagnostics-data unit tests 66/66 and diagnostics unit tests 1443/1443 passed with no failures or skips. Diagnostics-data detekt and ktlint passed. Strict OpenSpec and architecture health passed.
remote_ci: required
remote_ci_evidence: Pending branch integration and CI run.
device: not_applicable
device_evidence: Room upgrade behavior is covered by a production-builder Robolectric test.
artifact: required
artifact_evidence: Room KSP generated version 14 schema JSON. Its table structure equals version 13; only version and identity hash differ. Upgrade regression covers 129 Wi-Fi rows across batch boundaries, one cellular row, one production-JSON row with omitted nullable fields, six malformed rows, and unrelated event preservation.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-LEGACY-SNAPSHOT-REDACTION | DGN-1790436775581642 | Production-builder 13→14 upgrade regression, 66 data and 1443 diagnostics tests | Pass |

## Residual

Public IP and ASN are retained pending a separate product decision. `bypass_usage_sessions.publicIp`, `telemetry_samples.publicIp`, and diagnostic context payloads are separate persistence paths and remain outside this snapshot migration. Native binaries were skipped for JVM gates; APK assembly and device upgrade remain unverified.
