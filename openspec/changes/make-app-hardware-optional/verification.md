---
task_id: AND-1790433824419175
change: make-app-hardware-optional
commit_sha: null
local: passed
local_evidence: :app:lintGithubFullDebug passed with -Pripdpi.skipNativeBuild=true; PermissionImpliesUnsupportedHardware is absent from its text report.
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: The change only affects manifest feature filtering.
artifact: passed
artifact_evidence: The universal githubFullDebug merged manifest contains camera.any, camera, camera.autofocus, location, location.gps, and location.network with required=false.
deployment: not_applicable
deployment_evidence: No deployment is owned by this local change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-APP-OPTIONAL-HARDWARE | AND-1790433892522447 | Android lint passed without permission-implied hardware warnings; merged manifest contains all optional features | Local pass |
