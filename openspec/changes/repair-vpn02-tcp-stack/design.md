## Context

See proposal.md and the VPN-02 audit on base SHA 28eeb212e804f8adcee3daeba2b90a70976c2bed. The bridge uses smoltcp 0.14.0, bounded Tokio duplex streams, and a separate SOCKS task.

## Goals / Non-Goals

- Goal: repair F1 through F5 with separate commits and permanent behavior regressions.
- Non-goal: implement audit hypotheses, change relay protocols, use root, or claim device acceptance from host tests.

## Decisions

- Separate draining live relay resources from retained TCP close state. Preserve buffered bytes and repeated-FIN processing. Observe task completion after a directional EOF.
- Use getrandom 0.4, which is already in the workspace dependency graph and used by the WARP virtual TCP interface. Entropy errors stop initialization before readiness.
- Apply tcp-buffer-size to native RX/TX only; preserve the separate fixed duplex budget. Keep validation before allocation.
- Patch the existing smoltcp 0.14.0 source under vendor/smoltcp. There is no public MSS setter. Preserve both active and passive SYN handling, MSS-zero-as-absent, explicit MSS and its minimum clamp. Preserve license and archive checksum provenance. Do not rewrite received packets or edit Cargo cache.

## Contracts and ownership

- Kotlin/JNI schema, protobuf, storage and Android Builder stay unchanged.
- Lifecycle worker owns bridge/session code and its tests. Entropy worker owns tunnel_api.rs, the tunnel-core manifest and tests. Upstream worker owns vendor/smoltcp and workspace patch entry. Coordinator owns buffer callers, planning and integration.
- Cargo.lock is serialized: entropy worker first, upstream worker second, coordinator last. Workers do not edit the task board.

## Risks / Trade-offs

- Close retention must be bounded and must release completed relay buffers. Cover both half-close orders, repeated FIN, task failure after EOF and cancellation.
- Retained TCP state changes memory ownership. Keep existing capacity and queue limits; do not extend budgets or baselines.
- A vendored patch adds source maintenance, but keeps the same upstream package and license. Record the minimal patch and test family defaults and explicit MSS.

## Migration Plan

No persisted migration. Revert one repair commit if its regression cannot be resolved. Run owning Rust tests with --locked, affected clippy and formatting, independent read-only reviews, just ci-gates, architecture health and Cargo metadata before integration. Use the pinned Rust toolchain. Host tests do not establish Android or external compatibility. Do not launch hosted CI separately.
