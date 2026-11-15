## Context

Repair six bounded findings from DPI-34.

## Goals / Non-Goals

- Goal: fix F1 through F6 in separate commits.
- Non-goal: full QUIC migration, privileged ports, root probes or new dependencies.

## Decisions

Remove outgoing reuse. Check replacement tuple while the old socket lives. Gate rebind on existing QUIC evidence. Reject negative delta and make zero inert. Require positive sidecars to exceed carried bytes. Walk only safe IPv6 options with bounded parsing and independent checksum oracles.

## Contracts and ownership

socket_owner owns platform.rs, migration.rs and socket tests (F1/F6). rebind_owner owns session/upstream pump/config UDP and source-rebind tests (F2). raw_owner owns strategy UDP/config/Lua and TUN udplen (F3/F4). Root owns task/specs, F5 docs, packet-strategy-runtime.md, API snapshots and integration. Do not edit dependencies, versions, schemas, locales, goldens or baselines.

## Risks / Trade-offs

Negative configs now fail admission. Opaque rebind remains heuristic. Cargo --locked, owning regressions, independent review, Clippy and ci-gates provide local evidence. Android/raw external acceptance is not claimed.

## Migration Plan

No stored-schema migration. Positive configs retain scope. Use ordinary revert commits if rollback is requested. Rebase and repeat affected gates before fast-forward integration and push.
