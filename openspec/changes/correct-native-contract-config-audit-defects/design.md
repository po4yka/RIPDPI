## Context

The audit covers ten native contract and configuration crates. Several confirmed defects surface in downstream HTTP, proxy, and WebSocket runtime crates. The proposal records the behavior changes.

## Goals / Non-Goals

- Goal: Correct confirmed behavior at shared parsing and runtime boundaries, with regression checks.
- Non-goal: Redesign configuration schemas, add a backend, or change JNI or protobuf contracts.

## Decisions

- Validate public proxy access against the bound listener address, including direct and prebound entry points. Reject SOCKS4 on token-protected listeners.
- Parse HTTP framing before reading a body; reject incomplete or ambiguous framed responses.
- Keep the established optional MapDNS resolver behavior; reject only an explicit unknown protocol.
- Reload strategy configuration before comparing it, so changes in referenced host lists are visible.
- Use exact known production and test Telegram endpoints before the existing heuristic fallback.

## Contracts and ownership

- Rust crates: ripdpi-config, ripdpi-diagnostics-contracts, ripdpi-proxy-config, ripdpi-strategy-config, ripdpi-telemetry, ripdpi-tunnel-config, ripdpi-ws-transport-port; downstream ripdpi-diagnostics-http, ripdpi-proxy-runtime, and ripdpi-proxy-runtime-adapter.
- No Kotlin modules or serialized shared files change. The audit worktree is the sole writer for these paths.

## Risks / Trade-offs

- Stricter validation rejects configurations previously accepted by mistake; targeted parser and runtime tests cover each rejection.
- Reloading referenced host lists performs extra file reads; the existing size limits apply, and a future cache can be added if measured throughput requires it.
- Telegram range classification remains heuristic for addresses outside the exact documented set; a `ponytail:` source comment identifies the ceiling.

## Migration Plan

Existing valid configuration continues to parse. Invalid values require correction. Rollback uses the atomic fix commits. Run affected Cargo tests, architecture validation, and formatting before integration; hosted CI, Android device, and deployment evidence remain separate.
