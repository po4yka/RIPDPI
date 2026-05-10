---
title: Implement IPv6 extension header injection in ripdpi-strategy-ipv6 crate
type: task
status: blocked
area: rust-native
priority: low
owner: unassigned
parent: zapret2-feature-parity-epic
blocks: []
blocked_by: [expose-existing-techniques-as-config-addressable]
created: 2026-05-09
updated: 2026-05-10
---

- [ ] #task Implement IPv6 extension header injection in ripdpi-strategy-ipv6 crate #repo/RIPDPI #area/rust-native #status/blocked #blocked

## Objective

Implement IPv6 extension header injection in a new `ripdpi-strategy-ipv6` crate. This prepends HopByHop, DestinationOptions, or Routing extension headers to the outgoing IPv6 TCP segment to confuse DPI systems that do not correctly walk the IPv6 extension header chain before parsing the TCP layer.

## Context

zapret2 supports IPv6 extension header injection in its `nfqws2` binary (`/Users/po4yka/GitRep/zapret2/nfq2/darkmagic.h` and darkmagic.c — search for `ipv6_ext`, `hop_by_hop`, routing header injection). Some DPI systems (TSPU in particular, documented in the censorship-bypass vault) parse IPv6 as if next-header always points directly to TCP/UDP, failing to walk the extension header chain. Injecting a harmless DestinationOptions header (with a pad option) makes the outer IPv6 header appear to carry a different next-header value (0x3C = Destination Options) while TCP is still the inner protocol.

On Android in Mode.VPN (Tier 3), raw IPv6 packets are read from TUN. The strategy wraps the outgoing IPv6 TCP packet with an additional extension header:

1. Read IPv6 packet from TUN
2. Prepend DestinationOptions header (next-header=TCP, length=1, pad6 option bytes) between the IPv6 base header and TCP header
3. Update outer IPv6 next-header field to 0x3C (Destination Options)
4. Increment IPv6 payload length by extension header size
5. Recompute TCP checksum (the pseudo-header uses the updated addresses; the checksum itself covers TCP payload only, not extension headers — verify against RFC 2460)
6. Write modified packet back to TUN

For Tier 1 (raw socket, root): build a complete IPv6+ext+TCP raw packet and send via `IPPROTO_RAW` with `IPV6_HDRINCL`.

Extension headers to support:
- HopByHop (0x00): pad with PadN option — mostly harmless, traversed by every router
- DestinationOptions (0x3C): pad option — processed only at destination
- Routing (0x2B, Type 0 is deprecated; use Type 4 Segment Routing stub): purely confusing to DPI

## Acceptance criteria

- [ ] `ripdpi-strategy-ipv6` compiles; `Ipv6ExtHdrStrategy` implements `DesyncStrategy`
- [ ] `matches()` returns true only for IPv6 connections (`dissect.is_ipv6 == true`)
- [ ] DestinationOptions injection produces a valid IPv6 packet (passes `ip6 verify` sanity check — no kernel drop on loopback)
- [ ] TCP checksum remains valid after extension header insertion
- [ ] Extension header type is configurable: `"hopbyhop"`, `"destopts"`, `"routing"` via YAML `ext_type` param
- [ ] Tier 3 (Mode.VPN required) is declared in `describe().required_capabilities`
- [ ] Strategy no-ops for IPv4 connections without error
- [ ] YAML config: `type: ipv6_ext` with `ext_type: destopts`
- [ ] Unit test: given known IPv6+TCP packet bytes, after strategy application verify next-header=0x3C and inner TCP intact

## Source references

- zapret2 IPv6 ext header code: `/Users/po4yka/GitRep/zapret2/nfq2/darkmagic.h` and `.c` — ipv6 extension handling
- RFC 2460 (IPv6 extension headers) — standard reference
- RIPDPI IP fragmentation (raw packet building): `native/rust/crates/ripdpi-privileged-ops/src/linux/fragmentation.rs` — `build_tcp_fragment_pair()` for packet builder pattern to reuse
- RIPDPI TUN loop: Mode.VPN TUN read/write for Tier 3 path

## TDD workflow

1. **Write tests first** — before any implementation code, write golden byte tests with known IPv6+TCP input packets and the exact expected output after extension header insertion.
2. **Confirm red** — run `cargo test -p ripdpi-strategy-ipv6` and confirm golden tests fail because the modifier doesn't exist.
3. **Implement** — write the packet modifier to produce the expected bytes.
4. **Confirm green** — run the full crate test suite; zero regressions.
5. **Refactor** — clean up while keeping tests green.

**Test files to create before implementation:**
- `native/rust/crates/ripdpi-strategy-ipv6/tests/destopts_golden.rs` — construct known IPv6+TCP packet bytes; apply DestinationOptions injection; assert: byte at IPv6 next-header offset is `0x3C`, IPv6 payload length increased by ext header size, inner TCP header intact at new offset; fails until injection is implemented
- `native/rust/crates/ripdpi-strategy-ipv6/tests/tcp_checksum_valid.rs` — after DestinationOptions injection, compute TCP checksum over the modified pseudo-header and data; assert it matches the checksum field in the modified packet; fails until checksum recalculation is implemented
- `native/rust/crates/ripdpi-strategy-ipv6/tests/hopbyhop_golden.rs` — same pattern for HopByHop (next-header `0x00`); assert outer next-header is `0x00` after injection
- `native/rust/crates/ripdpi-strategy-ipv6/tests/ipv4_noop.rs` — apply strategy to an IPv4 packet; assert it is returned unchanged with no error; fails if IPv4 check is missing
- `native/rust/crates/ripdpi-strategy-ipv6/tests/strategy_capability_tier.rs` — assert `describe().required_capabilities` includes Mode.VPN (Tier 3); fails until capability declaration is added
- `native/rust/crates/ripdpi-strategy-ipv6/tests/yaml_ext_type_param.rs` — parse YAML `type: ipv6_ext, ext_type: destopts`; assert correct `Ipv6ExtParams` produced; fails until param deserialization exists

## Definition of done

`cargo test -p ripdpi-strategy-ipv6` green including packet byte golden test. Tests were written and confirmed red before implementation began; the relevant test command is green with no regressions.

## Work log

2026-05-10:

- Added `ripdpi-strategy-ipv6` with `Ipv6ExtHdrStrategy`, configurable `Ipv6ExtType` (`hopbyhop`, `destopts`, `routing`), raw IPv6 TCP extension-header insertion, and IPv6 TCP checksum recalculation.
- Registered `ipv6_ext` as a built-in strategy and exposed `type: ipv6Ext` plus `ext_type` parsing in the strategy YAML model.
- Added golden tests for Destination Options, Hop-by-Hop, and Routing insertion, TCP checksum validation, IPv4 no-op behavior, strategy capability/match behavior, and YAML/registry resolution.
- Confirmed red first with `CARGO_TARGET_DIR=target/codex-ipv6-red cargo test -p ripdpi-strategy-ipv6 --offline`; the initial stub failed on missing YAML fields and test-helper lifetime errors.
- Verification:
  - `cargo fmt --all`
  - `CARGO_TARGET_DIR=target/codex-ipv6 cargo test -p ripdpi-strategy-ipv6 --locked`
  - `CARGO_TARGET_DIR=target/codex-ipv6 cargo clippy -p ripdpi-strategy-ipv6 -p ripdpi-strategy-config -p ripdpi-strategy-registry --all-targets --locked -- -D warnings`
  - `CARGO_TARGET_DIR=target/codex-ipv6 cargo test -p ripdpi-strategy-config -p ripdpi-strategy-registry --locked`
- Added Android tunnel JNI evidence that `Tun2SocksConfig.strategyChainYaml` accepts `type: ipv6Ext` with `ext_type: destopts` alongside zapret egress entries.
- Verification: clean detached worktree `ANDROID_HOME=$HOME/Library/Android/sdk ANDROID_SDK_ROOT=$HOME/Library/Android/sdk ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.poyka.ripdpi.integration.NativeBridgeInstrumentedTest#rawBindingsAcceptZapretEgressStrategyTunnelConfig -Pripdpi.localNativeAbis=arm64-v8a` passed on `Pixel_10_Pro(AVD) - 17` with 1 test.
- Added packet-loop evidence that consumed egress injections bypass normal routing, while the existing interceptor test proves `type: ipv6Ext` emits the modified IPv6 packet bytes.
- Verification: `cargo fmt --manifest-path native/rust/Cargo.toml --all --check`; `CARGO_TARGET_DIR=/Users/po4yka/GitRep/.codex-targets/ripdpi-tunnel-egress-loop cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-tunnel-core egress --locked` passed 6 tests; `CARGO_TARGET_DIR=/Users/po4yka/GitRep/.codex-targets/ripdpi-tunnel-egress-loop cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-tunnel-core --all-targets --locked -- -D warnings` passed.
- Blocked validation: live attached-device validation that TUN reinjection/raw IPv6 packet injection is accepted by Android cannot be completed on the current stock non-root Android target. The egress path reaches `ripdpi_privileged_ops::send_raw_ip_packet()`, which opens a `SOCK_RAW` / `IPPROTO_RAW` socket; `VpnService.protect(fd)` prevents VPN routing loops but does not grant `CAP_NET_RAW`.
- Added rooted-device runtime wiring for `ipv6Ext` raw packet emission. The TUN injector now routes raw sends through `ripdpi-runtime-platform`, so a registered root-helper socket can perform privileged raw IPv6 injection before falling back to direct app-process raw sockets. Verification: `CARGO_TARGET_DIR=/Users/po4yka/GitRep/.codex-targets/ripdpi-root-helper-raw cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-tunnel-core --locked tun_egress_interceptor` passed including `ipv6_ext_rule_injects_modified_tcp_packet`; `./gradlew :core:engine:testDebugUnitTest --tests com.poyka.ripdpi.core.NativeBinaryContractTest -Pripdpi.skipNativeBuild=true` passed after adding `rootHelperSocketPath` to the tunnel config contract. Still blocked until live rooted-device raw IPv6 egress is proven.
- Fixed root-helper lifecycle activation so root-mode service starts and publishes a root-helper socket before native proxy/tunnel preferences are assembled and stop it during runtime cleanup. Verification: `./gradlew :core:service:ktlintCheck :core:engine:ktlintCheck :core:service:testDebugUnitTest --tests com.poyka.ripdpi.services.ConnectionPolicyResolverTest --tests com.poyka.ripdpi.services.ServiceSessionModuleTest :core:engine:testDebugUnitTest --tests com.poyka.ripdpi.core.RipDpiProxyUIPreferenceMappersTest --tests com.poyka.ripdpi.core.RipDpiProxyPreferencesTest -Pripdpi.skipNativeBuild=true`. Still blocked until live rooted-device raw IPv6 egress is proven.
- Fixed stale VPN tunnel root-helper socket capture for `ipv6Ext`. `VpnTunnelRuntime` now reads the socket at tunnel start, after policy resolution starts the helper. Verification: `./gradlew :core:service:ktlintCheck :core:service:testDebugUnitTest --tests com.poyka.ripdpi.services.VpnTunnelRuntimeTest --tests com.poyka.ripdpi.services.ServiceSessionModuleTest -Pripdpi.skipNativeBuild=true`. Still blocked until live rooted-device raw IPv6 egress is proven.
