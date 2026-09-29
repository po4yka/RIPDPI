## Context

Pinned upstream ca950d838a0ee7dc32bb6ae5e65e54488c1bfe3b already supplies root helper tests. TUN packet helpers lack independent complete-wire vectors. Full matrix success shortcuts are mostly disabled, but QUIC pivot and pilot elimination still apply.

## Goals / Non-Goals

- Goal: reproducible packet checks and a complete applicable uncapped audit.
- Non-goal: new packet protocol support, new diagnostic request fields, unlimited probing or root acceptance.

## Decisions

Generate fixed packets from upstream reconstruct/checksum helpers, and compare literal bytes in existing TUN tests. Preserve IPv4 UDP zero-checksum policy; IPv6 UDP retains its required checksum. Verify unsupported AH safe passthrough. Reuse existing Lua strategy execution and injectors.

Use one internal is_exhaustive predicate for full_matrix_v1 without max_candidates. Bypass QUIC pivot and pilot pruning only there. Keep eligibility, ordering, scan/stage deadlines and cancellation. Prioritize existing PartialResults over DNS fallback only for incomplete exhaustive runs. Current QUIC filter retains the full pool; no change needed.

## Contracts and ownership

Primary owns tunnel tests, oracle, docs and task/spec files in lua-nonroot. Audit worker owns monitor-engine only in exhaustive-strategy-audit. All other agents are read-only. No serialized shared-file, Kotlin, JNI, protobuf, locale, dependency or baseline changes.

## Risks / Trade-offs

Full audits perform more candidate work and can reach the same budget sooner; PartialResults exposes this limit. Tests compare built packets, not successful DPI evasion or on-wire delivery.

## Migration Plan

No persisted migration. Revert scoped commits to roll back. Gates: full tunnel/monitor tests, affected Clippy, fmt, locked metadata, architecture health/contracts, Android native test binaries, independent review and exact-SHA hosted CI.
