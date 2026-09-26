## Context

The Lua engine keeps at most 1024 `FlowId` tables and currently deletes the least recently used table when full. The TUN egress interceptor owns the Lua registries, but TCP sessions and UDP associations own runtime teardown. A Lua action can consume a packet before either runtime owner exists.

## Goals / Non-Goals

- Goal: Preserve admitted Lua state at capacity, report capacity failure for a new flow, and reclaim known closed flows through TUN integration.
- Non-goal: Raise the memory ceiling or change Lua script return semantics.

## Decisions

- Keep the 1024-table bound. Reject a new flow at capacity before creating its table; do not evict another flow. The existing strategy error and `on_fail` path controls its packet verdict.
- Forward an explicit close event from the registry to Lua strategies. The TUN interceptor maps the same packet tuple and protocol to `FlowId` for execution and close events.
- Close TCP state after outbound FIN/RST and on runtime session teardown. A pending listener timeout does not prove a raw Lua flow ended.
- Track exact UDP flow IDs by source. Mark a flow as association-owned only after forwarding creates or uses that association. Close only those flows when the association ends; keep locally handled DNS and Lua-consumed flows independent.
- Expire UDP flow state after the configured idle timeout when no owning association is live. A transport association replaced for the same source preserves app flow state.

## Contracts and ownership

- Rust crates: `ripdpi-strategy-lua`, `ripdpi-strategy-trait`, `ripdpi-strategy-registry`, `ripdpi-tunnel-intercept`, and `ripdpi-tunnel-core`. The trait API snapshot is a serialized shared file owned by one writer.
- No Kotlin, JNI, protobuf, stored data, or configuration schema change. No new dependency.

## Risks / Trade-offs

- A new flow can fall back or drop according to `on_fail` when all slots remain occupied. This is explicit and preserves admitted flows.
- Flow end can race with tuple reuse. Include transport in the flow identity and process teardown before reuse where the event loop owns both operations.
- A consumed TCP flow without FIN/RST or a runtime session can retain state until interceptor shutdown. The hard capacity limit rejects new flows instead of corrupting admitted state.

## Migration Plan

Change the in-workspace trait, registry, Lua engine, and TUN callers together. No persisted state migrates. Reproduce the 1025-flow failure first, then run targeted Rust tests, API snapshot generation/check, `cargo fmt --all -- --check`, architecture checks, and tunnel core integration tests. Rollback is a source-level revert.
