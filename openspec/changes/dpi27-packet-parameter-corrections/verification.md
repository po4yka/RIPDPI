---
task_id: RST-1794677915101106
change: dpi27-packet-parameter-corrections
commit_sha: null
local: required
local_evidence: Targeted regressions and combined gates are pending.
remote_ci: not_applicable
remote_ci_evidence: Local repair acceptance; no hosted CI success is claimed.
device: not_applicable
device_evidence: No device, root activation, or external probe is authorized. Android compile is a local gate where available.
artifact: required
artifact_evidence: Independent local kernel filter and raw-builder byte checks are pending.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DPI27-FEEDBACK | RST-1794678235803087 | Planned regression and owning gate | required |
| REQ-DPI27-IDENTIFICATION | RST-1794678245393545 | Planned regression and owning gate | required |
| REQ-DPI27-SACK | RST-1794678253764740 | Planned regression and owning gate | required |
| REQ-DPI27-UDP | RST-1794678258153648 | Planned regression and owning gate | required |
| REQ-DPI27-CANDIDATES | RST-1794678262798271 | Planned regression and owning gate | required |
| REQ-DPI27-TIMESTAMPS | RST-1794678269799237 | Planned regression and owning gate | required |
| REQ-DPI27-SCOPE | RST-1794683533527463 | Settings state regression, locale lint and render | required |
