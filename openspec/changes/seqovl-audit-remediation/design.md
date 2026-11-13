## Context

Task ENG-1794557051102648 repairs the DPI-12 findings. The existing action and plan executors diverge; the raw sender and helper lose error-stage information.

## Goals / Non-Goals

- Goal: fix F1-F6 and G1, one fix commit per finding.
- Non-goal: new DPI tactics, root activation, physical or external probes, golden blessing, dependency changes.

## Decisions

- Keep TCP flag support by routing flags and overlap bytes through the same semantic sender. Do not silently drop masks or switch to a plain executor.
- Mark actual raw refusal as a family fallback, independent of the planned kind.
- Treat seqovl as terminal in Kotlin, CLI and JSON validation.
- Before emission, ordinary supported refusal kinds may remain fallback-safe only after repair mode is restored. After a raw attempt or uncertain queue progress, wrap errors as terminal Other. A known atomic EMSGSIZE refusal remains safe after restoration.
- Account for full packet length against available TCP/route budget and retain a safe size-error fallback when the route changes.
- Add helper error data only for explicit safe seqovl refusals. Old helper responses and unknown outcomes stay terminal. Do not replay on arbitrary Other.
- Require repair plus at least one raw family for the existing aggregate capability boolean. No destination-specific success is implied.
- MD5 latency and external reassembly claims remain hypotheses; no speculative packet-policy redesign is part of these fixes.

## Contracts and ownership

- runtime-worker owns ripdpi-desync action/planner and ripdpi-desync-runtime for F1/F2; exhaustive action callers belong to this lane.
- config-worker owns core/data/model chain validation, config CLI, proxy-config chain validation for F3; runtime-platform/retransmit.rs for G1.
- privileged-worker owns ripdpi-privileged-ops seqovl raw sender and tests for F5 then F4.
- primary agent owns F6 root-helper handlers, runtime-platform/root_helper_client, failure-classifier retry regression, API snapshots, all task/OpenSpec state and generated board.
- No Cargo.lock, version catalog, locale, protobuf, JNI signature or golden fixture change is planned. Root-helper metadata is additive and fails closed when absent or invalid.

## Risks / Trade-offs

- A terminal chain previously accepted by storage may now fail validation; test all input paths and document the rejection.
- A terminal post-attempt error may close a connection that previously replayed; this protects payload integrity.
- macOS unit tests do not execute Linux TCP_REPAIR. Use portable pure regression coverage, target compilation and independent unsafe review; report missing device proof.

## Migration Plan

No persisted schema migration. Keep old helper error replies terminal. Rollback uses ordinary revert commits. Run affected crate suites with --locked, the data-model tests, cargo metadata --locked, architecture health and just ci-gates before integration. Run independent pr-reviewer, unsafe-code-auditor and required specialist tests. User has authorized fast-forward integration into main and remote push. Remote/device evidence remains separate from local evidence.
