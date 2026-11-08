## Context

Task DNS-1794142176893534 repairs the five reproduced VPN-05 defects in one Rust crate. The packet loop is the sole mutable cache owner. Worker tasks return bytes and do not own the cache.

## Goals / Non-Goals

- Goal: Preserve destination ownership, bounded memory, active flow leases, reserved listener ownership, and truthful AD metadata.
- Non-goal: Add response caching, negative caching, fake IPv6, a DNSSEC validator, root features, or new upstream protocols.

## Decisions

- Store issued synthetic IPs as LRU values. Use a monotonic address offset independent of live entry eviction. Remove unused reusable-slot bookkeeping. A cache miss for an old address is safer than selecting another host.
- Preserve the existing network/mask and live capacity limits. Do not rewind address issuance on generation reset. Reject finite address-space exhaustion before destructive eviction.
- Reserve the actual configured interceptor at cache setup, including a supplied cache. Reservation changes no configuration shape.
- Pin mappings during rewrite and serialization. Balance every temporary pin on all Result paths; do not claim rollback of unexposed cache allocations.
- Flush unleased mappings on actual generation transitions only. A stale result after synchronization must not clear the shared table again.
- Clear `metadata.authentic_data` only when A synthesis or AAAA removal changes response content. Do not claim signature compatibility.
- Preserve the existing direct DNS fail-closed behavior and document it. Do not add a silent encrypted fallback.

## Contracts and ownership

- Production ownership is `ripdpi-tunnel-core`; Kotlin Builder/JNI schemas and upstream transports stay unchanged.
- Mapping writer owns state.rs and config.rs for F1/F4. Rewrite writer owns packet.rs for F2/F5. Primary writer owns stale-response handling, documentation, task state, and serialized test registration.
- Each writer uses an isolated worktree. Commit F2, F5, F1, F4, and F3 separately; combine only after independent review.
- No lockfile, manifest, schema, baseline, locale, or golden update.

## Risks / Trade-offs

- A finite session can exhaust its synthetic address space after many distinct mappings. Return a controlled failure; a new native session is required to obtain a new address space. TTL remains zero.
- A retired address can produce a reverse miss. It must not identify an unrelated host.
- Desktop tests prove native behavior, not Android routing convergence or external peer compatibility.

## Migration Plan

No persistent migration is needed. Normal tunnel restart constructs the new cache. Revert the individual fix commit to roll back its source changes; do not alter stored user settings.

Required local gates: targeted RED/GREEN regression tests, `cargo test -p ripdpi-tunnel-core --locked`, affected-crate clippy with all targets, rustfmt, `just ci-gates`, and task/OpenSpec validation. An independent reviewer must review each substantial behavior change before commit. No hosted workflow is manually dispatched; normal push CI is observed when available. Physical probes and external server acceptance are outside this local repair scope.
