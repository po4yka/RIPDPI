---
title: Fix July 2026 native Rust P2 audit findings
type: epic
status: doing
area: epic
priority: high
owner: Codex
parent: null
blocks: []
blocked_by: []
created: 2026-07-14
updated: 2026-07-14
---

## Goal

Eliminate every confirmed P2 defect from the July 2026 repeated native Rust audit with one regression-backed atomic commit per defect.

## Why now

The P1 series closed the immediate authentication, cancellation, lifecycle, and memory-exhaustion risks. The remaining P2 findings still permit resource growth, carrier and worker leaks, deadline bypasses, inaccurate rolling quality data, fd leaks, unsafe public contracts, and credential disclosure through `Debug`.

## Key decisions

- Stack this worktree on `worktree-fix-native-p1-audit` so the P2 series is validated against the complete current post-audit remediation tree.
- Keep every fix and its regression tests in a separate Conventional Commit.
- Preserve externally compatible behavior unless the audited safe API is itself unsound; fail closed for disabled capabilities and ownership transfer.
- Do not integrate, delete, or push the worktree branch without explicit user confirmation.

## Scope

- [ ] Bound MASQUE target flows and evict idle least-recently-used flows.
- [ ] Bound aggregate UDP-association memory and replace oversized per-association storage with pooled MTU-sized buffers.
- [ ] Budget tunnel session scans and make bulk socket removal linear.
- [ ] Make UDP association eviction reflect recent activity instead of creation order.
- [ ] Fail closed when TUIC UDP support is disabled.
- [ ] Supervise the Hysteria2 H3 driver through authentication and session lifetime.
- [ ] Own and terminate the VLESS yamux driver task with the carrier session.
- [ ] Break the xHTTP pooled-connection ownership cycle.
- [ ] Make `QualityWindow` metrics truly rolling with an injectable monotonic clock.
- [ ] Make monitor connection-concurrency completion panic-safe and deadline-bounded.
- [ ] Make diagnostics TCP connection racing return the first success without spawn-order head-of-line blocking.
- [ ] Route diagnostics UDP resolution through the bounded deadline-aware DNS executor.
- [ ] Take ownership of PCAP export file descriptors before any fallible JNI or source operation.
- [ ] Make flow-attribution unregister bounded and generation-safe for late JNI results.
- [ ] Make PCAP worker shutdown bounded when filesystem flush blocks.
- [ ] Remove the safe API that accepts arbitrary signal handlers or mark the contract unsafe.
- [ ] Redact relay credentials from every public runtime/config `Debug` representation.

## Ship definition

- Seventeen atomic fix commits exist, each with a regression test or compile-time contract that fails against the audited behavior.
- Focused crate tests and clippy pass after each commit.
- Workspace formatting, clippy, nextest, dependency policy, architecture health, and native CI guards pass on the combined branch.
- Android/Linux-only paths are cross-checked where the local toolchain supports them; any remaining device/kernel verification debt is reported explicitly.

## Work log

- 2026-07-14: Goal started in `worktree-fix-native-p2-audit` from `f47201f0c`; ownership assigned to Codex.
