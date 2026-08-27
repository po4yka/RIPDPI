---
task_id: DGN-1786867116840500
change: fix-vpn-route-observation-and-evidence
commit_sha: f0d2beeb95b87783d5e88a7c35b1d395b1acd8ec
local: blocked
local_evidence: "Service 1855/1855 and app 1765/1765 passed; full staticAnalysis passed offline. Diagnostics has 1400 passes and two DNS candidate-count failures reproduced on clean baseline 4d9444ab. Architecture, locked Cargo metadata, and task validation passed."
remote_ci: blocked
remote_ci_evidence: "Exact-SHA CI run 33093779255 has a failed native hotspot-budget step: tcp_accept/listener.rs is 72 LoC against limit 54, reproduced unchanged on baseline 4d9444ab. Other architecture job checks passed. CodeQL 33093779196, Secret Scan 33093779128, and fleet-fixtures 33093779126 passed. Full CI success is not claimed."
device: blocked
device_evidence: "Only a physical API 37 device is connected. Physical API 36 acceptance and permission to install the debug APK and exercise VPN transitions remain outstanding. No device state was changed."
artifact: passed
artifact_evidence: "Fresh githubFullDebug arm64-v8a APK built from f0d2beeb95b87783d5e88a7c35b1d395b1acd8ec. SHA-256 16eaf5e895a3625e11f3ae27d80042166d54ddb1ba77fd23485e08d7f2905071; application com.poyka.ripdpi, version 0.1.4 (20000012). APK Signature Scheme v2 and the ELF verifier against libraries extracted from this APK passed. No device installation or runtime proof is claimed."
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
| REQ-VPN-ROUTE-007 | DGN-1786867116840503 | Local checks below; physical API 36 is pending and exact-SHA hosted CI is blocked by an existing native budget breach | partial |

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
- After committing and rebasing on `origin/main`, the combined JVM/static
  command ran again on `f0d2beeb9`: service and app suites and `staticAnalysis`
  passed; only the same two baseline DNS tests failed. Architecture health,
  locked Cargo metadata, task validation, and strict OpenSpec validation also
  passed on the integrated tree.

## Committed APK

The implementation commit is `f0d2beeb95b87783d5e88a7c35b1d395b1acd8ec`.
The following build completed successfully without skipping native compilation:

```sh
build-gate -- env -u CARGO_BUILD_JOBS ./gradlew \
  :app:assembleGithubFullDebug --offline --no-watch-fs \
  --max-workers=1 --no-parallel \
  -Pripdpi.nativeCpuBudget=2 -Pripdpi.nativeAbiParallelism=1
```

- Qualified artifact: `app-github-full-arm64-v8a-debug.apk`, arm64-v8a only.
- Local retained path:
  `/private/tmp/ripdpi-vpn-owner-artifact-f0d2beeb9-20260827/app-github-full-arm64-v8a-debug.apk`.
- SHA-256:
  `16eaf5e895a3625e11f3ae27d80042166d54ddb1ba77fd23485e08d7f2905071`.
- Application ID: `com.poyka.ripdpi`; version name `0.1.4`, code `20000012`;
  min SDK 27, target SDK 35, compile SDK 37. The DEX contains `f0d2beeb9`.
- `apksigner verify --verbose --print-certs`: passed, APK Signature Scheme v2.
  Android Debug certificate SHA-256:
  `c048a6d6124a61149f7d6d3d8aa80055d39335ff76f14e1584d508408798de15`.
- `scripts/ci/verify_native_elfs.py` passed against the native libraries
  extracted from this exact APK. No other generated ABI split is qualified.
- Build log:
  `/private/tmp/ripdpi-vpn-owner-apk-f0d2beeb9-gated-20260827.log`.
  Signature, application identity, and ELF reports are retained beside the APK.
- This artifact is not physical-device, forwarding, or recurring AWG evidence.

## Hosted CI and integration

- `origin/main` was independently read back as
  `f0d2beeb95b87783d5e88a7c35b1d395b1acd8ec` after the fast-forward and push.
- [Exact-SHA CI run 33093779255](https://github.com/po4yka/RIPDPI/actions/runs/33093779255)
  is not green. The `architecture-health` job failed in `Run native hotspot
  budgets`: `native/rust/crates/ripdpi-tunnel-core/src/io_loop/tcp_accept/listener.rs`
  has 72 production lines against a limit of 54. The same lightweight check
  reproduces the same breach on clean baseline `4d9444ab`. This repair changes
  neither native source nor the hotspot budget. Architecture health itself,
  file LoC, native architecture contracts, and Linux TUN wrapper contracts passed.
- [CodeQL](https://github.com/po4yka/RIPDPI/actions/runs/33093779196),
  [Secret Scan](https://github.com/po4yka/RIPDPI/actions/runs/33093779128), and
  [fleet-fixtures](https://github.com/po4yka/RIPDPI/actions/runs/33093779126)
  passed for the implementation SHA. These do not replace full CI acceptance.
- GitHub accepted the authorized direct-main push using existing account
  bypass rights, reporting PR, `ci-required`, and pending CodeQL rule bypasses.
  No branch protection setting was changed and no force push was used. Push
  acceptance is not evidence of passing required checks.

## Remaining acceptance

- Resolve the separate existing DNS test and native hotspot-budget blockers
  and obtain full hosted CI acceptance. No check or baseline is weakened here.
- On a physical API 36 device, verify the self-excluded owner's underlay
  default alongside the owned validated VPN callback, matching route families,
  Route UI transitions, and current-generation forwarding evidence across
  start/rebuild/handover/stop.
- Distinct-client traffic must carry a verified non-owner UID and correlate
  with TUN counter deltas. The packet-smoke wrapper's owner-UID probe and an
  API 37 run do not satisfy that criterion.
- The task remains open until the outstanding acceptance evidence exists.
