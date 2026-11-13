---
task_id: RST-1794557321446019
change: repair-dpi14-fragmentation
commit_sha: 4121395240a42d47cd9abbca54400c506467485a
local: passed
local_evidence: Combined Darwin tests passed (1125 Rust and 66 Kotlin), affected Clippy, fmt, locked metadata, and just ci-gates passed. See commands and logs below.
remote_ci: not_applicable
remote_ci_evidence: No hosted workflow was dispatched. Local source gates are required and passed.
device: not_applicable
device_evidence: Device and root activation are not authorized. Privileged wire and Android route acceptance remain unverified.
artifact: passed
artifact_evidence: Four affected Android packages compile; Linux IPC, all-targets Clippy, canonical API and 28 independent packet reassembly cases passed. Three unrelated BPF attach tests fail under cap-drop and are reported below.
deployment: not_applicable
deployment_evidence: This change does not deploy an application or service.
---

# Verification

The implementation resolves the nine DPI-14 findings. Each finding has a separate implementation commit. Independent read-only reviews covered each unit and the combined source at the exact SHA above. The original audit report is unchanged. Follow-up platform corrections were folded into their owning findings. The committed tree hash before and after this history-only operation was identical: eef4277a955dd9b1ffde90015f5eba52df200039. No tested source bytes changed during the fold.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DPI14-F1 | RST-1794557459134025 | Commit 3e78182594. Kotlin and Rust reject another send step before ipfrag2. TLS preludes remain valid. Both languages had an observed failing regression before the fix. | passed |
| REQ-DPI14-F2 | RST-1794557459773519 | Commit b0686b2d15. Sender queues payload inside TCP_REPAIR and does not perform a second ordinary initial write. Typed send-phase and cleanup tests passed. Real privileged wire behavior is not observed. | passed |
| REQ-DPI14-F3 | RST-1794557460478261 | Commit 2e976702e9. Real loopback receives only the written prefix after injected opaque errors; the result is uncertain and cannot permit fallback. Masked round-one and lowered round-two paths are covered. The real non-Linux adapter preserves plain fallback. | passed |
| REQ-DPI14-F4 | RST-1794557461255765 | Commit 7594f8685c. Planner and runtime regressions cover explicit round two and offsets zero/end. The complete payload reaches loopback without a fragment sender call. | passed |
| REQ-DPI14-F5 | RST-1794557462331060 | Commit 1bc5ba6f90. All five IPv6 profiles and both orders reach the real packet builder through legacy runtime. Protocol v4 parameters, Kotlin callers and command fixtures pass. Linux profile and error IPC fixtures passed. | passed |
| REQ-DPI14-F6 | RST-1794557463263982 | Commit 78ab66c156. Strict error metadata preserves normalized kind, original errno and the emission boundary. Legacy and malformed data cannot prove pre-send safety. Host DTO and handler tests passed. Linux profile and error IPC fixtures passed. | passed |
| REQ-DPI14-F7 | RST-1794557464023170 | Commit 9f9006a92e. Tests cover capability normalization, terminal protection errors, both fragment MTU limits and original-packet size rejection. Connected raw socket queries the route MTU before either send. Android compilation passed. | passed |
| REQ-DPI14-F8 | RST-1794557464717320 | Commit 4121395240. Bounded leases exclude ports, use the IPv4 protocol and share the IPv6 namespace across protocols. Tests cover collisions, wrap, expiry, capacity, concurrency and generated packet IDs. | passed |
| REQ-DPI14-F9 | RST-1794557465604641 | Commit 296e1583a9. Max/max-plus-one tests cover IPv4 and all five IPv6 profiles, with timestamps on and off. The pure builder rejects impossible original lengths. | passed |

## Observed host checks

All direct Cargo resolution commands used `--locked`. Heavy commands used `build-gate --lane ripdpi`; direct Cargo jobs were limited to four. Compilation ran from `native/rust` with pinned Rust 1.98.1.

```sh
cargo test --jobs 4 --locked -p ripdpi-ipfrag -p ripdpi-desync -p ripdpi-desync-runtime -p ripdpi-proxy-config -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-runtime-platform -p ripdpi-root-helper-protocol -p ripdpi-root-helper -p ripdpi-privileged-ops -p ripdpi-config -p ripdpi-failure-classifier
cargo test --jobs 4 --locked -p feature-contract-harness root_helper_command
cargo clippy --jobs 4 --locked -p ripdpi-ipfrag -p ripdpi-desync -p ripdpi-desync-runtime -p ripdpi-proxy-config -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-runtime-platform -p ripdpi-root-helper-protocol -p ripdpi-root-helper -p ripdpi-privileged-ops -p ripdpi-config -p ripdpi-failure-classifier --all-targets -- -D warnings
cargo fmt --all -- --check
cargo metadata --locked --format-version 1 --no-deps
```

Result: 1125 Rust tests passed, zero failed or ignored. Clippy, fmt and metadata passed. The total includes two feature-contract tests.

```sh
env -u CARGO_BUILD_JOBS CMAKE_BUILD_PARALLEL_LEVEL=4 ./gradlew :core:data:testDebugUnitTest --tests 'com.poyka.ripdpi.data.StrategyChain*' :core:service:testDebugUnitTest --tests 'com.poyka.ripdpi.services.RootHelperManagerTest' --tests 'com.poyka.ripdpi.services.RootHelperNfqwsTest' --tests 'com.poyka.ripdpi.services.NfqwsBackendTest' --max-workers=4 -Pripdpi.nativeCpuBudget=4 -Pripdpi.skipNativeBuild=true
just ci-gates
```

Result: 40 data tests and 26 service tests passed, zero failed or skipped. All source gates passed. Architecture health had 18 existing indicators and zero new, worse or stale entries. Darwin API checks passed; the canonical runtime-platform snapshot is Linux-owned and is checked separately. The native-build skip property applies only to this Kotlin test run.

Local log: `/tmp/dpi14-combined-host-rebased.log`, ending in `DPI14_COMBINED_HOST_EXIT=0`. Per-unit logs include `/tmp/dpi14-f1-{rust,kotlin}-{red,green}.log`, `/tmp/dpi14-f4-commit.log`, `/tmp/dpi14-f9-{red,green,clippy}.log`, `/tmp/dpi14-f3-final-checks.log` and `/tmp/dpi14-helper-f6-checks-commit.log`. These temporary logs are local evidence, not permanent CI artifacts.

## Native and packet artifacts

The final combined source passed `cargo check --jobs 4 --locked -p ripdpi-privileged-ops -p ripdpi-runtime-platform -p ripdpi-root-helper -p ripdpi-proxy-runtime-desync-adapter --target aarch64-linux-android` with NDK 29.0.14206865 and API 27. The first attempt lacked the NDK compiler environment; the corrected attempt passed. The per-unit attempts are preserved in `/tmp/dpi14-f7-f8-finalize.log` and `/tmp/dpi14-f7-f8-cross-finalize.log`. The final combined check is recorded in `/tmp/dpi14-combined-android-check.log`; the final source-gate/commit package records its freshness check in `/tmp/dpi14-combined-android-final.log`. This is a compilation result, not an Android device run.

The immutable production source was first checked at be0a754d9c834f5207daaa557a07774c7f233941. Later checks use 8b025ac6d37bf3bd2a9dee98cf219af6dabe4fd1 plus reviewed follow-up patch SHA256 5ddf963d63d49c206504150fdd3725fc6c09ef4751d8984273cf9389e4dec070. The follow-up contains only an unused import removal, an equivalent let-else, the typed capability-denial test and the generated API marker export.

Linux runs use a local image pinned to sha256:ed5275a26124886740857379e1fde447a065b700b15af93120277fa4127e12dc, UID/GID 1000, no network, no capabilities and no-new-privileges. Cargo uses public-only offline caches and the pinned 1.98.1 Linux toolchain. The initial runner needed an explicit installed toolchain name to avoid an offline rustup sync; that environment failure is retained separately.

Observed final checks so far: all-targets Linux Clippy passed for nine affected crates; protocol tests passed 66/66, root-helper client tests passed 34/34, and root-helper passed 70/70. The client suite includes the actual SCM_RIGHTS profile fixture and all three new Unix error-response fixtures. Final runtime ipfrag tests also passed 6/6. The exact capability-denial test passed 1/1 and the fragmentation contract prefix passed 15/15. All of these suites had zero ignored tests. The canonical Linux API recheck passed and Cargo.lock retained its original hash. The full privileged-ops suite reported 121 passed and three known BPF attach failures, with zero ignored tests; its native Cargo exit code 101 is retained in final-privops-full.exit. The final driver exited zero only after it verified the exact three failure names and all DPI-14 checks; it did not rewrite that failed-suite outcome.

The actual packet builder and an independent byte/checksum oracle plus TShark passed 28 PCAP cases and the oversized IPv4 rejection. The builder crate and its locked packet dependencies are unchanged by the final rebase, so this evidence remains valid. V here is packet construction and independent reassembly, not privileged transmission.

The canonical Linux API initially differed by the intended IpFragmentSendError reexport. The owning generator updated it and its recheck passed. The generated file has SHA256 56ac0fee06ad266fb037fcb656fc000298f3f4ebaafb0d6bb0233898cf00f019. No API baseline was edited by hand.

The full unprivileged privileged-ops run initially exposed a stale raw-IPv6 error expectation; the test now requires the typed pre-send capability error and original EPERM/EACCES. A subsequent run passed that test. Three existing BPF attach tests fail with EPERM under cap-drop; no filter, capability, skip or production guard was changed to hide them. The complete failure logs are retained, and this report does not claim the full Linux suite passed.

Final Linux log: `/tmp/dpi14-final-verification-efZtvb/linux-final.log`, with an observed final runner exit code of zero. The full-suite outcome remains separate in `final-privops-full.log` and `final-privops-full.exit`.

Local artifact root: `/tmp/dpi14-final-verification-efZtvb`. It contains source checkpoints, Linux logs, initial API failure and recheck logs, raw vectors, 28 PCAP files, and oracle-results.json. These artifacts are temporary.

## Evidence boundary and remaining acceptance

- C: current source and callers were checked. Independent precommit and combined integration review found no remaining actionable source issue.
- V: local parser, planner, loopback, error-contract and packet-builder runs are reported only where observed. A test sender that emits builder bytes on loopback does not establish raw transport behavior.
- S: the audit checked IPv4/IPv6 length, checksum, offset and reassembly-ID requirements. These checks do not assert full RFC conformance.
- E: no external compatibility run was performed.

Do not infer privileged transport acceptance from the host checks. The remaining explicit checks require separate authorization:

1. On an owned Linux/Android endpoint, capture a successful raw pair and verify that the original payload is not also sent as an ordinary initial TCP segment. Verify send-sequence advancement and retransmission after loss.
2. Force an MTU below either fragment length and verify that neither fragment leaves before safe fallback. Then lower PMTU between sends and verify terminal uncertainty without replay.
3. Exercise all five IPv6 profiles, both orders, negotiated timestamps and original flag masks through the real root helper. Verify packet reassembly and Android protected-route selection.
4. Inject first/second-send, repair-queue, cleanup and shutdown failures on a real repair socket. Verify repair exit, socket closure and bounded stop time.

The ID pool guarantees reservations only within one sender process and its conventional 120-second retention interval. Leases do not persist across process restart. The interval does not prove a universal maximum datagram lifetime. Multi-process/restart acceptance and paths that retain datagrams longer remain outside the observed result.

The audit hypotheses are treated separately: the nonzero authoritative ID pool removes the default-zero construction risk in this sender; cleanup errors now remain visible and uncertain; live ACK/stop races and helper stop latency are still unmeasured; IPv6 maximum reassembly length is covered by the new boundary tests.
