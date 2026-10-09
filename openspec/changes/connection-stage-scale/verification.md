---
task_id: DGN-1791523992124084
change: connection-stage-scale
commit_sha: null
local: passed
local_evidence: Full diagnostics suite passed 1559 tests; selected app suite passed 13 tests. Full staticAnalysis and app/service locale lint passed. Architecture health, native architecture contracts, locked Cargo metadata and independent review passed.
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
| REQ-STAGE-PROJECTION | DGN-1791524088743682 | ConnectionStage* tests in the full diagnostics suite; native producer audit | Passed |
| REQ-STAGE-UI | DGN-1791524089854725 | 13 app mapping/Compose/transfer tests; six visual renders, including 360 dp Arabic RTL at 1.5 font scale; ten locale sets | Passed |
| REQ-STAGE-EXPORT | DGN-1791524090977242 | StageScaleSummaryTest and full diagnostics archive/regression suite; full staticAnalysis and app/service lint | Passed |

## Observed checks

- The full `:core:diagnostics:testDebugUnitTest` suite passed: 1559 tests, no failures or skips.
- The selected `:app:testGithubFullDebugUnitTest` suite passed: 13 tests, no failures or skips. Filters: `*ConnectionStage*`, `*StageScaleSummary*`, `*DiagnosticsTransfer*`, `*ArchiveRenderer*`, `*ArchiveExporter*`.
- Gradle checks use `-Pripdpi.skipNativeBuild=true --max-workers=2 --offline`. They do not prove an Android APK build or device operation.
- Full `staticAnalysis`, `:app:lintGithubFullDebug`, and `:core:service:lintDebug` passed after complexity refactoring, without baseline changes.
- Architecture health reported no new or worsened indicators; native architecture contracts reported zero violations. Locked Cargo metadata resolved.
- Independent review found three P2 issues: TLS failure attribution, capped matrix bodies, and zero-byte live progress. Each issue was fixed and has regression coverage. A second review found no new actionable P1/P2.
- Stage timings omit combined DNS/SOCKS transport intervals. Sent TCP bytes are not presented as received body bytes.
- No wire schema, storage schema, native source, dependency, golden, or baseline changed.
- The stage UI reuses current diagnostic components and theme tokens. There is no dedicated protocol-stage RDS sample; the hop-trace design describes a different feature.

## Evidence limits

Remote CI and Android device acceptance are pending. No installable APK was built in this task. Keep the portfolio task in review until those evidence lanes are resolved.
