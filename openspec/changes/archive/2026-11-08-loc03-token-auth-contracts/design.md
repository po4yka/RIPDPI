## Context

Task PRX-1794114937480022 repairs findings F1-F4 from the LOC-03 audit at base 3e6e2dd2c870c20749c4184348a6da6ba2336a56. The implementation is repository-owned Rust. RFC 1929 uses one-byte credential lengths.

## Goals / Non-Goals

- Goal: reject invalid credentials and replies at their owners; preserve local correlation across address representations.
- Non-goal: accounts, local TLS, GSSAPI, new relay support, timing redesign or speculative logging changes.

## Decisions

- F1: validate token byte length in the central runtime listener validator for Mixed and BytePrefixed SOCKS-capable modes. Keep HTTP-only behavior, including long tokens, unchanged. Keep existing remote-listener and incompatible-mode guards.
- F2: validate both auth response bytes in the shared async helper. A bad version must stop TCP and UDP setup.
- F3: validate credential pair lengths in tunnel-config and reject empty fields in the helper. Use existing InvalidValue errors; do not add public error variants or dependencies.
- F4: canonicalize the peer IP before the correlation loopback check. Do not change the token or username policy.
- Use literal wire bytes and loopback peers as test oracles. Preserve the constant_time_eq call and credential-free errors.

## Contracts and ownership

The portfolio record assigns server, client and integration worktrees. F2 and F3 share one writer and are serialized as separate commits. The integrator owns all task/spec files. No Kotlin modules or JNI, protobuf, wire schema, storage, locale, golden or baseline changes. Cargo.lock and API snapshots remain unchanged.

## Risks / Trade-offs

- Previously accepted invalid configuration fails earlier; verify 0/1/255/256 byte boundaries and multibyte inputs.
- Dual-stack socket defaults differ by OS; explicitly exercise canonical mapped address policy and real loopback where available.
- Async setup still owns its stream through completion; preserve cancellation semantics and document changed async functions.

## Migration Plan

No persisted data migration. Keep HTTP-only token compatibility. Revert the relevant atomic commit to roll back a fix. Run affected crate tests with --locked, scoped Clippy, cargo metadata --locked, just ci-gates and independent read-only review before main integration. Device, release artifact, hosted CI and deployment are not required for these host-reproduced native fixes; do not claim those checks ran.
