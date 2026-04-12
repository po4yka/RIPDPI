# Integrations Roadmap - Phase P2 Implementation

This phase is the relay-protocol expansion track. It should build on the transports and hardening completed earlier instead of reopening already-stable P0 and P1 work.

## Scope

- [Item 9 - VLESS + Reality client](roadmap-integrations.md#9-vless--reality-client)
- [Item 10 - Hysteria2 with Salamander](roadmap-integrations.md#10-hysteria2-with-salamander)
- [Item 11 - Chain relay support](roadmap-integrations.md#11-chain-relay-support)
- [Item 12 - MASQUE protocol](roadmap-integrations.md#12-masque-protocol-http3--tcp-fallback)
- [Item 20 - TUIC v5](roadmap-integrations.md#20-tuic-v5-protocol)
- [Item 21 - ShadowTLS v3](roadmap-integrations.md#21-shadowtls-v3)
- [Item 22 - NaiveProxy](roadmap-integrations.md#22-naiveproxy)
- [Item 23 - Multiplexed connections](roadmap-integrations.md#23-multiplexed-connections-smux--yamux)
- [Item 37 - Server-capability-aware conditional desync](roadmap-integrations.md#37-server-capability-aware-conditional-desync-strategy)

## Current Repo Footing

- VLESS, Hysteria2, chain relay, and MASQUE are already in the tree. The relay runtime currently supports them through `native/rust/crates/ripdpi-relay-core`, `native/rust/crates/ripdpi-vless`, `native/rust/crates/ripdpi-hysteria2`, and `native/rust/crates/ripdpi-masque`.
- Kotlin-side relay orchestration is already live through `core/service/src/main/kotlin/com/poyka/ripdpi/services/UpstreamRelaySupervisor.kt`, and the engine bridge already serializes relay config through `RipDpiRelay.kt`.
- The current MASQUE and Hysteria2 status is already documented in [docs/native/relay-masque-status.md](native/relay-masque-status.md). Use that as the baseline before expanding protocol count.
- The NaiveProxy go-decision is recorded in [docs/native/relay-naiveproxy-decision.md](native/relay-naiveproxy-decision.md).
- Relay settings today only model `vless_reality`, `hysteria2`, `chain_relay`, and `masque`. TUIC, ShadowTLS, and NaiveProxy require deliberate data-model and UI additions instead of ad hoc flags.

## Recommended Sequence

1. Treat items 9 through 12 as consolidation first, not new research. Close the gaps in live interoperability coverage, deployer-backed auth, and operator-facing UX for the relay transports that are already implemented before adding more protocol kinds.

2. Add a reusable connection-management substrate before more relay protocols arrive. Item 23 should provide the abstraction for connection reuse and stream fan-out so later transports do not each invent their own pooling, multiplexing, and backpressure behavior.

3. Add TUIC v5 next. It fits the existing Rust async and QUIC-heavy stack better than NaiveProxy, shares concerns with Hysteria2, and should integrate through the same `ripdpi-relay-core` facade and Kotlin relay config path.

4. Add ShadowTLS v3 as a composable camouflage layer, not as a monolithic relay mode tied to one server topology. The preferred design is a reusable wrapper around an inner relay protocol rather than a dead-end one-off client.

5. Evaluate NaiveProxy only as a bundled subprocess. Do not attempt Chromium embedding in the Android binary. If it lands at all, it should reuse the existing relay process-management conventions and be clearly isolated from the native Rust runtime.

6. Use item 37 to improve protocol selection quality after the transport matrix is larger. Server-capability caches belong close to the native connection and failure-classification path so transport choice and desync choice can share the same evidence.

## Repo Touchpoints

- Native relay stack: `native/rust/crates/ripdpi-relay-core`, `native/rust/crates/ripdpi-vless`, `native/rust/crates/ripdpi-hysteria2`, `native/rust/crates/ripdpi-masque`
- Future protocol insertion points: `native/rust/crates` for new crates such as `ripdpi-tuic` or `ripdpi-shadowtls`
- Kotlin orchestration: `core/service/src/main/kotlin/com/poyka/ripdpi/services/UpstreamRelaySupervisor.kt`, `core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiRelay.kt`
- Config and UI: `core/data/src/main/kotlin/com/poyka/ripdpi/data/RelaySettings.kt`, `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/config/RelayFields.kt`, `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/config/ModeEditorScreen.kt`
- Existing relay status note: `docs/native/relay-masque-status.md`

## Exit Criteria

- Existing relay transports have live or near-live interoperability coverage and no longer rely only on unit tests.
- Connection reuse and multiplexing are handled by a shared design instead of protocol-specific patches.
- TUIC and ShadowTLS, if added, appear as first-class relay options with config, runtime, and tests.
- NaiveProxy has a clear go or no-go decision based on binary size, maintenance burden, and Android process model.
- Conditional desync can use server capabilities without duplicating caches and heuristics across modules.

## Current Closure Status

The P2 exit criteria above are now satisfied in-repo. Remaining relay work after this phase is future hardening, interoperability expansion, or new transport scope, not unfinished P2 implementation.

## Remaining Follow-Up Scope

- MASQUE still has Cloudflare-direct interoperability hardening and rollout validation work tracked in [relay-masque-status.md](native/relay-masque-status.md).
- NaiveProxy is no longer an open transport-selection question. Future work is limited to subprocess hardening, readiness polish, and operational validation, as recorded in [relay-naiveproxy-decision.md](native/relay-naiveproxy-decision.md).
- Additional relay transports or deployment presets can still be added later, but they are outside the closed P2 roadmap scope.
