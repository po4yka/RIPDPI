---
task_id: DGN-1790433240059940
change: redact-network-snapshot-identities
commit_sha: null
local: required
local_evidence: Two serialization regression tests pass. All 1436 diagnostics unit tests pass with zero failures or skips. Module detekt passes using ripdpi.skipNativeBuild=true.
remote_ci: required
remote_ci_evidence: Pending branch integration and CI run.
device: not_applicable
device_evidence: Pure snapshot projection is covered by local unit tests; Android capture integration remains unchanged.
artifact: required
artifact_evidence: NetworkSnapshotPrivacyTest serializes Wi-Fi and cellular models and asserts raw identifiers and addresses are absent while counts and coarse signal data remain.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SNAPSHOT-NETWORK-IDENTITIES | DGN-1790433363165985 | Two serialized snapshot tests and full module unit gate | Pass for new captures |

## Residual

Existing `network_snapshots.payloadJson` rows are not rewritten by this capture-time fix. History retention can be disabled, so they may remain on upgraded installs. A separate data migration is required. `publicIp` and `publicAsn` also remain raw pending a product decision about external resolver behavior; the latter can contain provider names. Native binaries were skipped for the JVM gate, so this check does not verify APK assembly or device behavior.
