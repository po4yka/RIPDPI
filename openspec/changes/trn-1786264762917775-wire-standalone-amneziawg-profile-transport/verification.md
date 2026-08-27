---
task_id: TRN-1786264762917775
change: trn-1786264762917775-wire-standalone-amneziawg-profile-transport
commit_sha: 0299de9e072a4ac0b784709f7ff10e3ef1726336
local: blocked
local_evidence: "3059 Kotlin tests, 93 native tests, 62 network E2E tests, AndroidTest Kotlin compilation and full staticAnalysis passed. Unchanged native hotspot and unsafe-boundary baseline failures remain acceptance blockers."
remote_ci: blocked
remote_ci_evidence: "Published implementation bundle 0299de9e072a4ac0b784709f7ff10e3ef1726336: CI run 33110649324 passed task/OpenSpec contracts but architecture-health failed at Run native hotspot budgets. Remaining jobs were still running at evidence capture. No green hosted acceptance is claimed."
device: not_applicable
device_evidence: "Acceptance permits independent loopback-peer evidence. No physical-device installation or execution was performed."
artifact: not_applicable
artifact_evidence: "This change does not require a release artifact. No current-change APK is claimed."
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TRN-1786264762917775-001 | TRN-1786264762919403 | 87 ripdpi-warp-core unit tests; invalid active codec configuration fails closed | passed |
| REQ-TRN-1786264762917775-002 | TRN-1786264762919688 | Production AWG runtime exchanges encrypted TCP and UDP with pinned independent peer | passed |
| REQ-TRN-1786264762917775-003 | TRN-1786264762919682 | 6 JNI adapter host tests and 6 Kotlin binding/serialization tests passed; Android artifact not rebuilt | passed |
| REQ-TRN-1786264762917775-004 | TRN-1786264762919373 | 6 native configuration serialization tests and 68 editor/DTO tests passed; DNS/routes stay service-owned | passed |
| REQ-TRN-1786264762917775-005 | TRN-1786264762919279 | Standalone activator exact acknowledgement/rollback and stale Start/Stop regressions in 1884 passing service tests | passed |
| REQ-TRN-1786264762917775-006 | TRN-1786264762919408 | 1884 service tests including cold activation, Xray handoff, profile route/DNS/MTU and receipt tests | passed |
| REQ-TRN-1786264762917775-007 | TRN-1786264762919506 | 59 editor tests including permission denial, matching consent and duplicate callback handling | passed |
| REQ-TRN-1786264762917775-008 | TRN-1786264762919526 | Pinned amneziawg-go v0.2.18 peer; real IPv4/IPv6 TCP/UDP, source metadata and stalled-client shutdown passed | passed |

## Reproduction and verification commands

Run compiler-backed commands through `build-gate` on this Mac. Gradle unit and
static checks use `-Pripdpi.skipNativeBuild=true`; that flag does not prove an
Android native artifact. Rust commands use the pinned toolchain and `--locked`.

- `cargo test --locked -p ripdpi-warp-core -p ripdpi-amneziawg-android`: 93 tests passed after regression
  reproduction for bound listener address, UDP source metadata, IPv6 routing,
  TCP/UDP cleanup and runtime shutdown.
- `cargo clippy --locked -p ripdpi-warp-core --all-targets --all-features -- -D warnings` passed.
- The normal native commit hook ran workspace Clippy with `--locked --workspace --no-deps --all-targets -- -D warnings`: passed.
- `RUSTUP_TOOLCHAIN=1.96.0 bash scripts/ci/run-rust-network-e2e.sh`: 25 local fixture tests, 36 proxy E2E tests and the independent AWG interop test passed.
- `python3 scripts/tests/run-standalone-awg-interop.py` passed against the pinned
  independent Go peer; no remote endpoint or device is used.
- `python3 -m unittest discover -s scripts/tests -p test_standalone_awg_interop_runner.py -v`
  passed, including a child process that ignores TERM.
- The final combined Kotlin run passed 3059 tests, with zero failures/errors/skips:
  runtime-state 183, core:data 779, engine-api 55, service 1884,
  selected GithubFull editor/import tests 71 and selected GithubSimple failover tests 87.
- The GithubFull selection includes all 59 AWG editor tests and 12 Xray import
  activation tests. The complete runtime-state suite includes the 9 AWG DTO tests.
- The GithubSimple selection covers `FailoverCoordinatorTest` and
  `SimpleVlessRuntimeMonitorTest`, including suspended preparation and newer-intent races.
- `:core:engine-api:testDebugUnitTest`: 55 passed, including 6 AWG configuration contract tests.
- `:app:compileGithubFullDebugAndroidTestKotlin` passed; instrumentation was not run.
- The same combined Gradle invocation completed `staticAnalysis`: BUILD SUCCESSFUL,
  809 actionable tasks. No lint, detekt or architecture baseline was extended.
- After fetch/rebase, the combined Kotlin/static-analysis gate passed again on
  `0299de9e072a4ac0b784709f7ff10e3ef1726336` (818 actionable tasks), followed by
  successful integration to main. The 93 native tests and 62 network E2E tests
  also passed again on that exact tree before push.
- Architecture health, runtime boundaries, native architecture contracts and
  async-safety guards passed without baseline changes.
- The final architecture report contains 23 current and 23 baseline indicators,
  with zero new or worsened indicators across 114 crates.

The implementation changes the internal `XrayProviderSelectionStore` getter and
setter from suspending to synchronous operations. Its production implementation
already performs synchronous preferences access; all three test implementations
and every caller are updated. This lets the existing intent arbiter atomically
publish provider selection and enqueue activation without a second lifecycle.

## Existing baseline failures

The implementation bundle was pushed to main and its exact remote SHA was
confirmed. [Hosted CI run 33110649324](https://github.com/po4yka/RIPDPI/actions/runs/33110649324)
passed the task/OpenSpec contract gate; its
[architecture-health job](https://github.com/po4yka/RIPDPI/actions/runs/33110649324/job/98652343619)
failed at `Run native hotspot budgets`. The same guard rerun locally on the
published tree reported exactly one over-budget file: `listener.rs`, 72 > 54.
The hosted job log download timed out; the job/step conclusion was retrieved
separately from the Actions API. Other jobs were still running at capture.
GitHub accepted the requested direct main push with the existing bypass
authority; that acceptance is not evidence that required checks passed.

Baseline main `7d8580c92dc6f011a4e685d0677e87a59469c248` has failing
[CI jobs](https://github.com/po4yka/RIPDPI/actions/runs/33103005845).
The native hotspot guard still reports `ripdpi-tunnel-core/src/io_loop/tcp_accept/listener.rs`
72 lines against a 54-line limit. The unsafe-boundary guard reports the existing
Clone owner pattern in `ripdpi-flow-app-attribution/src/lib.rs:160`. Neither file
nor its baseline is changed by this task. These failures remain visible and do
not count as passed gates.
