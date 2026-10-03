---
task_id: SVC-1791025113925875
change: svc-1791025113925875-actionable-diagnostics-verified-selector
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: not_applicable
artifact_evidence: No distributable release artifact is owned; build and device proof are recorded separately.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-ADS-ACTIONS | SVC-1791025380381572 | `372bda4ac3ecd7eaf1004dbbc6bac9cb5184eba5`: recommendation generation/navigation and full-row callback tests passed (9 core +42 app); locale lint and app/core ktlint/detekt passed. English/German/Arabic previews visually inspected; 10-locale key parity verified after rebase. | passed |
| REQ-ADS-SELECTION | SVC-1791025380877118 | Local gates passed: service 2010 tests, settings 53 tests, app selector/activation 27 tests, all zero failures/errors/skips; app/service detekt and ktlint passed. Seed-before-snapshot, child timeout recovery, pre-dispatch cancellation, owner overlap, child probe lifetime and stale same-ID profile guards covered. Device smoke remains required. | required |
| REQ-ADS-PAYLOAD | SVC-1791025381376340 | Pending candidate transport fixtures for valid response, stall, failure and stale evidence. | required |
| REQ-ADS-LOCALITY | SVC-1791025379377245 | `ca586188e95b20c51f984db0eb9a1d2c50a7012b` and `1dbd70f032385d36ffc03c8720c58efeff59f5f5`: proxy 232 unit +59 integration; monitor 242 unit +18 integration; locked metadata, native contracts, 30 Python policy tests, workspace Clippy and cargo-deny passed. Opt-in soak/load tests retain their existing defaults. Exactly two discouraged canonical-model dependencies removed with explicit user approval; no baseline edits. | passed |
| REQ-ADS-LOCALITY | SVC-1791025379880611 | `build-gate -- env CARGO_BUILD_JOBS=3 ./gradlew :core:diagnostics:testDebugUnitTest :app:testGithubFullDebugUnitTest -Pripdpi.skipNativeBuild=true --max-workers=4`: passed; standalone ktlint and independent semantic review passed. | passed |
| REQ-ADS-LOCALITY | SVC-1791025381882198 | Pending combined static analysis, architecture, hosted CI and device evidence. | required |
