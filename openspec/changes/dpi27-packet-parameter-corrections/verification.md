---
task_id: "RST-1794677915101106"
change: "dpi27-packet-parameter-corrections"
commit_sha: "62d45e05b6dc6fd76177c7a15d5288b17c6eae7a"
local: "passed"
local_evidence: "768 runtime tests and 202 config tests passed; all-target check, strict Clippy, ci-gates, architecture health and locked Cargo metadata passed. 174 Android tests and both locale lint gates passed on the identical Android source tree. Three existing Linux TCP filter tests fail with EPERM; no success is claimed for them."
remote_ci: "not_applicable"
remote_ci_evidence: "This change uses observed local acceptance. No hosted CI success is claimed."
device: "not_applicable"
device_evidence: "No physical device, root activation or external probe was authorized or run."
artifact: "passed"
artifact_evidence: "The Linux suite passed 132 tests, including four actual kernel BPF tests and the real packet-builder byte regression. Three TCP filter attach tests failed with EPERM. Builder bytes and AF_UNIX filter results do not prove production TCP wire delivery."
deployment: "not_applicable"
deployment_evidence: "This change does not deploy a service or publish a release."
---

# Verification

Code snapshot: `62d45e05b6dc6fd76177c7a15d5288b17c6eae7a`.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DPI27-FEEDBACK | RST-1794678235803087 | 201 desync-runtime, 430 proxy-runtime, 56 runtime adapter, 76 adaptive and 5 services tests passed. Channel-controlled overlap regression pins the actual TTL once. Independent concurrency review passed. | passed |
| REQ-DPI27-IDENTIFICATION | RST-1794678245393545 | Real raw-builder bytes use unique nonzero live IDs across ports and fragment leases. Independent IPv4/TCP checksums, payload and DF/MF assertions passed. | passed |
| REQ-DPI27-SACK | RST-1794678253764740 | Actual kernel BPF tests pass TS-only bytes and reject only bounded common-layout SACK blocks. Four BPF tests passed. TCP attachment in this environment remains blocked by EPERM. | passed |
| REQ-DPI27-UDP | RST-1794678258153648 | 202 converter tests passed. Real IPv4/IPv6 socket tests preserve fixed UI UDP TTL9 with adaptive TCP, explicit UDP auto TTL3, payload delivery and default TTL restoration. | passed |
| REQ-DPI27-CANDIDATES | RST-1794678262798271 | All 76 adaptive tests passed, including complete boundary cycles without duplicate TTL255. | passed |
| REQ-DPI27-TIMESTAMPS | RST-1794678269799237 | The kernel test confirms receive-only matching drops and unchanged outgoing bytes. Schema and gated capture test describe this contract. Packet capture was not run. | passed |
| REQ-DPI27-SCOPE | RST-1794683533527463 | 174 state/theme tests, app/service lint, ten-locale parity and translation export passed. Review confirms fixed DISOOB TTL1. Preview rendering was attempted but the disabled fdroidFullBenchmark unit-test variant blocks its model. | passed |

## Observed gates

Run Cargo from `native/rust/`, with the pinned toolchain, `--locked`, jobs2 and a worktree-owned target. The user approved sequential default-lane builds with CMake jobs2. Commands and full output are retained under `/Users/npochaev/GitHub/RIPDPI-worktrees/dpi27-evidence/`.

- `cargo test --locked --jobs 2 -p ripdpi-proxy-config`: 192 unit tests and 10 integration tests passed. Log: `final-rebased-dpi26-host.log`. Later upstream changes did not change this converter source or its tests.
- `cargo test --locked --jobs 2 -p ripdpi-desync-runtime -p ripdpi-proxy-runtime -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-runtime-adaptive -p ripdpi-runtime-services --lib`: 768 passed, no ignored tests. Log: `final-host-gates.log`.
- `cargo check --locked --jobs 2 -p ripdpi-proxy-runtime --all-targets` and strict all-targets/no-deps Clippy for all six affected packages passed. The first check hit ENOSPC after the suites passed; the retry passed after removal of owned, inactive build outputs. Log: `final-remaining-gates.log`.
- `just ci-gates`, `python3 scripts/ci/check_architecture_health.py` and locked Cargo metadata passed. API owner regeneration produced no snapshot changes. The snapshot checker intentionally skips the Linux-owned runtime-platform snapshot on Darwin.
- Linux uid501, network disabled, cap-drop ALL: privileged-ops full suite132 passed /3 failed /0 ignored; all failures are TCP SO_ATTACH_FILTER EPERM. Strict Linux Clippy for privileged-ops, desync-runtime, proxy-runtime and its adapter passed. Log: `bpf/final-rebased-dpi36-linux.log`; summary: `bpf/final-rebased-dpi36-summary.json`.
- Linux code snapshot was `039be0342e553986a636d6eef466fd86a8748cfb`. Its only Rust difference from the verified code snapshot is the added, platform-independent FakeRST test fixture. Production Linux sources, BPF arrays and builder tests are identical.
- Android state/theme tests174 passed, with no failures or skips. `:app:lintGithubFullDebug` and `:core:service:lintDebug` passed with max-workers2 and skipNativeBuild. Log: `final-rebased-dpi26-android.log`. Its source snapshot was `2fcf32b2322cb872c084689c26b0504def0bcdd5`; `git diff --exit-code` confirms identical app/core/build/Gradle sources at the verified code snapshot.
- Independent review approved each substantial change and the rebase resolutions. The unsafe BPF test helper hash is unchanged. The async audit found no async code in the changed path.

## Limits

C, V-unit, V-socket, V-builder and V-kernel are separate evidence levels. None of these local results proves production raw TCP delivery or external compatibility. The original specification checks remain S; E remains unestablished.

The full Linux suite has three failed environment-gated TCP attach tests. An independent pass-all filter also gets EPERM on TCP, while Unix sockets accept it. No capability or guard was weakened. Repeat these tests in an environment that permits TCP socket filters.

The Preview CLI model fails because unit tests are disabled for fdroidFullBenchmark. No visual render or golden update is claimed. Physical/root/helper activation, AF_PACKET capture, live fragmentation/reassembly and external compatibility remain unrun and require separate authorization.
