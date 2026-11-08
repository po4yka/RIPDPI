## Context

See proposal.md and the VPN-02 audit on base SHA 28eeb212e804f8adcee3daeba2b90a70976c2bed. The bridge uses smoltcp 0.14.0, bounded Tokio duplex streams, and a separate SOCKS task.

## Goals / Non-Goals

- Goal: repair F1 through F5 with separate commits and permanent behavior regressions.
- Non-goal: implement audit hypotheses, change relay protocols, use root, or claim device acceptance from host tests.

## Decisions

- Separate draining live relay resources from retained TCP close state. Keep normal relay ownership until its task completes; cancellation grace does not limit an active normal drain. Poll task completion with the bridge waker. The vendored stack exposes an additive read-only normal-close outcome so the bridge can distinguish a normal LastAck or TIME-WAIT completion from a reset, including transitions within one packet batch. Preserve unread native RX across TIME-WAIT expiry while a bounded live relay owns it. Abnormal cleanup must discard unread data and cannot retain a socket forever. Do not infer close outcome from a sampled state history.
- Publish terminal TCP task outcomes with the existing bridge waker, including panic cleanup. Directional EOF removes the duplex read wake, so failure cleanup must not depend on an unrelated TCP timer or a new application packet. Do not consume a live JoinHandle merely to register this notification. A coalesced bridge notification starts a bounded sweep of all live entries across 64-entry batches. Native socket state changes and new admissions start the same sweep. The loop blocks after the sweep ends; it does not spin while idle.
- Use getrandom 0.4, which is already in the workspace dependency graph and used by the WARP virtual TCP interface. Entropy errors stop initialization before readiness.
- Apply tcp-buffer-size to native RX/TX only; preserve the separate fixed duplex budget. Keep validation before allocation.
- Patch the existing smoltcp 0.14.0 source under vendor/smoltcp. There is no public MSS setter. Preserve both active and passive SYN handling, MSS-zero-as-absent, explicit MSS and its minimum clamp. Preserve license and archive checksum provenance. Do not rewrite received packets or edit Cargo cache.

## Contracts and ownership

- Kotlin/JNI schema, protobuf, storage and Android Builder stay unchanged. The existing vendored Rust dependency gains one read-only close-outcome accessor; no application API or wire field changes.
- Lifecycle worker owns bridge/session code and its tests. Entropy worker owns tunnel_api.rs, the tunnel-core manifest and tests. Upstream worker owns vendor/smoltcp and workspace patch entry. Coordinator owns buffer callers, planning and integration.
- Cargo.lock is serialized: entropy worker first, upstream worker second, coordinator last. Workers do not edit the task board.

## Risks / Trade-offs

- Close retention must be bounded and must release completed relay buffers. Cover both half-close orders, repeated FIN, task failure after EOF and cancellation.
- Retained TCP state changes memory ownership. Keep existing capacity and queue limits; do not extend budgets or baselines.
- A vendored patch adds source maintenance, but keeps the same upstream package and license. Record the minimal patch and test family defaults and explicit MSS.

## Migration Plan

No persisted migration. Revert one repair commit if its regression cannot be resolved. Run owning Rust tests with --locked, affected clippy and formatting, independent read-only reviews, just ci-gates, architecture health and Cargo metadata before integration. Use the pinned Rust toolchain. Host tests do not establish Android or external compatibility. Do not launch hosted CI separately.
