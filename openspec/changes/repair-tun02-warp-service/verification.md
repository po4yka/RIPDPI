---
task_id: TRN-1794384538458949
change: repair-tun02-warp-service
commit_sha: ec4f13320a272b945bf2a0497d02fcea545d73c9
local: required
local_evidence: Baseline audit reproduced nine failures; repair verification is pending.
remote_ci: required
remote_ci_evidence: No repair commit has been pushed.
device: required
device_evidence: UI and JNI acceptance is pending; no physical-device permission is assumed.
artifact: required
artifact_evidence: Repair artifact build is pending.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-WARP-F1 | TRN-1794384922559287 | Implementation and red/green evidence pending | required |
| REQ-WARP-F2 | TRN-1794384940808206 | Implementation and red/green evidence pending | required |
| REQ-WARP-F3 | TRN-1794384955046919 | Implementation and red/green evidence pending | required |
| REQ-WARP-F4 | TRN-1794384974171806 | Implementation and red/green evidence pending | required |
| REQ-WARP-F5 | TRN-1794384989760526 | Implementation and red/green evidence pending | required |
| REQ-WARP-F6 | TRN-1794385010176922 | Implementation and red/green evidence pending | required |
| REQ-WARP-F7 | TRN-1794385033249122 | Implementation and red/green evidence pending | required |
| REQ-WARP-F8 | TRN-1794385053055273 | Implementation and red/green evidence pending | required |

## Evidence boundaries

The audit baseline is ec4f13320a. Its host regressions do not prove Android,
Cloudflare interoperability, or complete protocol conformance. No real
credentials, provider API probes, physical devices, or root activation are
planned. Native loopback and emulator evidence will identify exact sources
and commands. Each finding has a separate fix commit; record hashes here.
