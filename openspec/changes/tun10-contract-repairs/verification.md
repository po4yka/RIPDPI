---
task_id: RLY-1794486295363382
change: tun10-contract-repairs
commit_sha: 1bcc9b9c7995b10199e5cf41e9a8e968a8d21252
local: passed
local_evidence: All repair slices passed owning tests and independent review; combined staticAnalysis, 109 service tests and ci-gates passed. Main was fast-forwarded to the recorded source SHA.
remote_ci: required
remote_ci_evidence: Final pushed revision has not been checked.
device: not_applicable
device_evidence: No device-only carrier is enabled. Physical, VPN and external Tor acceptance remain unverified.
artifact: passed
artifact_evidence: Built and executed the host WebTunnel helper for PT protocol checks. Android packaging is not covered.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TUN10-EGRESS | RLY-1794486420509778 | 12 resolver and 18 editor tests; app/service lint; one existing screenshot verification | Passed |
| REQ-TUN10-ENV | RLY-1794486421181770 | 44 WebTunnel crate tests, including 13 managed-client tests and actual helper stdout/exit | Passed |
| REQ-TUN10-DEADLINE | RLY-1794486421803541 | Readiness suite covers timeout, cancellation and preserved failure; included in 46 lifecycle tests | Passed |
| REQ-TUN10-CHANNEL | RLY-1794486422451074 | Actual shell process writes control-like stderr and more than one pipe buffer; included in 46 lifecycle tests | Passed |
| REQ-TUN10-STOP | RLY-1794486423091950 | Actual EOF, pipe drain and forced termination cases; included in 46 lifecycle tests | Passed |
| REQ-TUN10-SCOPE | RLY-1794486423721195 | Scope document reviewed; 109 combined service tests passed, including prior lifecycle, argument and endpoint regressions | Passed |
| REQ-TUN10-SCOPE | RLY-1794486424411769 | Independent review, combined staticAnalysis and ci-gates passed; main fast-forward observed at the source SHA | Passed |

## Finding disposition

The audit snapshot is `/Users/npochaev/Downloads/RIPDPI_network_audits/TUN-10.md` at source `3e626d3e205f27c90dd84a9732f1f96074ed15f1`. Repairs started from main `ee9a68b4f6300ff5f9359ba24562cc6873c1a355`. Existing TUN-07 fixes were retained.

| Audit item | Disposition | Commit |
|---|---|---|
| F1: raw PT used as general egress | Reject standalone Snowflake and WebTunnel; preserve legacy IDs and Tor bridge activation. obfs4 rejection already existed. | `0552aaae27`; existing `e96a3753d1` |
| F2: incomplete managed lifecycle | Existing version/method/DONE state machine and error handling retained; covered by combined regression run. | Existing `92378fc509` |
| F3: Lyrebird half-close | Remove the unsupported generic TCP promise with F1; document the Tor-link boundary. No upstream half-close implementation is claimed. | `ea9c2ca80b` and F1 |
| F4: PT argument escaping | Existing reserved-separator encoding retained; covered by combined regression run. | Existing `833374d53c` |
| F5: malformed endpoint | Existing numeric-loopback/port validation retained; covered by combined regression run. | Existing `92378fc509` |
| F6: WebTunnel negotiation/errors | Select version 1 from a valid list, emit normative errors, reject unsupported proxy configuration, initialize runtime before readiness. | `f5afd3060f` |
| Readiness budget | Apply the caller timeout without cancelling the shared readiness signal. | `6aa1e5d51d` |
| Diagnostic/control channels | Use stdout for managed PT control; drain and discard stderr in fixed-size chunks. | `b9ed07e8aa` |
| Shutdown | Close stdin, then use bounded TERM/KILL escalation within the existing 3-second wait budget. Keep output draining during exit. | `1bcc9b9c79` |
| Stop/admission race | Existing terminal admission lock and registration before upstream connect retained. | Existing `940c9a752d` |

## Observed repair checks

All heavy commands ran through `build-gate --lane ripdpi --`. Gradle used `--max-workers=4 -Pripdpi.skipNativeBuild=true` and a 5 GiB daemon heap. Rust used at most four jobs, `RUSTC_WRAPPER=sccache`, `CARGO_INCREMENTAL=0`, and a worktree-specific target directory.

- Activation RED: 12 external-family resolver tests, exactly two expected failures for Snowflake and WebTunnel. Tor tests passed for all three PT bridge kinds. Log: `/private/tmp/tun10-activation-red.log`.
- Activation GREEN: `./gradlew :core:service:testDebugUnitTest --tests '*UpstreamRelayRuntimeConfigResolverExternalFamiliesTest' :app:testGithubFullDebugUnitTest --tests '*ModeEditorScreenTest'` plus the common flags above. 12 service and 18 UI tests passed, no skips. Log: `/private/tmp/tun10-activation-green.log`.
- Locale/UI check: `./gradlew :app:lintGithubFullDebug :core:service:lintDebug :app:testGithubFullDebugUnitTest --tests '*RipDpiScreenCatalogScreenshotTest.modeEditorScreen' -Pripdpi.includeRoborazziUnitTests=true -Proborazzi.test.verify=true` plus common flags. Both lint tasks and one screenshot verification passed. The screenshot covers the relay-disabled view; the changed protocol grid is covered by the editor tests. No golden was changed. Log: `/private/tmp/tun10-activation-lint-screenshot.log`.
- Lifecycle RED: 20 tests, three expected failures for deadline, stderr handling and stdin shutdown. Log: `/private/tmp/tun10-lifecycle-red-nativeless.log`.
- Lifecycle GREEN: `./gradlew :core:service:testDebugUnitTest --tests '*SubprocessRelayReadinessSignalTest' --tests '*SubprocessRelayProcessSupervisorTest' --tests '*SubprocessRelayComponentsTest' :core:service:detekt` plus common flags. 46 tests passed: 10 readiness, 11 process supervisor, 25 component; no skips. Log: `/private/tmp/tun10-lifecycle-green-final.log`.
- WebTunnel RED: binary suite had five passes and three expected failures. A child-only descriptor limit also observed a premature CMETHOD/DONE before runtime initialization failed.
- WebTunnel GREEN from `native/rust`: `cargo test --locked -p ripdpi-webtunnel --jobs 4` passed all 44 tests. `cargo clippy --locked -p ripdpi-webtunnel --all-targets --jobs 4 -- -D warnings` passed. Existing vendor/h3 warnings remain. `cargo fmt --check -p ripdpi-webtunnel` passed. Clippy receipt: `/private/tmp/tun10-webtunnel-clippy-final.log`.
- Changed Kotlin files passed ktlint. Translation export check and normal commit hooks passed. No dependency, lockfile, baseline or golden changes were made.
- Independent `pr-reviewer` reviewed all four worktree diffs before commits and found no actionable issues. It checked lifecycle callback lock order as well as PT version/error requirements. The final ReturnCount lint rewrite retained the reviewed stop sequence.

One initial lifecycle test command omitted the native-build skip property and began an unnecessary native build. Its owned process was stopped (exit 130); the native-less RED/GREEN commands above supplied the actual test evidence. An initial GREEN lint error was fixed without a new suppression or baseline change.

## Host artifact and protocol oracle

Host helper: `/private/tmp/ripdpi-tun10-webtunnel-pt-target/debug/ripdpi-webtunnel`, Mach-O arm64, SHA-256 `e6898422b326b0395974a96513f3dbd873187e5a76d92f91aaa1405498d99bbf`. It was built from the worker tree whose three changed Rust files match the integrated source tree.

The tests run the actual helper process and compare stdout and exit status to literal PT-v1 transcripts. The descriptor-limit test changes only the child process limit. The error oracle is [PT v1 IPC](https://spec.torproject.org/pt-spec/ipc.html) and [configuration environment](https://spec.torproject.org/pt-spec/configuration-environment.html), checked against goptlib v1.6.0 from pinned Lyrebird `fc105a03c0e0acc2479301c361c012ffed359c43`.

## Combined verification

At source `1bcc9b9c7995b10199e5cf41e9a8e968a8d21252`, the following command passed (8m 15s, 849 tasks):

```sh
build-gate --lane ripdpi -- ./gradlew staticAnalysis \
  :core:service:testDebugUnitTest \
  --tests '*PluggableTransport*Test' --tests '*SubprocessRelay*Test' \
  --tests '*UpstreamRelayRuntimeConfigResolver*Test' \
  -Pripdpi.skipNativeBuild=true --max-workers=4 \
  '-Dorg.gradle.jvmargs=-Xmx5g -XX:+UseG1GC -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8'
```

XML reports contain 109 tests in eight suites, zero failures, errors or skips. Log: `/private/tmp/tun10-combined-gradle.log`. Together with the separate 18 editor tests, one screenshot verification and 44 Rust tests, this is 172 distinct passing tests/check cases; the 46 lifecycle and 12 resolver counts above are subsets, not additional cases.

The first `ci-gates` attempt passed all source checks before API snapshots. A temporary locked-Cargo wrapper incorrectly passed `--locked` to `cargo-public-api`, which does not accept that option. The wrapper was corrected outside the repository for the directly invoked metadata command. The installed API tool starts its own nightly cargo rustdoc without a locked option; the wrapper cannot reach that toolchain path. This is a tool limitation, not evidence that the nested command was locked. The rerun is offline and the lockfile is checked for changes. No source or dependency workaround was applied. The full gate rerun passed; all 12 host API snapshots match. The Linux-owned runtime-platform snapshot is intentionally skipped on Darwin by the gate.

Explicit `cargo metadata --manifest-path native/rust/Cargo.toml --locked --offline --format-version 1 --no-deps` passed. `python3 scripts/ci/check_architecture_health.py` passed: 18 existing indicators, zero new, worsened or stale entries.

The passing command was `build-gate --lane ripdpi -- env PATH=/private/tmp/tun10-locked-bin:$PATH CARGO=/private/tmp/tun10-locked-bin/cargo CARGO_TARGET_DIR=/private/tmp/ripdpi-tun10-contracts-target CARGO_BUILD_JOBS=4 RUSTC_WRAPPER=sccache CARGO_INCREMENTAL=0 CARGO_NET_OFFLINE=true just ci-gates`. Log: `/private/tmp/tun10-combined-ci-gates-final.log`. API snapshots, dependency inputs and baselines are unchanged.

Before integration, `git fetch origin && git rebase origin/main` reported the branch up to date. Main was clean at `ee9a68b4f6300ff5f9359ba24562cc6873c1a355`; `git merge --ff-only fix/tun10-contracts` advanced it to the recorded source SHA. The evidence-only commit follows that source. Remote CI is required and pending, so this task stays in review and is not archived.

## Evidence boundary

- C: current source and callers were reviewed; independent review found no actionable diff issue.
- V: host Rust helper execution, local process pipes/termination, JVM service/editor tests and one screenshot were observed.
- S: the listed PT v1 version/error transcripts were checked against primary requirements. This is not complete conformance certification.
- E: no external bridge or Android compatibility claim is made.

Still unverified: source-strict Android APK/installed-ABI identity; Arti bootstrap and two controlled final destinations through each external bridge; Android process death/cancellation and cross-UID helper isolation; VPN carrier protection. External servers, physical devices, root activation and real secrets were not used. Lyrebird generic TCP half-close remains unsupported; supported Tor links do not promise it.
