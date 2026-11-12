---
task_id: TRN-1794489808420764
change: tun09-argument-repair
commit_sha: 22ce45bd96fc7ce6cdc5c97d2d28459a829d3f42
local: passed
local_evidence: Owning Rust and JVM tests, lint, independent review and combined source gates passed.
remote_ci: required
remote_ci_evidence: Checks for the final pushed revision have not been observed.
device: not_applicable
device_evidence: Parser repair does not change Android socket or route lifecycle. Physical and live Tor acceptance remain unverified.
artifact: passed
artifact_evidence: Host arm64 helper built and executed; all three reference WebTunnel loopback cases passed. Android packaging is not covered.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TUN09-BOUNDARIES | TRN-1794489899877262 | Five new regressions failed before the fix and passed after it; unknown options no longer pass through bridge-line tokenization | Passed |
| REQ-TUN09-GRAMMAR | TRN-1794489899877262 | Pinned goptlib vectors, first-value duplicate tests and malformed-entry tests; all 49 owning crate tests passed | Passed |
| REQ-TUN09-SCOPE | TRN-1794489900533410 | 76 JVM/UI tests, managed helper tests, three independent reference loopback cases and read-only review passed; main fast-forward observed at the recorded source SHA | Passed |

## Finding disposition

Audit: `/Users/npochaev/Downloads/RIPDPI_network_audits/TUN-09.md`, source `3e626d3e205f27c90dd84a9732f1f96074ed15f1`. Repair base: `48b7b184d716abd47cb0f0c9b5859984150c5fd4`.

| Finding | Repair | Commit |
|---|---|---|
| F1: raw PT used as general TCP egress | Current main rejects standalone WebTunnel, hides its selection chip and retains Tor bridge activation. Existing repair was revalidated, not duplicated. | `0552aaae27948fd626b471f9cdf1cc0e841197f4`; scope document `ea9c2ca80b03b8d07b5c6a0b47cd9e1095f76dfe` |
| F2: ignored TOR_PT_PROXY | Current helper emits PROXY-ERROR and exits before listener setup for unsupported proxy configuration. | `f5afd3060fb43f70c9d7cfed6854687df4505b88` |
| F3: version-list and IPC errors | Current helper selects version 1 from a valid list and emits typed protocol errors. Existing F2/F3 repair was revalidated, not split or duplicated. | `f5afd3060fb43f70c9d7cfed6854687df4505b88` |
| F4: lost argument boundaries | Shared semantic validation consumes parsed pairs directly. Escaped delimiters, opaque values and first-value selection match pinned goptlib. Empty entries fail. | `22ce45bd96fc7ce6cdc5c97d2d28459a829d3f42` |

The original library-task hypothesis does not establish a defect in the supported subprocess lifecycle. No embedding contract is added. Android readiness, stderr and stop repairs already exist on the base revision and passed the selected lifecycle suite.

## Observed commands

Heavy commands ran through `build-gate --lane ripdpi --`. Rust used `RUSTC_WRAPPER=sccache`, `CARGO_INCREMENTAL=0`, four jobs and target `/private/tmp/ripdpi-tun09-pt-arguments-target`.

```sh
cargo test --manifest-path native/rust/Cargo.toml --locked \
  -p ripdpi-webtunnel --jobs 4 --test bridge_line --test pt_managed_client --no-fail-fast
cargo test --manifest-path native/rust/Cargo.toml --locked -p ripdpi-webtunnel --jobs 4
cargo clippy --manifest-path native/rust/Cargo.toml --locked \
  -p ripdpi-webtunnel --all-targets --jobs 4 -- -D warnings
cargo fmt --manifest-path native/rust/Cargo.toml -p ripdpi-webtunnel --check
```

- RED: 18 passed, five new regressions failed. Log: `/private/tmp/tun09-pt-red.log`.
- GREEN: 49 passed, zero failed or skipped. Log: `/private/tmp/tun09-pt-green-3.log`.
- Clippy, formatting and `git diff --check` passed. Log: `/private/tmp/tun09-pt-clippy.log`.
- An initial compile attempt contained a character-literal typo, corrected before the final tests. Its log remains in `tun09-pt-green.log`.
- The first full test attempt had one existing stdin-close test timeout at five seconds (`tun09-pt-green-2.log`). The complete retry passed. A separate read-only lifecycle review found no blocking path attributable to this parser diff. Twenty sequential runs of the existing exact test then passed without changing its deadline; maximum process duration was 0.1122 seconds, total 1.7696 seconds. Log: `/private/tmp/tun09-stop-repeat.log`. The first timeout cause is unknown; this does not prove stability under all concurrent loads.

The JVM command was:

```sh
./gradlew :core:service:testDebugUnitTest \
  --tests '*UpstreamRelayRuntimeConfigResolverExternalFamiliesTest' \
  --tests '*SubprocessRelayReadinessSignalTest' \
  --tests '*SubprocessRelayProcessSupervisorTest' \
  --tests '*SubprocessRelayComponentsTest' \
  :app:testGithubFullDebugUnitTest --tests '*ModeEditorScreenTest' \
  --max-workers=4 -Pripdpi.nativeCpuBudget=4 -Pripdpi.skipNativeBuild=true \
  '-Dorg.gradle.jvmargs=-Xmx5g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:SoftRefLRUPolicyMSPerMB=50 -XX:MaxMetaspaceSize=1g -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8' \
  --console=plain
```

It passed 76 tests with zero failures, errors or skips: 12 resolver, 10 readiness, 11 supervisor, 25 component and 18 editor tests. Duration: 3m45s. Native compilation was explicitly disabled for this JVM run; it provides no JNI or device evidence. Log and exact wrapper: `/private/tmp/tun09-existing-kotlin-tests.log` and `.sh`.

Independent `pr-reviewer` found no actionable issue in the four-file code diff. It checked the common validator, escaping, duplicate policy and error paths against goptlib v1.6.0. No runtime, async, unsafe, JNI, dependency, schema, locale, golden or baseline change is included.

## Artifact and independent oracle

The host helper is `/private/tmp/ripdpi-tun09-pt-arguments-target/debug/ripdpi-webtunnel`, Mach-O arm64. SHA-256: `78b725d5d28dd8e2964da7f88bbcaa399c845bcefd52088b0d0b9fbe4ff859db`. Its four edited source/test files match the coordinator tree byte for byte.

The goptlib oracle is v1.6.0 from pinned Lyrebird `fc105a03c0e0acc2479301c361c012ffed359c43`: `args.go` and `args_test.go`, stored in the existing PT build module cache. `Args.Get` selects the first repeated value; `parseClientParameters` rejects empty entries, while empty whole input reaches semantic validation.

The original audit's independently built reference WebTunnel v0.0.3 server was reused on loopback only. The Python driver was copied into `/private/tmp/tun09-repair-interop-9wow6wvw` and pointed at the repaired helper; the audit evidence was not overwritten. `python3 /private/tmp/tun09-repair-interop-9wow6wvw/interop.py` passed all three modes: no ALPN, HTTP/1.1 and h2 followed by a fresh HTTP/1.1 connection. Each returned all 48 bytes unchanged and exited on stdin EOF with status 0. The h2 connection carried zero application bytes. The server observed SNI `cover.test`, distinct from the URL and certificate-domain names; its two-certificate hash-chain pin authenticated the connection. This is concrete local reference compatibility, not external Tor bootstrap acceptance.

## Combined source gates

`CARGO_BUILD_JOBS=4 CMAKE_BUILD_PARALLEL_LEVEL=4 CARGO_INCREMENTAL=0 RUSTC_WRAPPER=sccache CARGO_TARGET_DIR=/private/tmp/ripdpi-tun09-source-gates-target build-gate --lane ripdpi -- just ci-gates` passed. Log: `/private/tmp/tun09-ci-gates.log`. All host API snapshots match; the runtime-platform snapshot is Linux-owned and is skipped by the gate on Darwin. The API tool starts its own Cargo command without a locked option; this nested tool limitation is not evidence of a locked invocation. Dependency inputs, lockfile, snapshots and baselines remain unchanged.

Explicit `cargo metadata --manifest-path native/rust/Cargo.toml --locked --no-deps --format-version 1` also passed. Architecture health in the combined gate has 18 existing indicators, zero new, worsened or stale entries.

## Integration

The normal commit hooks passed. `git fetch origin` and `git rebase origin/main` left the reviewed source unchanged. Main was clean at `48b7b184d716abd47cb0f0c9b5859984150c5fd4`; `git merge --ff-only fix/tun09-webtunnel` advanced it to `22ce45bd96fc7ce6cdc5c97d2d28459a829d3f42`. This evidence-only commit follows the source commit. Remote CI remains required, so the task stays in review and is not archived.

The preserved logs, patch and checksums are in `/Users/npochaev/Downloads/RIPDPI_network_audits/TUN-09-repair-evidence-20261112-173327/`. The initial audit and registry are unchanged. A separate repair addendum is `TUN-09-remediation-20261112.md` beside the original report.

## Evidence boundary

- C: current source and callers reviewed, including independent review.
- V: Rust helper, process shutdown and JVM/UI behavior observed locally.
- S: parser behavior checked against pinned goptlib and existing managed PT-v1 contracts; no full conformance certification.
- E: three local reference WebTunnel cases passed; external bridge and Android compatibility remain unverified.

Still unverified: Android APK/installed-ABI identity, Arti bootstrap and final destinations through an external bridge, physical-device VPN routes and process cancellation, and the cause of the single host stdin-close timeout. No physical device, root activation, external probe or real secret was used. Remote CI must be observed separately; the task is not ready for archival while it remains required.
