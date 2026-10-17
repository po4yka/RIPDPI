---
task_id: UIX-1792222949017657
change: ui-motion-first-wave
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
deployment_evidence: No deployment is owned by this Android UI change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UAF-STATIC | UIX-1792223013470376 | Theme and control behavior tests, static-mode emulator inspection | Required |
| REQ-UAF-DISCONNECT | UIX-1792223013470376 | Confirmation, expiry, late press and disabled-motion tests | Required |
| REQ-UAF-OUTCOME | UIX-1792223014118844 | Active, success, failure, cancellation and saved-result tests | Required |
| REQ-UAF-LATEST | UIX-1792223014868002 | Event insertion, reader position, jump action and filter tests | Required |
| REQ-UAF-ACCESS | UIX-1792223015547663 | Theme tests, locale lint, static analysis, font2/RTL emulator checks and review | Required |
| REQ-UAF-ACCESS | UIX-1792223016361525 | Rebased combined gates, main integration and remote SHA | Required |

## Planned commands

Run focused :app:testGithubFullDebugUnitTest filters for motion, actuator,
diagnostics outcome and history; all theme tests; :app:lintGithubFullDebug;
:core:service:lintDebug; staticAnalysis; scripts/ci/check_architecture_health.py;
and cargo metadata --manifest-path native/rust/Cargo.toml --locked.
Build the real :app:assembleGithubFullDebug APK, record its SHA256, and validate
the four behaviors under an exclusive API37 arm64 emulator lease. Inspect light,
dark, font scale 2.0 and RTL without public network or production operations.
Do not bless screenshot fixtures. Record any intentional screenshot mismatch.
