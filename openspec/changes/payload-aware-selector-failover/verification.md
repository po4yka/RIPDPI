---
task_id: TRN-1792659369802828
change: payload-aware-selector-failover
commit_sha: null
local: required
local_evidence: null
remote_ci: blocked
remote_ci_evidence: "Final CI 39772247126 on b0b48501b738295bc0597ab5fb6815c0975d477f passed full JVM, staticAnalysis and native builds but failed API33 job119814631151 and API35 job119814630708 service-start instrumentation. Cold-start regression remains open. Coverage job119803343421 separately failed dependency retrieval with Maven Central HTTP429 before tests. No full CI pass is claimed."
device: not_applicable
device_evidence: No device capture or native wire contract changes; existing protected relay lifecycle is reused. Filtered-network and physical-device acceptance are not claimed.
artifact: required
artifact_evidence: null
deployment: not_applicable
deployment_evidence: No deployment or live control endpoint is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-PAYLOAD-IMPORT | TRN-1792659466914740 | Strict importer and persistence tests, exact shared schema mirror | Pending |
| REQ-PAYLOAD-VERIFY | TRN-1792659467442022 | Real local HTTP payload verifier tests through candidate SOCKS routing | Pending |
| REQ-PAYLOAD-STABILITY | TRN-1792659467948091 | Virtual-clock thresholds, cooldown, backoff and cancellation tests | Pending |
| REQ-PAYLOAD-SAFETY | TRN-1792659468436110 | Network/manual fences, no-direct selection and affected module gates | Pending |

## Acceptance incident — cold service startup

Final-source instrumentation exposed a deferred catalogue-loading deadlock that
JVM tests with eagerly seeded repositories did not cover. Closure was reverted;
acceptance is reopened. Required repair: validate and publish the persisted
catalogue before waiting for selector subscriptions, propagate unreadable-storage
errors, and verify empty cold starts plus persisted selectors without weakening
existing corruption or lifecycle tests. The complete required CI matrix must pass
before closing this change again.
