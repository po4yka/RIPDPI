## Context

DPI-37 confirmed three safety defects on the audit snapshot. Current main retains them. The implementation remains in its existing Linux/Android and library-only scope.

## Goals / Non-Goals

- Goal: preserve supported receive bytes, validate input checksum and preserve terminal cleanup failures.
- Non-goal: add packet techniques, activate root, run physical-device or external-server probes, or implement unproven audit hypotheses.

## Decisions

- Keep protocol v4 and JSON fields. Add a bounded response path only for recv_icmp_wrapped_udp. Its cap is four JSON bytes per u16-sized payload byte plus 1024 metadata bytes; all other responses retain 8192 bytes.
- Advertise receive-response support in pure preflight. Refuse peers without the advertisement before delivering a receive request. Do not globally raise control limits.
- Check the existing ICMPv4 checksum residual before decoding. Use independent fixed byte vectors as the oracle.
- Reuse finish_tcp_repair in both remaining error exits. Use scoped test-only syscall fault injection and a real loopback stream to test the exits without root or raw emission.

## Contracts and ownership

- Integrator owns F1: root-helper-protocol, root-helper, runtime-platform, ROOT_HELPER_CONTRACT.md, task and OpenSpec artifacts. The integrator is the only writer of IPC and planning files.
- checksum_fix owns F2: privileged-ops/src/linux/experimental_tier3/icmp_wrapped_udp.rs and its tests in a separate worktree.
- repair_fix owns F3: privileged-ops/src/linux/raw_packet/{fake_tcp.rs,tcp_payload.rs}, tcp_repair/sockopt.rs, and narrow test support in a separate worktree.
- No shared production file has two writers. Cargo.lock, dependencies, locales, baselines, generated outputs, JNI and protobuf are unchanged.
- Review agents are read-only. Each fix receives its own commit after review; the integrator merges by fast-forward into main and pushes only as explicitly requested.

## Risks / Trade-offs

- Larger receive frames increase bounded allocation: use a command-specific cap and existing total deadline.
- Older peers cannot return large frames: require advertised support before operation delivery.
- Failed restoration leaves uncertain state: keep errors terminal and preserve both causes.
- Darwin excludes Linux logic: run actual Linux tests if available, otherwise compile Android test targets and report execution gaps.

## Migration Plan

No stored-data migration. Ship matched app/helper binaries. Additive preflight metadata permits clean refusal of older helpers. Revert each fix commit independently if required.

## Validation

Observe regression failure before each fix and passing owning tests after it. Run combined owning-package tests, strict Clippy, Android aarch64 test-target checks, just ci-gates, architecture health and locked Cargo metadata. Obtain independent PR review and an unsafe-boundary review for affected syscall test paths. Record exact SHA and hosted CI state after the authorized push. Device, artifact and deployment evidence are not applicable to this code-only repair; privileged syscall acceptance remains unverified.
