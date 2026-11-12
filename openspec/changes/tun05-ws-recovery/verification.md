---
task_id: SVC-1794477234440384
change: tun05-ws-recovery
commit_sha: null
local: passed
local_evidence: Targeted supervisor, WS recovery, and VPN exit tests passed; service detekt and ktlintCheck passed. See /tmp/tun05-recovery-final-gates.log. Combined source gates and exact integration SHA are recorded by the parent integration task.
remote_ci: not_applicable
remote_ci_evidence: Parent owns remote push and CI observation.
device: not_applicable
device_evidence: No device acceptance is claimed by this local fix.
artifact: not_applicable
artifact_evidence: No native artifact is changed by the recovery supervisor.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AWG-WS-RECOVERY | SVC-1794477296252372 | AmneziaWgRuntimeSupervisorTest and AmneziaWgWsRecoveryTest | Passed targeted local tests |
| REQ-AWG-WS-STOP | SVC-1794477296252372 | AmneziaWgRuntimeSupervisorTest and AmneziaWgWsRecoveryTest | Passed targeted local tests |
| REQ-AWG-WS-BOOTSTRAP | SVC-1794477296252372 | AmneziaWgWsRecoveryTest and config serialization tests | Passed targeted local tests |
