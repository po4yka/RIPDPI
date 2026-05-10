---
title: Port zapret2 DPI-bypass technique set into RIPDPI Rust+Android framework
type: epic
status: blocked
area: epic
priority: high
owner: Codex
parent: null
blocks: []
blocked_by:
  - expose-existing-techniques-as-config-addressable
  - add-udp-length-falsification-strategy
  - add-ipv6-extension-header-injection
  - implement-lua-api-surface
created: 2026-05-09
updated: 2026-05-10
---

- [ ] #task Port zapret2 DPI-bypass technique set into RIPDPI Rust+Android framework #repo/RIPDPI #area/epic #status/blocked #blocked 🔼

## Goal

Port the full zapret2 DPI-bypass technique set into RIPDPI's Rust+Android framework with three strategy backends: Rust-native implementations, a YAML/TOML config-driven compositor, and an embedded Lua scripting runtime (mlua, feature-gated). This gives RIPDPI parity with zapret2's packet manipulation capabilities while keeping the Android-first (no NFQUEUE, no root required) architecture and the existing UCB1 adaptive strategy evolver.

## Why now

zapret2's Lua strategy library (`/Users/po4yka/GitRep/zapret2/lua/zapret-antidpi.lua`) contains 30+ techniques, several of which are absent from RIPDPI: HTTP header manipulation, synack/synack_split, TCP window clamping (wsize/wssize), UDP length falsification, IPv6 extension headers, and 6 new L7 protocol classifiers. Users who migrated from zapret2 on Linux/BSD need a migration path.

## Key decisions

- Single `DesyncStrategy` trait that all three backends (Rust, config, Lua) implement — one execution path for the UCB1 evolver
- Lua is an optional Cargo feature (`lua-strategies`) so non-Lua builds stay lean
- Config schema extends existing protobuf (DesyncGroup) rather than replacing it
- Android TUN write replaces NFQUEUE — no kernel module dependency
- All new functionality is developed test-first: tests are written and confirmed failing before implementation begins, covering every acceptance criterion in the child tasks

## Scope

5 implementation phases, 19 child tasks. Excludes Windows/BSD-specific capture backends (NFQUEUE/WinDivert/ipfw) — Android only.

## Ship definition

- [ ] All zapret2 techniques listed in `lua/zapret-antidpi.lua` have either a Rust-native impl or are expressible in the config schema
- [ ] Lua backend loads and runs existing zapret2 scripts unmodified (compatibility shim)
- [ ] Strategy chain can be specified via YAML config without code changes
- [ ] All new raw-socket operations call `VpnService.protect(fd)` through the existing JNI callback
- [ ] `ripdpi-capabilities` tier detection covers all new technique capability requirements
- [ ] Every child task has a corresponding test file committed before its implementation; `cargo test --workspace` and `./gradlew test` are green on main

## Child tasks

- add-ripdpi-strategy-trait-crate
- refactor-plan-tcp-to-desynpstrategy-trait
- add-ripdpi-strategy-registry-crate
- add-ripdpi-strategy-config-yaml-loader
- expose-existing-techniques-as-config-addressable
- add-strategy-config-editor-screen
- add-http-header-manipulation-strategies
- add-tcp-window-size-clamping-strategies
- add-udp-length-falsification-strategy
- add-synack-split-tun-interception
- add-ipv6-extension-header-injection
- expand-l7-protocol-detection
- add-ripdpi-strategy-lua-crate
- implement-lua-api-surface
- bundle-zapret2-lua-library-as-assets
- add-jni-bindings-for-lua-script-management
- extend-diagnostic-probe-service
- integrate-probe-results-with-strategy-evolver
- add-blockcheck-report-screen

## Dependencies

None (greenfield epic)

## Risks

- mlua adds ~2MB to APK
- Lua 5.4 vendored build may conflict with Android NDK version
- TCP_REPAIR (Tier 2) unavailable on stock Android

## Work log

- 2026-05-10: All 19 child tasks are either closed through prior commits or in review with implementation commits. Parent epic moved to review pending final board closure.
- 2026-05-10: Added attached-emulator evidence for the Lua asset/JNI path: `StrategyEngineJniInstrumentedTest` passed on `Pixel_10_Pro(AVD) - 17` via `am instrument`, `OK (2 tests)`.
- 2026-05-10: Extended attached-emulator JNI evidence to native strategy YAML validation; `StrategyEngineJniInstrumentedTest` passed with `OK (4 tests)` including `fake`, `udplen`, and `ipv6Ext` registry YAML.
- 2026-05-10: Re-ran current-checkout Android JNI evidence with `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.poyka.ripdpi.jni.StrategyEngineJniInstrumentedTest -Pripdpi.localNativeAbis=arm64-v8a` on `emulator-5554` (`Pixel_10_Pro(AVD) - 17`). The first attempt failed with `INSTALL_FAILED_INSUFFICIENT_STORAGE`; after wiping the AVD data partition, the rerun passed 4 tests. Final closure still needs live VPN/TUN malformed-packet egress evidence for the review-gap tasks.
- 2026-05-10: Added Android tunnel JNI config evidence: `NativeBridgeInstrumentedTest#rawBindingsAcceptZapretEgressStrategyTunnelConfig` passed in a clean detached worktree with native arm64 artifacts on `Pixel_10_Pro(AVD) - 17`, proving `Tun2SocksConfig.strategyChainYaml` plus `protectPath` reaches `Tun2SocksNativeBindings.create()` for `fake`, `udplen`, and `ipv6Ext`. Final closure still needs live VPN/TUN malformed-packet egress evidence.
- 2026-05-10: Added host-side packet-loop evidence for VPN egress interception. `ripdpi-tunnel-core egress` tests passed 6/6 and clippy passed, proving consumed egress injections bypass normal routing while non-consuming fake-packet flows preserve normal forwarding. Final closure still needs live Android raw-socket/TUN egress proof.
- 2026-05-10: Added Strategy Config active-service apply coverage. A clean detached worktree passed `:app:ktlintCheck` and `MainViewModelTest`, including awaited settings persistence plus automatic active-service restart after saving Strategy Config YAML/DSL. Final closure still needs live Android raw-socket/TUN egress proof and manual picker/share-sheet validation.
- 2026-05-10: Added Strategy Config Import/Export Compose callback coverage. A clean detached worktree passed `StrategyConfigScreenTest`, proving the in-app buttons invoke their callbacks. Final closure still needs Android external picker/share-sheet validation plus live Android raw-socket/TUN egress proof.
- 2026-05-10: Moved the parent epic to blocked because the remaining raw-socket/TUN egress proof cannot be completed on the current stock non-root Android target. The Android path uses `SOCK_RAW` / `IPPROTO_RAW`; `VpnService.protect(fd)` only prevents VPN routing loops and does not grant `CAP_NET_RAW`.
- 2026-05-10: Closed Strategy Config external UI validation. On `emulator-5554` (`Pixel_10_Pro(AVD) - 17`), a native `arm64-v8a` debug APK opened the Android document picker, selected `/sdcard/Download/ripdpi-zapret2-sample.yaml`, returned to Strategy Config with `Config imported` and the imported YAML buffer, then opened the Android share sheet with the YAML text preview. Final closure still needs live Android raw-socket/TUN egress proof.
- 2026-05-10: Wired TUN raw packet injection through the opt-in root-helper path for rooted devices. `Tun2SocksConfig.rootHelperSocketPath` now flows from `RootHelperManager` into native tunnel config; the tunnel worker registers the root-helper socket, and `fake` / `udplen` / `ipv6Ext` / Lua `rawsend` raw packet execution uses `ripdpi-runtime-platform` before falling back to direct app-process raw sockets. Verification: `cargo fmt --manifest-path native/rust/Cargo.toml --all --check`; `CARGO_TARGET_DIR=/Users/po4yka/GitRep/.codex-targets/ripdpi-root-helper-raw cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-runtime-platform -p ripdpi-root-helper-protocol -p ripdpi-tunnel-android --locked root_helper -- --nocapture`; `CARGO_TARGET_DIR=/Users/po4yka/GitRep/.codex-targets/ripdpi-root-helper-raw cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-tunnel-android tunnel_config_field_manifest_matches_contract_fixture --locked`; `CARGO_TARGET_DIR=/Users/po4yka/GitRep/.codex-targets/ripdpi-root-helper-raw cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-tunnel-core --locked tun_egress_interceptor`; `CARGO_TARGET_DIR=/Users/po4yka/GitRep/.codex-targets/ripdpi-root-helper-raw cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-root-helper --locked send_raw_ip_packet -- --nocapture`; `./gradlew :core:service:ktlintCheck :core:engine:ktlintCheck :core:service:testDebugUnitTest --tests com.poyka.ripdpi.services.RipDpiVpnServiceConfigTest --tests com.poyka.ripdpi.services.VpnTunnelRuntimeTest -Pripdpi.skipNativeBuild=true`; `./gradlew :core:engine:testDebugUnitTest --tests com.poyka.ripdpi.core.NativeBinaryContractTest -Pripdpi.skipNativeBuild=true`. The epic remains blocked pending live rooted-device raw egress proof.
- 2026-05-10: Android-target native verification covered the cfg-gated root-helper client path: `PATH="$HOME/Library/Android/sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH" ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/29.0.14206865" CARGO_TARGET_DIR=/Users/po4yka/GitRep/.codex-targets/ripdpi-root-helper-raw-android cargo check --manifest-path native/rust/Cargo.toml -p ripdpi-runtime-platform --target aarch64-linux-android --locked` passed.
- 2026-05-10: Fixed root-helper lifecycle activation for root mode. `ConnectionPolicyResolver` now starts or stops `RootHelperManager` from the current `rootModeEnabled` setting before assembling native proxy preferences, proxy/VPN runtime coordinators stop the helper during cleanup, service destruction also stops it, and UI preference JSON decoding preserves `rootMode`, `rootHelperSocketPath`, and `environmentKind`. Verification: `./gradlew :core:service:ktlintCheck :core:engine:ktlintCheck :core:service:testDebugUnitTest --tests com.poyka.ripdpi.services.ConnectionPolicyResolverTest --tests com.poyka.ripdpi.services.ServiceSessionModuleTest :core:engine:testDebugUnitTest --tests com.poyka.ripdpi.core.RipDpiProxyUIPreferenceMappersTest --tests com.poyka.ripdpi.core.RipDpiProxyPreferencesTest -Pripdpi.skipNativeBuild=true` passed. The epic remains blocked pending live rooted-device raw egress proof.
- 2026-05-10: Fixed stale VPN tunnel root-helper socket capture. `VpnTunnelRuntime` now reads the root-helper socket through a provider at tunnel start, after policy resolution has started the helper, instead of capturing `null` when the session component is built. Verification: `./gradlew :core:service:ktlintCheck :core:service:testDebugUnitTest --tests com.poyka.ripdpi.services.VpnTunnelRuntimeTest --tests com.poyka.ripdpi.services.ServiceSessionModuleTest -Pripdpi.skipNativeBuild=true` passed.
- 2026-05-10: Hardened root-helper readiness and shutdown. `RootHelperManager` now waits until the helper Unix socket is connectable before publishing `rootHelperSocketPath`, and `stop()` uses a bounded wait before force-killing an unresponsive helper process. Verification: `./gradlew :core:service:ktlintCheck :core:service:testDebugUnitTest --tests com.poyka.ripdpi.services.RootHelperManagerTest --tests com.poyka.ripdpi.services.ConnectionPolicyResolverTest --tests com.poyka.ripdpi.services.VpnTunnelRuntimeTest -Pripdpi.skipNativeBuild=true` passed. The epic remains blocked pending live rooted-device raw egress proof.
