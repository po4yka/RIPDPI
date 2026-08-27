---
task_id: DGN-1786867116840500
change: fix-vpn-route-observation-and-evidence
commit_sha: null
local: blocked
local_evidence: "Service 1855/1855 and app 1765/1765 passed; full staticAnalysis passed offline. Diagnostics has 1400 passes and two DNS candidate-count failures reproduced on clean baseline 4d9444ab. Architecture, locked Cargo metadata, and task validation passed."
remote_ci: required
remote_ci_evidence: null
device: blocked
device_evidence: "Only a physical API 37 device is connected. Physical API 36 acceptance and permission to install the debug APK and exercise VPN transitions remain outstanding. No device state was changed."
artifact: required
artifact_evidence: null
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Current callback repair — 2026-08-27

This repair starts from `4d9444abdda85789675ed1db6822a2d971da8462`.
The earlier owner-route implementation is already present in `main` through
`d4034d2cc`; the previous verification record described an older worktree SHA
and APK, not the artifact for this repair. Current archive schema is 12. No
schema, golden, JNI, protobuf, dependency, or locale changes are made here.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPN-ROUTE-001 | SVC-1786867116840502 | Registered owned callbacks with null/stale getters; both callback orders; owner-UID rejection | local pass; API 36 pending |
| REQ-VPN-ROUTE-002 | SVC-1786867116840502 | Separate callback halves produce matching route families; route-only changes preserve validation | local pass |
| REQ-VPN-ROUTE-003 | DGN-1786867116840501 | Validation-only changes preserve installed routes; diagnostics and app projection suites pass | local pass |
| REQ-VPN-ROUTE-004 | SVC-1786867116840502 | Generation anchors, retired-network loss, stopped registration, and lifecycle receipt regressions pass | local pass |
| REQ-VPN-ROUTE-005 | DGN-1786867116840503 | Renderer 38/38, exporter 14/14, passive route builder 3/3; no new exported identifiers | local pass |
| REQ-VPN-ROUTE-006 | DGN-1786867116840503 | Existing archive/legacy decode tests run without blessing; schema 12 unchanged | local pass |
| REQ-VPN-ROUTE-007 | DGN-1786867116840503 | Local checks below; physical API 36 and exact-SHA hosted CI remain outstanding | partial |

## Behavioral regression evidence

- Null synchronous getters: the registered capabilities/routes pair initially
  failed with `(Awaiting, Unavailable)` instead of `(Complete, Verified)`.
  Retaining the independently delivered callback axes made the test pass.
- Stale synchronous getters: the observed states were `[Complete, Awaiting]`
  instead of `[Awaiting, Complete]`. Removing getter calls from both callbacks
  made the test pass.
- Rejected foreign VPN: after its capabilities/routes callbacks, loss of the
  owned VPN incorrectly remained `Awaiting` instead of `Lost`. Tracking known
  rejected networks and ignoring their route callbacks made the test pass.
- Combined observer/store/runtime regression run: 25 tests passed, zero
  failures/errors/skips. Tests exercise the public registered callback path;
  the unused atomic `observeNetworkShape` production API was removed.
- Independent final read-only review found no remaining actionable issues in
  registration/generation guards, rejected-owner handling, or privacy
  projection. Internal Android network keys are not added to exports.

## Current local gates

All heavy commands run through `build-gate`, with one Gradle worker and no
parallel execution. JVM and static checks use `-Pripdpi.skipNativeBuild=true`;
this does not constitute native packaging evidence.

- `:core:service:testDebugUnitTest`: 1,855 tests, zero failures/errors/skips.
- `:app:testGithubFullDebugUnitTest`: 1,765 tests, zero failures/errors/skips.
- `:core:diagnostics:testDebugUnitTest`: 1,402 tests, two failures, no errors or
  skips. Both failures are in `ConnectivityDnsTargetPlannerTest`, expecting
  16 automatic candidates while the planner returns 12. The route repair does
  not modify the planner or its tests. The same two failures were reproduced
  by running the 11-test planner class on clean baseline `4d9444ab` in a
  separate worktree. Both baseline and repaired trees report expected 16,
  actual 12; prior commits excluded Cloudflare from automatic candidates but
  left these test expectations unchanged. This unrelated DNS defect is not
  hidden by changing assertions or provider policy in this repair.
- Initial `staticAnalysis` waited on Google Maven metadata inside Android lint;
  that invocation was cancelled. The first callback cycle passed the complete
  `staticAnalysis --offline` task. The final full static-analysis rerun also
  passed after fixing a blank-line ktlint violation; no checks were disabled.
- `--no-watch-fs` is used for the final checks after a Gradle daemon reused the
  old test class despite the renamed test source. A real compile then exposed
  an API 29 Robolectric route-fixture issue, corrected by supplying a gateway
  and matching route/link interface. No production assertion was weakened.
- `python3 scripts/ci/check_architecture_health.py`: 23 indicators, no new or
  worsened entries.
- `cargo metadata --locked --format-version 1` from `native/rust`: passed.
- `./taskctl validate`: 46 tasks and 221 steps passed.

## Remaining acceptance

- Build and inspect a fresh debug APK from the committed repair; record its
  exact source SHA, application identity, APK hash, signature, and native ELF
  checks. No previous APK hash is credited to this repair.
- Observe hosted CI on the exact pushed SHA. A push alone is not CI evidence.
- On a physical API 36 device, verify the self-excluded owner's underlay
  default alongside the owned validated VPN callback, matching route families,
  Route UI transitions, and current-generation forwarding evidence across
  start/rebuild/handover/stop.
- Distinct-client traffic must carry a verified non-owner UID and correlate
  with TUN counter deltas. The packet-smoke wrapper's owner-UID probe and an
  API 37 run do not satisfy that criterion.
- The task remains open until the outstanding acceptance evidence exists.
