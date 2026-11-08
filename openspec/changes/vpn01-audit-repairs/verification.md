---
task_id: VPN-1794133736183612
change: vpn01-audit-repairs
commit_sha: c63df6927cc1d1e8d09b36fae43712beb0e6839b
local: passed
local_evidence: Service 2071 and engine 307 JVM tests passed without skips; 526 native tests passed with one existing ignored startup smoke. Static analysis, source gates, architecture health, locked metadata, Clippy, and independent reviews passed. Rebased native checks passed again on the recorded implementation SHA.
remote_ci: required
remote_ci_evidence: Main publication is authorized. Hosted results remain separate and are not inferred from local checks.
device: required
device_evidence: Android restart, revoke, and real TUN driver acceptance were not run. Host fd and Robolectric evidence do not establish device behavior. Physical probes, external acceptance servers, root activation, and real secrets were not used.
artifact: not_applicable
artifact_evidence: No release APK or native binary publication is requested. Host checks do not establish packaged JNI execution.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPN-PREDECESSOR | VPN-1794133860638505 | Reconstruction suite: 12 tests, two failing regressions before the change; 12 passed after it. Timeout rejection, retry, full predecessor chain, idle stop and start-generation release are covered. | Passed on host |
| REQ-TUN-WRITE-RETIRE | VPN-1794133861060047 | Real packet-fd send failure first timed out. Propagation alone exposed missing child cancellation. Final test observes the owned loopback TCP socket close before loop return. | Passed on host |
| REQ-TUN-READ-TERMINATE | VPN-1794133861459291 | Real UnixStream EOF and abortive TCP read each reproduced a timeout before the fix. Both now return errors through session retirement. All three packet-fault tests passed. | Passed on host |
| REQ-VPN-COMPATIBILITY | VPN-1794133861855078 | PacketFd ownership documentation matches source. REQ-PHYSICAL-UNAVAILABLE explicitly requires unknown legacy evidence; its existing JVM tests passed in the full service suite. No guessed physical selection was added. | Passed within scope |
| REQ-VPN-COMPATIBILITY | VPN-1794133862255291 | Full JVM and native suites, staticAnalysis, ci-gates, architecture health, locked Cargo metadata, Clippy, independent pr-reviewer and async-cancel-safety reviews. | Passed locally; hosted/device pending |

## Local commands and results

- `:core:service:testDebugUnitTest`: 2071 passed, zero failures, errors or skips.
- `:core:engine:testDebugUnitTest`: 307 passed, zero failures, errors or skips.
- `staticAnalysis`: passed, including the custom detekt rule tests.
- The Gradle runs used `build-gate --lane ripdpi`, workers 4, native CPU budget 4,
  `skipNativeBuild=true`, and invocation-only heap limits of 5 GiB for Gradle and
  3 GiB for Kotlin. No Android JNI binary was exercised by these JVM tests.
- `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-tunnel-config
  -p ripdpi-tunnel-core -p ripdpi-tunnel-android --lib --locked --offline --jobs 4`:
  config 35, core 373, adapter 118 passed. One existing ignored adapter test,
  `config::tests::startup_latency_smoke`, was not counted as passed.
- `scripts/ci/cargo-guarded.sh cargo clippy --manifest-path native/rust/Cargo.toml
  -p ripdpi-tunnel-core --all-targets --locked --offline --jobs 4 -- -D warnings`:
  passed. Native commands used the same gated, per-worktree Cargo target.
- `build-gate --lane ripdpi -- just ci-gates`: passed after the final rebase.
  Linux-owned API snapshots retain the script's existing Darwin exclusion.
- `python3 scripts/ci/check_architecture_health.py`: passed; 18 current and
  baseline indicators, no new or worsened entries.
- `cargo metadata --manifest-path native/rust/Cargo.toml --locked --offline
  --format-version 1`: passed after the rebase.
- Independent `pr-reviewer` and `async-cancel-safety` reviews found no actionable
  issue in the lifecycle fix, both I/O fixes, or the phase simplification.

## Failed checks retained and resolved

The first worktree build lacked an offline Gradle plugin cache. An online retry
resolved that artifact. Robolectric then timed out while fetching exact SDK
artifacts. An invocation-only init script selected Maven Central; exact Android
9 and 12 JAR/POM artifacts were fetched with curl over IPv4 and checked against
fresh SHA-512 sidecars. No SDK version, dependency declaration or repository
configuration was changed. The final full service suite and static gate passed.

The first source gate found phase SLOC 124 above the existing limit 119.
Removing a no-await forwarding wrapper reduced it to 116. Bounded reads,
transient handling, buffer restoration and error propagation remain tested.
The budget and all native gates passed afterward. No baseline was extended.

## Rebase and evidence binding

The initial source was `930c537c89638614c7e19e6b5bbb3e5c18bab5a3`.
Integration fetched 14 new Transparent-ingress commits through
`0a06b3cbe83fa3934612a77d4bc2b492e6211c7b` and rebased without conflicts.
The recorded implementation SHA includes that upstream source.

Native suites, Clippy, source gates, architecture health and metadata were
repeated on the rebased source. A source diff confirmed that the app, service,
engine, data, diagnostics, Gradle and contract-fixture inputs used by the passing
JVM checks were unchanged. Their results remain valid for this implementation.
The evidence commit changes task records only; it does not change application
or native source.

## Remaining acceptance

The physical legacy ambiguity is an intentional supported limit, not an unfixed
selection algorithm. The audit's external IPv6 TCP rejection hypothesis was
refuted by packet-fd evidence and requires no application correction.

A completed predecessor job proves that its old release callbacks have ended.
It does not certify full native retirement after an existing CleanupPending
outcome. No automatic late retry of those old release callbacks was found.

Keep this change in review while required hosted or device evidence remains
unresolved. Do not archive it or mark Android routing, real driver failure,
revoke, FGS timing, physical handover or external compatibility passed from host
results. No deployment, release, signing, golden blessing or external message
was performed.
