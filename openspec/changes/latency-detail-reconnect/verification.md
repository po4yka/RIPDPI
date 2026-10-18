---
task_id: UIX-1792294575616838
change: latency-detail-reconnect
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
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-LDR-DETAIL | UIX-1792300268446369 | Focused host and real APK latency detail checks | required |
| REQ-LDR-SNAPSHOT | UIX-1792300268446369 | Selected snapshot retention and reopen regression | required |
| REQ-LDR-ACCESS | UIX-1792300269982685 | Normal/reduced/off, real TalkBack, Back/focus, RTL/font2 | required |
| REQ-LDR-RECONNECT | UIX-1792300269490435 | Actual service/native session and before/after fixture traffic | required |
| REQ-LDR-VERIFY | UIX-1792300270507626 | Combined gates, APK hashes and exact published hosted CI | required |

## Planned gates

Focused meaningful RED/GREEN; affected Home/health/theme host suites; unchanged
screenshot fixtures; app and service locale lint; staticAnalysis; independent
code review; actual API37 leased emulator acceptance; architecture health;
locked Cargo metadata; exact published CI. Production telemetry, test-only
presentation snapshots and real reconnect network receipts remain distinct.
