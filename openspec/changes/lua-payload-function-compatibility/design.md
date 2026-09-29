## Context

The full-parity authority is sibling zapret2 commit ca950d838a0ee7dc32bb6ae5e65e54488c1bfe3b, ABI 6. It registers 101 C globals and contains six Lua scripts with 235 named top-level functions. Existing RIPDPI assets remain pinned to fd4716da, ABI 5, for the payload planner.

The current StrategyContext has payload and selected metadata. It lacks original IP/TCP headers, directed packet events, conntrack positions, reassembly/replay, and execution-plan state. Ordinary TCP sockets cannot set sequence numbers, checksums, SYN options, or IP fragmentation. Existing root-helper sends packets but does not own NFQUEUE interception.

## Goals / Non-Goals

- Goal: run all pinned upstream Lua functions with their real packet/runtime contracts on rooted Android, and preserve safe non-root payload execution.
- Non-goal: claim arbitrary raw packet parity on a non-root device or replace unrelated RIPDPI strategies.

## Decisions

- Build a pinned upstream nfqws2 executable as a separate opt-in root backend. Reuse its native packet, crypto, timer, conntrack, replay, and verdict implementations rather than duplicate them in the payload planner.
- Vendor or fetch checksum-pinned source through the native build owner. Build four Android ABIs with 16 KiB load alignment. Keep all source and component license notices available with the artifact.
- Service-owned lifecycle must start the backend before enabling queue rules, supervise exit, and remove only its own rules on stop/failure. Queue bypass must preserve connectivity if the process dies. Backend routing must not recurse through the VPN or its own queue.
- Keep root activation behind root_mode_enabled. Root/NFQUEUE absence must produce a defined capability failure and preserve the existing non-root path.
- The existing Lua jail, memory limit, and watchdog remain in place. Full upstream file/module behavior belongs to the explicitly privileged backend; do not widen the existing payload sandbox.
- The preparatory payload changes use a private position module, canonical payload names, wrapping u32add, and persistent per-flow state. They are a subset, not full parity. Raw packet edits remain rejected before non-root emission.

## Contracts and ownership

Primary owns Kotlin service integration, the payload planner, protocol detection, and task/specification files in fix/lua-function-compat. The native build writer owns native/zapret2, scripts/native/build-nfqws2.py, and the nfqws2 convention task in its separate worktree. The root lifecycle writer owns ripdpi-root-helper and ripdpi-root-helper-protocol in its separate worktree. Primary integrates their scoped patches. Review agents are read-only. No baseline or golden update is authorized by this task. New persistent or wire fields must follow their schema owners and gates.

## Risks / Trade-offs

- Kernel NFQUEUE availability varies by Android image; test capability checks and bypass cleanup, and report unavailable device proof explicitly.
- libnetfilter_queue uses GPLv2; preserve its license and corresponding source. Keep the backend boundary and all distribution notices explicit.
- Privileged process death can leave rules behind; bypass plus idempotent session-scoped cleanup is required.
- Payload markers describe the original payload only. The non-root planner rejects host-relative markers for alternate blobs; the upstream backend parses each packet with its own runtime.

## Migration Plan

Do not migrate stored settings implicitly. Use the existing explicit root toggle and add only the activation contract needed for the new backend. Rollback disables the backend, removes session rules, and restores the prior executable/assets. Gates: all affected Rust tests, Clippy, formatting, Kotlin lifecycle tests, native four-ABI builds and ELF alignment, pinned upstream ABI tests, packet captures for all strategy families plus timers/cutoffs/cancel/replay, root Android lifecycle/failure tests, non-root regression tests, architecture health, and locked Cargo metadata. Record local, device, artifact, and hosted CI evidence separately before publication is claimed complete.
