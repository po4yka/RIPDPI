---
task_id: VPN-1794196711383747
change: repair-root-helper-ipc
commit_sha: 2b1bed16e1a93d1585a0a7250a53e2b4b20a5139
local: blocked
local_evidence: All repair regressions, 38 Kotlin tests, combined Clippy and ci-gates passed. The full Linux suite had 231 passes and three unchanged BPF attach EPERM failures in the unprivileged container. It is not a full-suite pass.
remote_ci: required
remote_ci_evidence: Hosted CI for the delivered commit has not been observed.
device: blocked
device_evidence: No root activation, Android transport acceptance, physical device or external probe was authorized or run. Host tests do not establish these gates.
artifact: passed
artifact_evidence: ARM64 buildRustRootHelper passed after the rebase; the helper SHA256 is f1ae45f2cd0317471183bfd42794a5b04f471ec20edb467f5f6beb9fccbead7e. Other Android ABIs and installed loading were not tested.
deployment: not_applicable
deployment_evidence: This repair does not deploy a service or publish a release.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-IPC-CLOEXEC | VPN-1794196899260030 | Real Linux flags, exec-child exclusion, working TCP bytes, same-number ownership and isolated replacement failure passed. Android uses the same SYS_dup3 operation. | passed |
| REQ-IPC-LIMITS | VPN-1794196899730212 | Overflow, unbacked length, range, coverage, zero and overlap regressions passed; real frame and SCM_RIGHTS failure closes the socket and the next preflight succeeds. | passed |
| REQ-IPC-DEADLINE | VPN-1794196900171780 | Late buffered request, descriptor cleanup, total response deadline and real preflight trickle regressions passed. | passed |
| REQ-IPC-SOCKET | VPN-1794196900171780 | Real file, Unix, wrong transport and unconnected TCP inputs are rejected; IPv4/IPv6 TCP and UDP positives passed. | passed |
| REQ-IPC-COMPAT | VPN-1794196900616146 | Existing F3/F4 repair 6808d8e91, 38 owning Kotlin tests, current non-root callers and native fallback tests checked; device acceptance remains blocked above. | passed |

## Observed gates

The code SHA above includes all five repairs, the Android syscall correction and the FD test-oracle correction. It is based on origin/main c2d39ef31. Incoming TLS profile and bundled BoringSSL changes caused a fresh repeat of native tests, Clippy, ci-gates and the ARM64 build. Later changes in this worktree only record evidence and task state.

- Final Linux command: `cargo test --locked --offline --jobs 4 --no-fail-fast -p ripdpi-privileged-ops -p ripdpi-root-helper -p ripdpi-root-helper-protocol -p ripdpi-runtime-platform -- --test-threads=4`. Exit 101: privileged-ops 73 passed / 3 failed; helper 66 passed; protocol 41 + 3 + 3 passed; runtime 45 passed. Main suites had no ignored or filtered tests.
- Final Clippy command: `cargo clippy --locked --offline --jobs 4 -p ripdpi-privileged-ops -p ripdpi-root-helper -p ripdpi-root-helper-protocol -p ripdpi-runtime-platform --all-targets -- -D warnings`. Exit 0.
- The three failures are `bpf_drop_sack_filter_attaches_and_detaches_cleanly`, `bpf_filter_detach_removes_program` and `bpf_strip_timestamps_filter_attaches_and_detaches_cleanly`. Attach returns EPERM. No capability was granted and no test was removed, ignored or weakened.
- The fresh Linux target was `/tmp/vpn09-rebased-linux-target-20261109`; Docker image was `sha256:0407d8b59c14badf6b6901ae543f910d9fe98954dbcf0181551b75fc43b3570c`, UID/GID 1000, `--cap-drop ALL --network none`, source and registry mounts read-only. Rust/Cargo 1.98.1; Clippy 0.1.98. The wrapper is `/tmp/vpn09-rebased-native-20261109.sh`; logs and status files are `/tmp/vpn09-rebased-native-{tests,clippy,meta}-20261109.*`.
- `just ci-gates` passed after rebase, including architecture health `--check` (no new or worsened indicators), LoC/hotspot budgets, contracts, locales and API snapshots. Log: `/tmp/vpn09-rebased-ci-gates-20261109.log`. No baseline or snapshot changed.
- `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1` passed. Output: `/tmp/vpn09-rebased-locked-metadata-20261109.json`.
- `./gradlew :core:engine:buildRustRootHelper -Pripdpi.nativeAbisOverride=arm64-v8a --max-workers=4 -Pripdpi.nativeCpuBudget=4` passed after rebase, with command-only Gradle/Kotlin heap limits of 5/3 GiB and ambient CARGO_BUILD_JOBS removed. Log: `/tmp/vpn09-rebased-android-arm64-20261109.log`. Earlier E0425 on libc::dup3 caused the SYS_dup3 correction; its Linux and Android gates then passed.
- Current Kotlin helper suites passed 38 tests with zero failures, errors or skips. The command used `:core:service:testDebugUnitTest`, selectors `*RootHelperSocketDeadlineTest`, `*RootHelperEndpointCleanupTest`, `*RootHelperManagerTest`, `*RootHelperNfqwsTest`, `*NfqwsBackendTest`, and `-Pripdpi.skipNativeBuild=true`. Log: `/tmp/vpn09-current-kotlin-retry-20261109.log`. These owning Kotlin inputs did not change in the rebase. This is unit evidence, not Android IPC or root acceptance.
- Independent pr-reviewer and unsafe-code-auditor approved the behavior and unsafe changes before commit. The final combined review found no production issue. A documentation finding about cleanup-pending startup was corrected and approved.

All heavy commands and normal commit hooks used build-gate. The user explicitly approved temporary use of its default lane for VPN-09; the global capacity was not changed. Earlier Maven network, ENOSPC and stale-cache failures were retained as failed attempts. Only inactive task-owned build outputs and a tooling backup were removed to recover disk space. Fresh private outputs removed cross-worktree cache uncertainty.

## Test-oracle repair

The first combined parallel run failed an old numeric-fd assertion because another test could reuse the closed number. The complete unchanged helper binary passed 66/66 serially. Five checks now use actual socket EOF, a working SCM_RIGHTS transfer followed by EOF, or exclusive UDP rebind. The UDP test also supplies all typed parameters and checks the precise target parse error. The final combined parallel helper suite passed 66/66. No production seam or test skip was added. Mac and Linux writer suites each passed 65 tests before integration of the extra F2 regression.

## Audit disposition and remaining acceptance

F1/F2 and confirmed H1/H2/H4 are repaired. F3/F4 were already repaired by 6808d8e91. H3 pathname/nonce-file hardening has no established cross-UID failure under the current private-directory trust model. H5 fd-less active-VPN raw routing remains device uncertainty at the DPI-37 boundary. Sequence-number anti-replay, peer-root attestation and remote helpers are not promised by this contract.

Keep this task in review. Do not archive it while local BPF, hosted CI or device evidence is incomplete. Required next checks are the three BPF tests in an authorized supported kernel environment; real Android AF_UNIX backlog/deadline, cancellation and revoke; root endpoint isolation, DAC/SELinux and stale nonce; and separately authorized packet capture for active-VPN raw routing. No real secret, su activation or physical/external probe was used.
