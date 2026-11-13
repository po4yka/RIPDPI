---
task_id: RST-1794556971423909
change: dpi13-audit-fixes
commit_sha: ca1090077bea873f2de9a70c0c50c0881e3ddaee
local: passed
local_evidence: Combined source tree passed 801 Rust tests, 78 Kotlin model tests, host and restricted Linux clippy, full staticAnalysis, ci-gates, architecture and locked metadata. Separate finding commits passed normal hooks and isolated Rust checks. Independent pr-reviewer and F4 unsafe review found no unresolved issue.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI acceptance is not claimed; this change requires local checks and an ordinary authorized main push.
device: not_applicable
device_evidence: Physical-device and root activation were not authorized. Successful raw send, repair queue ACK/retransmission and external compatibility remain unverified.
artifact: not_applicable
artifact_evidence: No packaged Android binary or release artifact is requested.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-MD-REPLAY | RST-1794557095307425 | Actual loopback peer receives abc before opaque and known-progress timeout; uncertainty retained; invalid pre-send plan stays certain. Included in the 801-test Rust run and isolated F1 checks. | passed |
| REQ-MD-BULK | RST-1794557095887616 | 58 protocol tests pass, including real UnixStream/SCM_RIGHTS byte and descriptor round trips, 1/2/4/16KiB and maximum payload, negotiated/legacy limits and malformed ranges. Four Linux client bulk tests pass. | passed |
| REQ-MD-SETTINGS | RST-1794557096567987 | Rust parser/runtime tests cover first-owner inheritance, marker0, sorting, duplicates, inactive owner and rejection before any peer bytes. Kotlin model suite: 78 tests, including two new grouped-settings tests. Generated config API snapshot adds one method. | passed |
| REQ-MD-QUEUE | RST-1794557097247048 | Linux multidisorder tests: 5 passed; restoration tests: 3 passed; Linux clippy passed. Explicit IPv4/IPv6 masks, SEQ wrap, no plain bytes on early failure and loopback continuation checked. | passed |
| REQ-MD-GATES | RST-1794557097896136 | Documentation compared with startup capability validation and both multidisorder/ipfrag2 executors. Combined review corrected the ipfrag2 boundary claim. | passed |

Combined review and gates: RST-1794557098495856.

## Exact local checks

All compiler-backed commands ran through `build-gate --lane ripdpi`. Rust used 1.98.1, at most four Cargo/CMake jobs and a target directory owned by this worktree. Gradle used the configured 5GiB heap, 3GiB Kotlin heap and at most four workers.

From the checkout root:

```sh
cargo test --locked --jobs 4 --manifest-path native/rust/Cargo.toml -p ripdpi-config -p ripdpi-desync -p ripdpi-desync-runtime -p ripdpi-proxy-config -p ripdpi-root-helper-protocol
cargo clippy --locked --jobs 4 --manifest-path native/rust/Cargo.toml -p ripdpi-config -p ripdpi-desync -p ripdpi-desync-runtime -p ripdpi-proxy-config -p ripdpi-root-helper-protocol -p ripdpi-privileged-ops -p ripdpi-runtime-platform --all-targets -- -D warnings
./gradlew :core:data:model:testDebugUnitTest --max-workers=4 -Pripdpi.skipNativeBuild=true -Pripdpi.nativeCpuBudget=4
./gradlew staticAnalysis --max-workers=4 -Pripdpi.skipNativeBuild=true -Pripdpi.nativeCpuBudget=4
just ci-gates
python3 scripts/ci/check_architecture_health.py
cargo metadata --locked --manifest-path native/rust/Cargo.toml --format-version 1 --no-deps
cargo fmt --manifest-path native/rust/Cargo.toml --all -- --check
git diff --check
```

Observed results: 801 Rust tests passed, 0 failed/ignored; 78 Kotlin tests passed, 0 failed/errors/skipped. `staticAnalysis` succeeded with 826 tasks. All nine combined phases returned exit0. The owning `check_rust_api_snapshots` generator updated only the ripdpi-config method entry; the complete 13-target API gate matched.

Restricted Linux image `ripdpi-dpi05-linux-check:rust1.98.1` ran as uid1000 with `--network none --cap-drop ALL --security-opt no-new-privileges`; source and host Cargo caches were read-only. Inside the container:

```sh
cargo test --locked --offline --jobs 4 -p ripdpi-privileged-ops multi -- --nocapture
cargo test --locked --offline --jobs 4 -p ripdpi-privileged-ops restoration -- --nocapture
cargo test --locked --offline --jobs 4 -p ripdpi-runtime-platform root_helper_client::transport::tests::bulk_tests -- --nocapture
cargo clippy --locked --offline --jobs 4 -p ripdpi-privileged-ops -p ripdpi-runtime-platform --all-targets -- -D warnings
```

Observed: 5 + 3 + 4 tests passed and Linux clippy passed. No privileged successful send ran. A separate earlier full privileged-ops run reported 97 passed / 3 failed: unchanged BPF attach tests returned EPERM without capabilities. That full suite is not reported as green; no test or enforcement was removed.

Logs for this local run are under `/tmp/dpi13-rebased-run.log`, `/tmp/dpi13-combined-*.log`, `/tmp/dpi13-commit-fixes.log`, and the worker logs named in the audit handoff. Temporary queued commands cancelled before admission are not counted as runs. The first partial-staging commit attempt failed because the hook saw a mixed F1/F3 snapshot; each final finding commit instead used an isolated staged tree and normal hooks.

## Evidence limits

C: implementation and callers reviewed. V: the named local tests, loopback delivery, packet bytes and error/transport contracts only. Raw TCP_REPAIR success, kernel retransmission under loss, Android VPN/root-helper routing and external-network E are not established. No S/E certification is claimed from static analysis. No golden, dependency, lockfile or baseline relaxation was used.

## Rebase review

The final source commit is the F4 commit named above. The job was rebased onto origin/main `894fb66bf7ab64ff94da61e9801607144786957e`. An independent pr-reviewer checked the upstream intersections with F1-F5 and found no actionable issue. Upstream FakeRst, TLS bounds, Kotlin root-mode checks and restoration behavior were preserved. Verification logs were refreshed on the rebased tree. The earlier pre-rebase Rust run had 786 passing tests; the rebased run has 801.

All nine rebased check phases returned exit0 (`REBASED_BATCH_EXIT=0`). The rebased `staticAnalysis` run succeeded in 5m27s with 826 tasks: 195 executed, 21 from cache and 610 up-to-date. Linux regressions again passed 5 + 3 + 4 tests; Linux clippy passed.

## Final fixture-only rebase

Before publication, origin/main advanced to `5a1c0045fde51a9605e32600ec4ab93106218b34`. The job was rebased once more. `git diff --name-only 4a78dd7127 HEAD` listed only `core/data/src/test/resources/contract/protocol-liveness.schema.json`, an upstream fixture update. All production source, test code, manifests, compiler inputs for the named Rust/model runs, and DPI-13 documentation remained byte-identical to the fully checked tree. The compiler-backed results above were reused. The repository fixture-contract tests, cross-language runtime contract gate, protocol-fixture version gate, architecture check, JSON syntax check and task validation were run again. No source fix or baseline change was added in this rebase.

Final fixture-only checks: 62 repository fixture tests passed (exit0); all named source/task gates and the JSON syntax check passed (exit0). Commands:

```sh
python3 -m unittest scripts.tests.test_fleet_fixtures scripts.tests.test_netem_transaction scripts.tests.test_phase16_matrix scripts.tests.test_protocol_fixture_versions
python3 scripts/ci/check_cross_language_runtime_contracts.py
python3 scripts/ci/check_protocol_fixture_versions.py
python3 scripts/ci/check_architecture_health.py --check
python3 -m json.tool core/data/src/test/resources/contract/protocol-liveness.schema.json > /dev/null
./taskctl validate
```
