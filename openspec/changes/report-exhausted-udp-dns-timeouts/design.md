## Context

The classifier assumes multiple failed attempts prove a transient timeout. They do not prove recovery.

## Goals / Non-Goals

- Goal: classify observed failure and recovery correctly.
- Non-goal: infer provider intent, change retry scheduling, or add outcome tokens.

## Decisions

- Use existing `dns_unavailable` for timeout failures. Preserve encrypted success in the result details.
- Keep successful retry and non-timeout behavior unchanged.

## Contracts and ownership

- Classifier agent owns `ripdpi-diagnostics-runner/src/connectivity/probes/dns/classification/outcome.rs`, related tests, and this planning slice.
- No shared schema, lockfile, golden, or locale edits. Root owns combined board generation and integration.

## Risks / Trade-offs

- `dns_unavailable` is a broad existing outcome. Raw details retain which transport failed and do not attribute failure to the provider.

## Migration Plan

No migration. Revert the atomic fix to roll back. Run focused RED/GREEN, the runner crate suite, Clippy, and combined staticAnalysis before integration.
