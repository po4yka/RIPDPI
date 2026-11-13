---
task_id: ENG-1794557051102648
change: seqovl-audit-remediation
commit_sha: f6dc39891ed3da485985f9bf462e13744654afe1
local: passed
local_evidence: "Rust1.98.1 --locked: 1071 host tests; model80/data995; staticAnalysis; ci-gates; Linux raw17/protocol45/platform61/shared-restore3/direct-fallback1 and Clippy; TLS multirecord integration1; formatting; independent review passed."
remote_ci: required
remote_ci_evidence: null
device: blocked
device_evidence: Root and physical acceptance are not authorized in this request; no external compatibility claim.
artifact: not_applicable
artifact_evidence: No release or APK artifact is owned by these contract fixes.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SEQOVL-FLAGS | ENG-1794557264007873 | Flag dispatch regression, planner action tests, independent raw packet byte/checksum oracle | Passed locally; external acceptance unverified |
| REQ-SEQOVL-RECEIPT | ENG-1794557265150554 | Exact peer bytes through EOF, split receipt and partial-prefix accounting regressions | Passed locally; external acceptance unverified |
| REQ-SEQOVL-CHAIN | ENG-1794557267490390 | CLI and JSON parser suites plus Kotlin model validation tests | Passed locally; external acceptance unverified |
| REQ-SEQOVL-RESTORE | ENG-1794557269233526 | Pre-emission, raw attempt, queue commit and repair-restoration failure regressions | Passed locally; external acceptance unverified |
| REQ-SEQOVL-MTU | ENG-1794557270135639 | Full packet/MSS/MTU budget and direct atomic EMSGSIZE regression tests | Passed locally; external acceptance unverified |
| REQ-SEQOVL-HELPER | ENG-1794557270998739 | JSON metadata round trip, real Unix-stream reply loss/decode/deadline tests, retry classification | Passed locally; external acceptance unverified |
| REQ-SEQOVL-CAPABILITY | ENG-1794557271939729 | Eight-case repair/raw-family truth table and registered helper fixture | Passed locally; external acceptance unverified |
| REQ-SEQOVL-BOUNDARY | ENG-1794557272952551 | Activation/source gates, independent review and unprivileged-only local execution | Passed locally; external acceptance unverified |

## Regression evidence

The original audit is `/Users/npochaev/Downloads/RIPDPI_network_audits/DPI-12.md` at audit SHA `7cdc4bd49de465a85058236132ecba3597b76638`. This change fixes its six P2 findings and the P3 capability gap. It does not turn unit evidence into external DPI compatibility.

- F1: pinned RED reproduced the plain path with fake flags. The regression checks overlap dispatch and configured flags. Production packet-builder tests check independently decoded IPv4/IPv6 header flags, payload, sequence wrap, TTL and checksum.
- F2: peer tests read the exact genuine bytes through EOF. Receipts identify split after a safe refusal. Partial-prefix progress is retained when the remainder fails; one action completes only after all writes complete.
- F3: CLI, JSON and Kotlin tests reject a following send step and retain valid prelude cases. Validation stays within the existing source budgets; no baseline was changed.
- F5: tests distinguish refusal before emission, an uncertain raw attempt, queue-commit failure, and repair-restoration failure. Only proven no-emission with successful restoration can permit replay.
- F4: tests include the fake prefix and packet headers in size limits, plus known MTU/MSS boundaries. Only the direct atomic EMSGSIZE refusal is safe; nested or uncertain errors remain terminal.
- F6: JSON round trips preserve four explicit safe refusal kinds. Unknown, malformed and legacy metadata stays terminal. Real Unix-stream tests cover response loss, invalid JSON/version and elapsed reply deadlines after delivery. FD replacement and first-write classification cannot turn an uncertain commit into a request retry.
- G1: the full eight-case repair/raw-family truth table is covered. The helper fixture checks registered capability combinations.

## Additional observed checks and limits

The first host combined nextest run passed 1031 tests in ten packages with `--locked --no-fail-fast --test-threads 4`; no tests were skipped. The doc-test command passed with no doc tests defined. The model Kotlin suite passed 78 tests before the main rebase. Online `staticAnalysis -Pripdpi.skipNativeBuild=true` passed 826 tasks. Its initial offline attempt could not resolve uncached Maven test/lint artifacts.

Unprivileged Linux tests used pinned Rust 1.98.1 in a read-only checkout, with Docker `--network none --cap-drop ALL --user 65534:65534`. No root capability was activated.

- Narrow privileged raw-module suite: 19 passed. Linux Clippy all targets passed with warnings denied.
- Helper protocol: 45 passed. Runtime platform: 59 passed, including the reply policy tests.
- A broader privileged-ops run passed 103 tests and failed three BPF attachment tests with EPERM under the capability restriction. These are not reported as a passing full suite.
- The full Linux root-helper binary suite first passed 63 and failed four timeout-sensitive nfqws2 fixtures in parallel. The same complete suite with one test thread passed all 67. No nfqws2 code or timeout was changed; the first failure does not establish its root cause.
- Miri Tree Borrows could not build a C dependency for aarch64 Linux because `aarch64-linux-gnu-gcc` was absent. No Miri test ran and no UB conclusion is drawn.

Independent read-only review covered runtime receipts/flags, config/capability, helper error policy, and raw send safety. The unsafe review found no new or changed unsafe block and no issue with the existing raw-fd/layout invariants in the reviewed delta.

## Remaining acceptance

Root TCP_REPAIR/raw emission, direct versus helper, actual route MTU/EMSGSIZE, and repair fault injection on an owned device remain unrun. These checks need explicit root/device permission. Test IPv4 and IPv6, timestamp/MD5 policy, overlap 1/32, sequence wrap, both root routes, and peer application bytes through EOF. Compare captured source packets with independent decoding and the real peer stream. No remote-server or real DPI/OS reassembly compatibility is established (E remains unverified). C and local V improved; the audit S evidence is unchanged.

Keep the portfolio task in review while device and remote CI evidence remain incomplete. Do not archive this change from local tests alone.

## Verification commands and earlier runs

From `native/rust`, with Rust/Cargo 1.98.1:

```sh
cargo nextest run --locked --no-fail-fast --test-threads 4 -p ripdpi-config -p ripdpi-proxy-config -p ripdpi-desync -p ripdpi-desync-runtime -p ripdpi-privileged-ops -p ripdpi-root-helper-protocol -p ripdpi-root-helper -p ripdpi-runtime-platform -p ripdpi-failure-classifier -p ripdpi-proxy-runtime-desync-adapter
cargo test --locked --doc -p ripdpi-config -p ripdpi-proxy-config -p ripdpi-desync -p ripdpi-desync-runtime -p ripdpi-privileged-ops -p ripdpi-root-helper-protocol -p ripdpi-root-helper -p ripdpi-runtime-platform -p ripdpi-failure-classifier -p ripdpi-proxy-runtime-desync-adapter
```

From the rebased integration checkout:

```sh
just ci-gates
./gradlew :core:data:model:testDebugUnitTest staticAnalysis -Pripdpi.skipNativeBuild=true
```

The final host nextest result is 1046 passed, zero failed/skipped. The doc command passed with zero defined cases. The rebased `just ci-gates` passed. Logs: `/private/tmp/dpi12-postrebase-final-rust-nextest.log`, `/private/tmp/dpi12-postrebase-final-rust-doc.log`, `/private/tmp/dpi12-ci-gates-rebased.log`.

Linux commands, inside the restricted container described above:

```sh
cargo test --offline --locked -p ripdpi-privileged-ops linux::raw_packet -- --test-threads 1
cargo test --offline --locked -p ripdpi-runtime-platform -p ripdpi-root-helper-protocol --lib
cargo clippy --offline --locked -p ripdpi-privileged-ops -p ripdpi-runtime-platform -p ripdpi-root-helper-protocol -p ripdpi-root-helper --all-targets -- -D warnings
```

The runner is `/private/tmp/dpi12-linux-rebased.py`; its log is `/private/tmp/dpi12-linux-rebased.log`. This is Linux local evidence, not Android/root emission evidence.

The rebased Kotlin/model and static-analysis command passed: 78 model tests, zero failed/errors/skipped; Gradle reported BUILD SUCCESSFUL with 832 tasks. Log: `/private/tmp/dpi12-kotlin-rebased.log`.

The rebased Linux command sequence passed: raw module 19 tests, helper protocol 45, runtime platform 59; zero failed/ignored in these selected suites. Clippy all targets passed with warnings denied. The broader BPF-restricted and parallel nfqws2 results above remain separate observations.

Rebase integration preserved incoming fakerst and random-record changes. Independent review approved the sole textual conflict resolution: keep FakeRst special execution and the SeqOverlap flag-dispatch exclusion. Source gates passed without changing baselines, dependency versions, lockfiles or activation policy.

Additional real Linux adapter check, in the same capability-free container:

```sh
cargo test --offline --locked -p ripdpi-desync-runtime tcp::tests::action_execution::actions_seqovl_fallback_to_split -- --exact --nocapture
```

Result: one selected existing test passed; 172 were filtered. The real TCP peer read `abcd` from the production Linux action path and did not read the configured `xx` fake prefix in those bytes. This test does not capture raw wire emission or read through EOF. The separate receipt regressions cover exact bytes through EOF. Log: `/private/tmp/dpi12-linux-direct-fallback.log`.

The F6 commit passed all normal hooks, including Clippy. The initial default-lane commit attempt was cancelled during a shared-slot wait; Clippy had not run in that attempt. Its staged patch remained unchanged (SHA256 `407b92115946288856572936fbfdb22fdf30947c57099bec86aeb3c40e614ba8`). The final commit used an owner-approved build slot with all normal hooks. Log: `/private/tmp/dpi12-f6-final-commit.log`.

## Earlier integration revalidation

The DPI-13 rebase retained the upstream shared `finish_tcp_repair` and its three restoration tests. Seqovl uses that helper without adding a duplicate. Both multidisorder and seqovl receipt modules remain enabled. Independent review approved this merge.

After the DPI-13 rebase, nextest passed 1055 tests. The full Gradle command below passed model80 and data995 tests, with zero failures/errors/skips; staticAnalysis passed (852 tasks). Log: `/private/tmp/dpi12-kotlin-final2.log`.

```sh
./gradlew :core:data:model:testDebugUnitTest :core:data:testDebugUnitTest staticAnalysis -Pripdpi.skipNativeBuild=true
```

The later DPI-09 rebase at `77b07adcad` changed Rust and documentation only; Kotlin inputs to that Gradle invocation remain unchanged. Final host nextest passed 1056 tests, zero failed/skipped, on the complete source. Doc tests passed with zero defined cases. `just ci-gates` and `cargo fmt --all --check` passed. Logs: `/private/tmp/dpi12-final3-rust-nextest.log`, `/private/tmp/dpi12-final3-rust-doc.log`, `/private/tmp/dpi12-ci-gates-final3.log`, `/private/tmp/dpi12-final3-format.log`.

Final Linux gates passed: raw17, protocol45, platform61, shared repair-restoration3 and direct production-adapter fallback1. All selected tests passed, and Clippy all targets passed with warnings denied. The two earlier raw restoration tests moved upstream to their shared owner, so raw17 replaces raw19; no test was disabled. Logs: `/private/tmp/dpi12-linux-final3.log`, `/private/tmp/dpi12-linux-final3-restoration.log`. The broader BPF permission failure and parallel nfqws2 results above remain separate observations.

## Current integration result

The final source is rebased onto `f52e13ef78` and preserves the incoming DPI-09, DPI-10, DPI-11 and DPI-13 repairs. The last rebase had no conflicts. The model/data sources and inputs to the Gradle invocation remain unchanged from the successful model80/data995/staticAnalysis run.

Final host nextest: 1071 passed, zero failed/skipped, 21 binaries. Doc command passed with zero defined cases. Formatting and `just ci-gates` passed. Final Linux raw17/protocol45/platform61/shared-restoration3/direct-fallback1 and all-target Clippy passed. Logs: `/private/tmp/dpi12-final4-rust-nextest.log`, `/private/tmp/dpi12-final4-rust-doc.log`, `/private/tmp/dpi12-ci-gates-final4.log`, `/private/tmp/dpi12-final4-format.log`, `/private/tmp/dpi12-linux-final4.log`, `/private/tmp/dpi12-linux-final4-restoration.log`.

The production adapter boundary was also checked with a real loopback peer and three bundled TLS inputs:

```sh
cargo test --locked -p ripdpi-proxy-runtime-desync-adapter --test tls_record_multirecord
```

Result: one passed test, no failures/ignored/filtered; no external host, root or real secret was used. This target belongs to the adapter crate, not the proxy-runtime crate. Log: `/private/tmp/dpi12-final4-tls-record-multirecord.log`. External DPI/OS compatibility remains unverified.

## Finding commits

| Finding | Commit | Change |
|---|---|---|
| F1 | `61c64ed067a89bc5cd5ea8e33d7f60cc2e0d9c9b` | fix(seqovl): preserve overlap sending with TCP flags |
| F2 | `3b009e9effa597a0641654fb8a631164cb4e9ed1` | fix(seqovl): report effective split fallback and progress |
| F3 | `64b9a157321b4c1520dda550e270ed9f8747db6a` | fix(seqovl): reject send steps after terminal overlap |
| F4 | `6b14ce80640e7fe5c5ae6c84b7c047788b87b307` | fix(seqovl): reject packets beyond TCP size budgets |
| F5 | `1b1faca785b51b93afb31811c29b0a3a54cbfd01` | fix(seqovl): stop replay after uncertain raw send |
| F6 | `f6dc39891ed3da485985f9bf462e13744654afe1` | fix(seqovl): preserve helper refusal and commit safety |
| G1 | `adc1ef03b29230c8b6385386ef3126df924c96b9` | fix(seqovl): require raw access in the capability probe |
