---
task_id: VPN-1793035703372515
change: owned-packet-descriptor-io
commit_sha: 82166c9e357dc30805670f9dbae8faf6c9963a8e
local: passed
local_evidence: macOS tunnel-core tests passed (326 unit tests and 7 TUN E2E tests); affected-crate Clippy, formatting, architecture, and task contracts passed.
remote_ci: passed
remote_ci_evidence: https://github.com/po4yka/RIPDPI/actions/runs/40383907052 completed successfully on the exact commit, including Rust workspace tests, coverage, and static analysis.
device: passed
device_evidence: Hosted Android instrumented tests passed on API 27, 33, 35, 36, and 37 in run 40383907052. This is emulator evidence, not physical-device or direct-network acceptance.
artifact: passed
artifact_evidence: All four native ABI builds and Debug and Release verification passed in run 40383907052.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-PACKET-BYTES | VPN-1793035899141801 | packet_bytes_and_readiness_survive_empty_reads and existing tun_e2e tests passed locally and in hosted CI | passed |
| REQ-PACKET-READINESS | VPN-1793035899141801 | packet_bytes_and_readiness_survive_empty_reads passed; the Linux error_only_readiness_returns_the_descriptor_error regression passed in hosted CI | passed |
| REQ-PACKET-OWNERSHIP | VPN-1793035899141801 | adoption_preserves_flags_and_drop_closes_the_endpoint, tunnel_shutdown_on_cancel, and setup-failure tests passed | passed |
| REQ-PACKET-READY-BARRIER | VPN-1793035899141801 | Existing runtime-ready barrier, packet-loop setup-failure, and first-packet ordering tests passed | passed |

## Scope and limits

The main-branch run passed after all 16 dependency updates and the CI timeout repair. Its static-analysis job completed within the 30-minute budget, including cache saves. The earlier aggregate PR also passed: https://github.com/po4yka/RIPDPI/actions/runs/40371427377.

Independent reviews covered descriptor ownership, fcntl, AsyncFd registration, and cancellation. Local debug tests and hosted Linux tests exercise the descriptor path. No dedicated Miri or AddressSanitizer run exercised these system calls. No physical-device, external-network, or deployment acceptance is claimed.

The low-level Rust io_loop_task argument now uses device::PacketFd. The run_tunnel and Android JNI contracts stay the same.
