## Context

The seven findings affect existing packet parameters. See proposal.md. Implementation work uses separate worktrees, with ownership in the portfolio record.

## Goals / Non-Goals

- Goal: correct observed reliability and contract defects with the existing mechanisms.
- Non-goal: add outbound timestamp stripping, more SACK layouts, new packet methods, root activation, external probes or persistent learning.

## Decisions

- F1 carries TTL, generation and revision from resolve to feedback. Cache replacement and config rebuild invalidate earlier attempts. A successful attempt can pin only its actual TTL; old failures cannot undo a newer success. Server-hop feedback also respects generation. Actual-use receipts gate token publication; a short-lock session ledger defers fake-only success feedback until an in-flight send publishes its receipt. No network I/O runs under that lock.
- F2 uses the existing sender-owned IP-tuple lease for DF=false raw TCP packets. Per-flow and random values are preferences; the sender can replace a live collision. Keep DF and MTU behavior. Preserve the existing 120-second, process-local lease scope and explicit exhaustion errors.
- F3 keeps fixed-layout support. Prove the NOP/NOP/Timestamp/NOP/NOP prefix and SACK option length before return-zero. Unsupported or malformed layouts pass through. Do not add an option scanner.
- F4 derives fixed UDP TTL from the saved fake TTL before the adaptive TCP fallback replaces the primary action. UDP does not inherit TCP auto_ttl.
- F5 computes bounds in a wider integer type and converts only values in range.
- F7 removes DISOOB from fake TTL relevance and target/badge labels, updates all locale scope text and exports, and preserves saved profile visibility. This corrects a UI claim, not the fixed TTL 1 wire behavior. The matching RDS reference is vpn-quick-vs-advanced.html; no layout/token redesign.
- F6 corrects comments and test direction. Preserve the current dropSack priority guard and existing opt-in capture gate.

## Contracts and ownership

Adaptive Rust ports and session DTOs change together; no JNI, IPC or protobuf numbers change. The IPv4 worker alone owns the one IP-ID explanation in all locales. The BPF worker alone owns the proto comment. The integrator owns generated API snapshots and task records. No new production dependency, baseline growth or golden blessing.

## Risks / Trade-offs

- Sender lease state is process-local and expires after 120 seconds; restart and multi-process global uniqueness are not newly promised.
- A filter cannot remove options: it can drop a whole incoming segment. Tests must name that direction and consequence.
- Attempt plumbing must cover retry, pending response and terminal result paths without replay after a possible commit.
- Local Linux kernel tests prove filtering and builder bytes, not external DPI effectiveness or Android SELinux support.

## Migration Plan

No stored data migration. Keep JSON keys and protobuf fields. Regenerate API snapshots with their owner. Use one finding per implementation commit. Reverting a finding commit restores its prior behavior; do not weaken validation or change DF as a shortcut.

Gates: targeted red/green tests, affected crate suites, strict Clippy, unprivileged Linux kernel/byte tests, Android all-targets compile where available, locale lint, generated API checks, just ci-gates, architecture-health check, locked Cargo metadata, and independent read-only review. Physical/root/external acceptance is outside this authorized local validation scope.
