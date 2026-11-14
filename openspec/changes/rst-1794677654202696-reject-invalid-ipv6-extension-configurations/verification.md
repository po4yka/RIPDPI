---
task_id: RST-1794677654202696
change: rst-1794677654202696-reject-invalid-ipv6-extension-configurations
commit_sha: null
local: required
local_evidence: Affected Cargo tests and just ci-gates must pass before integration.
remote_ci: not_applicable
remote_ci_evidence: Local repair gates; hosted CI is not an acceptance claim.
device: not_applicable
device_evidence: Input validation and refusal only; no raw device execution is changed or claimed.
artifact: required
artifact_evidence: Independent diff review and source gates before integration.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-IPV6-ROUTING | RST-1794677811832188 | cargo test --locked -p ripdpi-strategy-ipv6 -p ripdpi-strategy-config | pending |
| REQ-IPV6-TYPE | RST-1794677812640554 | cargo test --locked -p ripdpi-strategy-config -p ripdpi-tunnel-intercept | pending |
| REQ-IPV6-UDP | RST-1794677813465324 | cargo test --locked -p ripdpi-proxy-config; just ci-gates | pending |
