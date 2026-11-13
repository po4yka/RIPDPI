## Context

Task ENG-1794501878605236 resolves six DPI-08 defects. The runtime uses
synchronous per-connection OS threads. The host kernel owns TCP headers.

## Goals / Non-Goals

- Goal: preserve original payload, reject invalid configuration, and count bytes.
- Non-goal: new evasion features, raw headers, external servers, root activation,
  dependency changes or a claim of universal peer compatibility.

## Decisions

- Use existing monotonic round and stream progress to restrict urgent emission
  to the first nonempty original buffer. Lower additional Oob/Disoob steps to
  Split before TTL changes. Do not add a global socket map.
- Write the original prefix with existing progress tracking, then send a single
  urgent byte. A short prefix no longer marks an original byte urgent.
- Retry a Disoob TTL failure only if no original bytes were accepted and the
  urgent syscall was not attempted. An empty prefix urgent error is terminal.
- Preserve progress on errors. Exclude inserted bytes from application counts.
  Repair ordinary and urgent accumulation in the strategy-free action path.
- Reject OOB repeats greater than one and nonzero skip at CLI and grouped-native
  admission. Keep repeat 0 or 1 with skip 0. Reject extra offset components.
- Parse hex without UTF-8 slicing. Keep the ByeDPI literal fallback for malformed escapes.
- Validate empty or single byte-range characters consistently across UI,
  settings import, mapper and native codec. Do not change persisted schema.
- Use six separate repair commits, one per audit finding. Read-only independent
  review precedes commits. User authorization includes main integration and push.

## Contracts and ownership

- Runtime worker owns ripdpi-desync and ripdpi-desync-runtime: F1, F2, F6.
- Parser worker owns ripdpi-config parsing and ripdpi-proxy-config chain
  validation: F3, F4.
- UI worker owns Kotlin validation, settings, codec and owning tests: F5.
- Integration owner owns portfolio, OpenSpec, board and combined checks.
- Cargo.lock, dependencies, protobufs, locales and baselines have no planned edits.
  Generated snapshots change only through their owning generator when required.
- The generic strategy enum already has WriteUrgent. Its TCP adapter preserves
  that variant and exact bytes. It does not flatten the action into normal Write.
  This is a separate repair of the internal gap found in the audit.

## Risks / Trade-offs

- Sending prefix and urgent byte separately changes packet grouping. Peer-byte
  correctness is tested; no external DPI or packet grouping promise is made.
- Later OOB steps become splits. Receipts must show the effective family.
- Strict byte validation rejects invalid old/imported values instead of
  truncating them. The existing empty default sentinel remains supported.
- macOS production wait is unsupported. Tests distinguish the real emitter
  from platform wait adapters. Local Linux checks need no elevated capability.

## Migration Plan

No persisted migration or deployment is required. Integrate only after targeted
Rust/Kotlin tests, independent review, just ci-gates, architecture health and
locked Cargo metadata pass. Revert the repair commits to roll back. No baseline
blessing or quality gate weakening is permitted.
