## Context

Task ID: `TRN-1794371418861139`. The audit has loopback reproductions at source SHA `57d9bcdf722510ef9e1de482cd41859b77a1ef47`. Refer to proposal.md and the carrier delta specification.

## Goals / Non-Goals

- Goal: repair F1 through F4 with one commit per defect and tests of observable behavior.
- Non-goal: packet-up, downloadSettings, new HTTP versions, Android Xray packaging or external-server activation.

## Decisions

- Keep existing exported constructors, stream types and accepted native modes.
- Submit POST asynchronously after request validation; returned stream owns the task and observes upload errors.
- Transfer pump task ownership to the returned stream. Full drop cancels these tasks; write-half shutdown only ends upload.
- Preserve decoded URL path and raw query separately; encode the path at the HTTP request boundary using existing facilities without adding a dependency.
- Wait on capacity from all usable carriers and recheck pool state after closure or retirement. Preserve cancel safety.

## Contracts and ownership

- Only Rust `ripdpi-xhttp` behavior changes; existing relay-core callers retain their contract.
- Stream worker: relay.rs except path helpers and new relay regression files. F1 then F2 use separate commits.
- Path worker: config.rs, h2_body.rs, path helpers and path tests. Integrate relay.rs hunks after the stream commits.
- Pool worker: pool.rs and pool regression files, with no permit API change.
- Root: portfolio/OpenSpec, README/SPEC_VERSION/SPEC and CONFIG_CONTRACTS.md, combined validation and integration.
- All writers use separate worktrees. No Cargo/Gradle dependency, protobuf, JNI, locale, baseline or golden fixture changes.

## Risks / Trade-offs

- Async submission can delay rejection visibility: propagate upload failures through stream I/O and cover late rejection/reset.
- Abrupt full drop must reset only one logical stream: verify sibling echo and half-close download.
- URI escaping changes previously broken profiles: use fixed upstream-derived cases and an independent loopback peer.
- Pool waiting must not lose wakeups or permits: exercise the second-carrier release and cancellation.

## Migration Plan

No stored-data migration. Revert the individual fix commit to roll back its behavior. Before integration run targeted regression tests, crate tests and clippy, relay-core xHTTP tests, independent pinned peer exchange, read-only PR and async cancellation review, `just ci-gates`, architecture health and locked cargo metadata. Physical devices and external networks are outside this repair acceptance scope.
