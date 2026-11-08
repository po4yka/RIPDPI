## Context

The shared delayed TCP path establishes a provisional upstream before CONNECT
success. The payload can select another group. Group identity and exact host
text currently force reconnect even when connection properties are unchanged.

## Goals / Non-Goals

- Goal: complete delayed TLS on one accepted TCP connection when routing changes
  only compatible strategy or host metadata.
- Non-goal: reuse across different upstreams, destination sets or pre-connect
  socket policies; change WS bootstrap, retry rules or wire configuration.

## Decisions

- Compare effective `RouteConnectPolicy` for the actual connected group without
  payload and the selected group with payload. Keep egress and preferred-target
  equality and the direct SYN-data exclusion.
- Reuse requires valid policies. Missing groups must not compare equal by both
  returning `None`.
- On reuse, replace route metadata and seed with the selected route and payload.
  Refresh the advisory same-SNI guard if extracted host metadata changes.
- Keep block and owned-stack gates before any application byte reaches upstream.
- Use existing socket fixtures and independently supplied wire bytes. Add a
  one-accept regression before implementation and an independent verified TLS
  loopback check. Keep existing incompatible-upstream and SYN-data tests.

## Contracts and ownership

- Runtime sources and tests belong to the implementation worker in an isolated
  worktree. Primary agent owns this plan, documentation and verification.
- Only `ripdpi-proxy-runtime` behavior changes. HTTP, SOCKS4 and SOCKS5 share the
  fix. No Kotlin, JNI, serialization, dependency or baseline changes.
- Runtime is the existing bounded worker/thread relay. New async functions, if
  needed in tests, must document cancel safety. No unsafe code is planned.

## Risks / Trade-offs

- Reusing an incompatible socket would skip required setup. Compare the entire
  effective connection policy and retain replacement regressions.
- Reusing stale route metadata would lose the TLS strategy. Assert selected
  route metadata and observable forwarding on the retained socket.
- Local TLS proof does not establish Android or external-network acceptance.

## Migration Plan

No migration is needed. Rollback reverts the behavior commit. Validate targeted
regressions red then green, the runtime library and relevant network integration
suites with `cargo test --locked --jobs 4`, all-target Clippy, formatting,
`cargo metadata --locked`, independent single-accept TLS, and `just ci-gates`.
Run heavy commands through `build-gate --lane ripdpi`.
