---
task_id: DGN-1791525730340153
change: local-sim-constraints
commit_sha: null
local: passed
local_evidence: Combined diagnostics and app tests, staticAnalysis, app/service locale lint, architecture health, locked Cargo metadata and independent review passed on 2026-10-09.
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
| REQ-SIM-COLLECT | DGN-1791525922801912 | Full diagnostics suite: 1584 tests, 0 failures/errors/skips; includes capture, permission, subscription-change and API 27 tests | Passed |
| REQ-SIM-EXPLAIN | DGN-1791525923796326 | Assessment tests; 16 app tests including localization contract, current/history context and Compose wrapping/RTL checks; ten locale files | Passed |
| REQ-SIM-PRIVACY | DGN-1791525924654209 | Full diagnostics suite includes legacy JSON, redacted archive/text and identifier allowlist tests | Passed |

## Observed evidence

- Meaningful RED: a provider test failed before local evidence was wired; an assessment assertion failed before condition derivation was implemented. Both pass in the combined suite.
- Compose tests found text overflow. Full-width stacked rows fixed it. LTR and Arabic RTL renders were inspected; labels and explanations wrap without horizontal clipping.
- Independent final review found no actionable issues. Voice registration is separate from packet-data state. The Android API 27 background restriction getter is guarded.
- Architecture health: 21 existing indicators, zero new or worsened entries. Locked Cargo metadata resolves. No Rust or native contract changed.

## Remaining acceptance lanes

Physical-device behavior with dual SIM, OEM permission restrictions and SIM switching has not been observed. No APK artifact, native rebuild or hosted CI result is claimed by the local test gates. These lanes remain required before task archival.

## Combined local command

```sh
./gradlew :core:diagnostics:testDebugUnitTest :app:testGithubFullDebugUnitTest \
  --tests '*DiagnosticsLocalNetwork*' --tests '*DiagnosticsUiContextSupportTest*' \
  --tests '*HistoryConnectionDetailUiFactoryTest*' \
  --tests '*DiagnosticsUiFactoryLocalizationContractTest*' \
  staticAnalysis :app:lintGithubFullDebug :core:service:lintDebug \
  -Pripdpi.skipNativeBuild=true --max-workers=2 --offline
```

Result: BUILD SUCCESSFUL in 4m 27s. Diagnostics: 1584 tests; app: 16 tests; zero failures, errors or skipped tests. Static analysis and both locale lint gates passed. Native build was intentionally excluded from these local JVM checks.
