## Context

The existing default LAN Direct rule matches the configured private AWG DNS addresses. The native proxy first selects an AWG-enabled group, then the destination evaluator returns Direct and the TCP/UDP policy removes the upstream SOCKS endpoint. The AWG handshake succeeds while DNS bypasses the tunnel.

## Goals / Non-Goals

- Goal: exact active-profile resolver IPs, TCP/UDP ports 53 and 853, through AWG unless the original effective decision is Block.
- Non-goal: change ordinary LAN routing, reinterpret rule order, alter peer addresses, loosen Block, change AWG cryptography, or add retries.

## Decisions

- Omit the empty runtime-only resolver field with `@EncodeDefault(NEVER)`; Rust defaults an absent list to empty. Do not persist it as settings or regenerate unrelated goldens. Show any actual golden contract diff for named-family approval before a bless command.
- Carry a defaulted `warp.awgDnsServers` list from the captured AWG request through the actual native proxy envelope. Snapshot and validate addresses; do not use live mutable stores during packet routing.
- Project this list in `SharedProxyRuntimeStack` from the same request passed to the AWG supervisor. Clear any remembered list before a non-AWG start. The current helper drops `awg` after projection, so it must capture resolver data before that step.
- Add a typed resolver IP list to `RuntimeConfig`. Native conversion accepts a nonempty list only with the enabled AWG all-traffic marker and valid loopback upstream. Reject invalid numeric addresses and inconsistent authority.
- In the shared native `RuntimeState.destination_egress` decision, evaluate the existing policy first. Preserve Block. For any other result, exact resolver IP plus port 53 or 853 selects Tunneled. This common decision covers TCP and UDP without separate forwarding implementations.
- Keep the original destination policy and its canonical digest unchanged. Resolver authority belongs to the captured AWG runtime input, not to a rewritten persisted user rule set.
- Do not prepend an unconditional tunnel rule: that can bypass an effective Block and change first-match or GeoIP semantics.

## Contracts and ownership

Runtime worker owns these production lanes and their adjacent tests:

- `core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/RipDpiProxyUIConfig.kt`: defaulted model field.
- `core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiProxyPreferences.kt`, `RipDpiProxyUIWarpNormalization.kt`, `codec/WarpTunnelSectionCodec.kt`, and `codec/NativeProxyConfigValidation.kt`: frozen projection, clearing, codec, and validation.
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/SharedProxyRuntimeStack.kt`: active request authority.
- `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/awg/AwgActivationRequest.kt`: correct the documentation that DNS never crosses any native boundary; its stored shape remains unchanged.
- `native/rust/crates/ripdpi-proxy-config/src/types/warp.rs`, `convert/warp.rs`, and `convert.rs`: defaulted wire field, validation, typed projection.
- `native/rust/crates/ripdpi-config/src/model/runtime.rs` and its default constructor: typed resolver snapshot.
- `native/rust/crates/ripdpi-proxy-runtime/src/runtime/state.rs`, `state/routing.rs`, and a small pure routing helper if needed: one shared effective-egress rule.
- `docs/architecture/CONFIG_CONTRACTS.md`: current contract. Task/spec and generated board are serialized with the integration owner.

The JSON addition follows current additive-field rules: default empty on Kotlin and Rust, schemaVersion remains 2. This is a wire-field addition, not a claim of no contract change. No protobuf/Room migration, JNI function signature, unsafe code, new dependency, or credential logging is needed. Root review must approve this plan before production edits.

## Risks / Trade-offs

- Stale remembered resolver data could grant authority to an inactive tunnel: clear it at runtime composition and test active-to-inactive and profile switches.
- Broad tunnel overrides could bypass Block: test the original effective first-match result, including Direct-before-later-Block and Block-before-Direct.
- Mutable input lists could change after capture: copy at the projection boundary and test mutation isolation.
- Malformed or hostname resolver inputs must not trigger DNS lookup: validate numeric addresses at both boundaries and reject invalid authority.

## Migration Plan

Add regressions before implementation. Test IPv4/IPv6, TCP/UDP, ports 53/853, unrelated addresses and ports, empty/inactive inputs, malformed fields, ordinary WARP, remembered inputs, ordered blocks, and actual upstream selection. Run affected complete engine/service JVM suites and static gates; locked native config/runtime tests and Clippy under the shared build gate; strict task/OpenSpec validation and independent review. Rebuild the exact integrated Android artifact and rerun physical AWG DNS plus payload acceptance with seeded LAN rules unchanged. Rollback is a scoped revert; persisted profile/rule data does not migrate.
