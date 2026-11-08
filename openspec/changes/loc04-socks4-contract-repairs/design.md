## Context

The LOC-04 audit at 3e6e2dd2c870c20749c4184348a6da6ba2336a56 reproduced six defects.
Use the existing blocking TCP worker runtime and its local network fixtures.

## Goals / Non-Goals

- Goal: repair F1 through F6 with a focused commit and regression evidence for each.
- Non-goal: BIND, UDP, IDENT, new resolver transports, dependencies, or provider changes.

## Decisions

- Reuse the established connected-before-ACK route for SOCKS4 delayed requests. Preserve route selection and server-first operation.
- For the SOCKS4 Always-WS branch, prepare the real WS carrier for the DC inferred from the requested target before ACK. Reuse that session after seed classification when the DC matches; otherwise release it and retain the existing classified route/fallback. Do not require direct TCP reachability to the target. Extend the WS transport port with an object-safe prepared session only as needed; existing relay entry points remain available.
- The prepared WS stage has one deadline across DNS, TCP, TLS, and HTTP upgrade. Borrow an owner-stop callback during open, cancel stalled bootstrap without detached work, and check stop before ACK. Retain normal-route fallback for non-MTProto traffic if a provisional WS endpoint fails.
- Treat pre-payload FIN as a directional close. Keep the other direction in the existing relay.
- Validate the final address family and the exact SOCKS4a sentinel in the session owner.
- Pass runtime cancellation and the remaining handshake deadline to the existing cancellable DNS resolver. A FIN alone is not operation cancellation.
- Keep request size bounds and map a recognized SOCKS4 size rejection to its wire failure reply.
- Add behavior tests to owning crates; compare wire output with literal protocol bytes and real loopback origins.

## Contracts and ownership

- Affected crates: ripdpi-session, ripdpi-proxy-runtime, existing resolver callers through ripdpi-ws-bootstrap, ripdpi-ws-transport-port, and ripdpi-ws-tunnel.
- Kotlin, JNI, protobuf, storage and configuration schemas remain unchanged.
- The portfolio task records independent worker paths. Root serializes planning, snapshots and integration. Do not edit Cargo.lock, version catalogs, baselines or golden fixtures.
- Each worker uses a dedicated worktree. Root transfers each reviewed commit separately into the integration branch.

## Risks / Trade-offs

- Shared relay code affects SOCKS5 and HTTP: run their existing tests and duplex tests.
- ACK timing can affect delayed routing and WS: test refused targets, no duplicate connects, and server-first traffic; preserve payload policy checks.
- DNS cancellation can break half-close if FIN is treated as disconnect: cancel on owner stop and deadline only.
- A closed upstream in WS normal fallback must stop being polled after FIN. Preserve client writes with the shared first-payload reader and its TLS assembly.
- The existing Android protect IPC is outside the new network bootstrap loop. Count its elapsed time and recheck cancellation before outbound connect. Its Unix connect bound and device behavior are not established by host evidence.
- Limits must not grow: exercise both sides of USERID and domain bounds.

## Migration Plan

No data migration is needed. Revert the relevant fix commit to roll back that behavior.
Before integration run targeted regression tests, affected crate suites, Rust format and clippy, independent review, just ci-gates, architecture health, and locked Cargo metadata.
Only host and loopback validation is required for these Rust repairs; hosted CI and Android/device/provider acceptance are separate evidence and are not claimed.
