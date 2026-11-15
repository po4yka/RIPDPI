---
task_id: DAT-1794728756596106
change: imp04-conf-import-corrections
commit_sha: edb82bb8e597a18d038d97a8d5ff102b1caed159
local: passed
local_evidence: 274 focused JVM tests passed (Runtime state 28, Core data 64, App 182). Full staticAnalysis, six unchanged Roborazzi screenshots, ci-gates, architecture health, locked Cargo metadata, and independent review passed.
remote_ci: required
remote_ci_evidence: Hosted CI for the integration commit has not completed.
device: not_applicable
device_evidence: The correction targets text parsing, preview and validation before VPN start.
artifact: not_applicable
artifact_evidence: No release artifact.
deployment: not_applicable
deployment_evidence: No deployment.
---

# Verification

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-WG-CONF-ROUTES | DAT-1794728811060002 | Parser and editor route regression tests | Passed |
| REQ-WG-CONF-ACTIVATION | DAT-1794728811710266 | Editor and repository request regression tests | Passed |
| REQ-WG-CONF-INPUT | DAT-1794728812224059 | Entry-point, limit, preview, and error regression tests | Passed |
| REQ-WG-CONF-DIALECT | DAT-1794728812717587 | AWG parser and subscription regression tests | Passed |
| REQ-WG-CONF-INPUT | DAT-1794728813180809 | Unit suites, source gates and independent review | Passed |

## Observed results

Source commit: `edb82bb8e597a18d038d97a8d5ff102b1caed159`. The JVM and visual suites ran on `2f8e5c4382a946c9093d799487396b324e3b66c7`. The subsequent changes format the decoder and move the existing conf lexer helpers to file scope.

- `testDebugUnitTest` in runtime-state and core-data plus `testGithubFullDebugUnitTest` in app: 274 focused tests, zero failures, errors, or skips (Runtime state 28, Core data 64, App 182).
- `staticAnalysis`: passed with the repository-supported host option `ripdpi.skipNativeBuild=true`.
- `verifyRoborazziGithubFullDebug --tests '*AmneziaWgProfileScreenshotTest'`: three scenarios, six unchanged snapshots.
- `just ci-gates`, `python3 scripts/ci/check_architecture_health.py`, and `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1`: passed.
- The final source separately passed all 54 focused conf parser and INI subscription tests. These tests overlap the 274-case run.
- Independent review verified the route and consent behavior and the combined parser/import boundaries. Its decoder and WS compatibility findings were corrected.
- The existing F7 guard is at `4a13a7c13` and has tests in ScannerUriDispatcherTest. Integration keeps the shared-intake two-section rule, the explicit fixed ListenPort refusal, and strict UTF-8 and multipart zlib decoding.
- Exact physical Android and external peer behavior remain outside this local verification.

## Correction commits

| Finding | Commit |
|---|---|
| F1 | `dc474590e` |
| F2 | `e60113fca` |
| F3 | `d9a14dbcf` |
| F4 | `ae78633d7` |
| F5 | `5732c2bb5` |
| F6 | `07685802f` |
| F7 | `4a13a7c13 (already integrated)` |
| F8 | `a84f33ea7` |
| F9 | `b71530d9b` |
| F10 | `c9a3bf97b` |
| F11 | `ce78835cb` |
| F12 | `d5da6e3b0` |
