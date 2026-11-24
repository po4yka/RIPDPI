---
task_id: RTE-1786264762917255
change: rte-1786264762917255-adopt-process-based-per-package-routing-via-xray-tun-routeonly
commit_sha: d0ee0f52e5363b363e34889044c5ebd03f501c4b
local: passed
local_evidence: All 17 VpnAppExclusionPolicyTest cases and the three SplitTunnelScreenTest cases passed. The app routing catalog supplies the default exclusions.
remote_ci: passed
remote_ci_evidence: "Android static analysis, Kotlin coverage, and the relay configuration contract passed at d1b75749a2ef4b01e01b3b6648dfdd475f35487e in hosted run 45441455734. These checks cover this task. The standalone fuzz lockfile is the only change from the locally tested source."
device: not_applicable
device_evidence: App acceptance covers the Android routing plan and filter application. Measurement of public egress addresses on a device with two live paths is outside scope.
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this portfolio area.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RTE-1786264762917255-001 | RTE-1786264762918248 | VpnAppExclusionPolicy tests cover the allow and disallow selections used by Android VPN service. | passed |
| REQ-RTE-1786264762917255-002 | RTE-1786264762918389 | The split-tunnel editor exposes allowed and excluded app lists where the local editor owns the policy. | passed |
| REQ-RTE-1786264762917255-003 | RTE-1786264762918168 | The app routing catalog defines the default exclusions. | passed |
| REQ-RTE-1786264762917255-004 | RTE-1786266573979890 | The routing-plan regression distinguishes a blocklisted app from an unrelated app and filters unavailable packages. | passed |
