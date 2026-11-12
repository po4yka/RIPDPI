## Context

Task ID: `RST-1794498457070491`. Repair confirmed findings within the existing approximation scope.

## Goals / Non-Goals

- Goal: correct cleanup, TTL, receipts, payloads, sequence arithmetic, and docs.
- Non-goal: add permissions, a new method, dependency, or external DPI claim.

## Decisions

- F1: restore original pages outside the fallible enqueue/wait block before TTL cleanup. Preserve first error and known moved bytes using optional helper response data and adapter error mapping. Zero-progress errors preserve errno; known-progress errors cannot trigger whole-chunk retry.
- F2: request a raw original without changing raw selection conditions.
- F3: restore saved socket TTL after every successful mutation and preserve first error.
- F4: use private controls for optional effective family and actual nonempty writes/waits.
- F5: order primary and secondary fake groups with genuine spans. Fake sequential offsets advance across selected fake bytes.
- F6: retain length validation and use modulo sequence addition.
- F7: document actual owners, gates, and evidence.

## Contracts and ownership

The portfolio defines writer lanes. Lead owns helper progress data, adapter mapping, API snapshots, and combined checks. Each writer has an isolated worktree. No top-level helper fields, JNI/protobuf/storage/config schema changes.

## Risks / Trade-offs

Host tests cannot prove Linux queue semantics. Use nonroot Linux pipe/page tests and independent bytes. Raw emitter and Android remain evidence gaps without root/device authorization. Reject malformed helper progress and preserve errors without progress metadata.

## Migration Plan

No data migration. Run crate suites, general and unsafe reviews, and just ci-gates. Rebase on origin/main, rerun affected gates if inputs changed, fast-forward main, and push. Do not rewrite published history.
