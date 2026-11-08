---
task_id: DNS-1794142176893534
change: vpn05-dns-safety
commit_sha: null
local: required
local_evidence: Run targeted regressions, the full tunnel-core suite, clippy, formatting, task validation, and just ci-gates.
remote_ci: required
remote_ci_evidence: Observe normal push CI if available; do not dispatch hosted jobs manually.
device: not_applicable
device_evidence: This repair changes native cache and response logic only. Android non-root routing, physical handover, and external peer compatibility are not claimed by host regressions.
artifact: not_applicable
artifact_evidence: No APK or native release artifact is published by this repair.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPN05-RESPONSE-LEASES | DNS-1794142289514887 | Targeted response lease regression and full tunnel-core suite | Pending |
| REQ-VPN05-SYNTHESIS-AD | DNS-1794142290766144 | AD mutation and unchanged-content regressions | Pending |
| REQ-VPN05-ISSUED-OWNER | DNS-1794142292240560 | Eviction, generation reset, finite exhaustion, and active lease regressions | Pending |
| REQ-VPN05-RESERVED-LISTENER | DNS-1794142293202272 | Default and custom listener reservation plus capacity regressions | Pending |
| REQ-VPN05-STALE-ISOLATION | DNS-1794142294080225 | Late direct result after fresh encrypted/current reply regressions | Pending |

## Combined validation

Execution step DNS-1794142294899581 owns independent review, the full affected crate suite, affected-crate clippy with all targets, formatting, task validation, and just ci-gates. Each implementation slice must include its regression tests. Host execution does not establish Android or external-network acceptance.
