# Integrations Roadmap - Phase P1 Implementation

This file groups the medium-term hardening work that expands the existing engine, routing, and Android platform behavior after the P0 transport path is stable.

## Scope

- [Item 4 - WARP endpoint scanner](roadmap-integrations.md#4-warp-endpoint-scanner)
- [Item 5 - Auto-strategy with trigger-based fallback](roadmap-integrations.md#5-auto-strategy-with-trigger-based-fallback)
- [Item 6 - HTTP header manipulation steps](roadmap-integrations.md#6-http-header-manipulation-steps)
- [Item 7 - SYN data](roadmap-integrations.md#7-syn-data-tcp-fast-open-style)
- [Item 8 - IPv6 extension header injection](roadmap-integrations.md#8-ipv6-extension-header-injection)
- [Item 17 - TCP timestamp faking](roadmap-integrations.md#17-tcp-timestamp-faking)
- [Item 18 - ClientHello clone for fake packets](roadmap-integrations.md#18-clienthello-clone-for-fake-packets)
- [Item 19 - Double fake strategy](roadmap-integrations.md#19-double-fake-strategy)
- [Item 24 - Per-app UID exclusion](roadmap-integrations.md#24-per-app-uid-exclusion-for-vpn-detection-bypass)
- [Item 27 - Dual-IP VPS support](roadmap-integrations.md#27-ip-correlation-deanonymization-defense--dual-ip-vps-support)
- [Item 28 - Package name regex routing](roadmap-integrations.md#28-per-app-package-name-regex-routing)
- [Item 29 - Russian cloud relay preset](roadmap-integrations.md#29-russian-cloud-relay-preset-for-mobile-whitelist-bypass)
- [Item 30 - Finalmask obfuscation framework compatibility](roadmap-integrations.md#30-xray-core-finalmask-obfuscation-framework)
- [Item 31 - AmneziaWG PayloadGen presets](roadmap-integrations.md#31-amneziawg-payloadgen--protocol-imitation-for-junk-packets)
- [Item 33 - TCP receive window reduction](roadmap-integrations.md#33-tcp-receive-window-reduction-wssize--force-server-side-chunking)
- [Item 34 - DHT trigger CIDR mitigation](roadmap-integrations.md#34-dhtbittorrent-udp-trigger-cidr-detection-and-mitigation)
- [Item 35 - Protocol-agnostic desync](roadmap-integrations.md#35-protocol-agnostic-desync-mode-any_protocol-flag)
- [Item 36 - Cross-origin session correlation defense](roadmap-integrations.md#36-cross-origin-session-correlation-defense-mintsifry-dual-script-attack)

## Current Repo Footing

- The native desync and runtime stack already exists in `native/rust/crates/ripdpi-desync`, `native/rust/crates/ripdpi-runtime`, `native/rust/crates/ripdpi-packets`, and `native/rust/crates/ripdpi-failure-classifier`.
- Detection and diagnostics already expose enough plumbing to validate many P1 items through `core/diagnostics`, `core/detection`, and `native/rust/crates/ripdpi-monitor`.
- Strategy-pack and detection-resistance config surfaces already exist in `core/data/src/main/kotlin/com/poyka/ripdpi/data/StrategyPackSettings.kt` and `core/data/src/main/kotlin/com/poyka/ripdpi/data/DetectionResistanceSettings.kt`.
- Android app exclusion is not greenfield. `core/service/src/main/kotlin/com/poyka/ripdpi/services/VpnAppExclusionPolicy.kt` already ships a static Russian app exclusion list, and `RipDpiVpnService.kt` already applies `addDisallowedApplication`.
- WARP already exposes scanner and Amnezia knobs in `WarpSettings.kt`, which makes items 4 and 31 mostly about turning stored fields into tested runtime behavior.

## Recommended Sequence

1. Land the low-risk desync upgrades first. Items 6, 7, 17, 18, 19, 33, and 35 fit naturally into the existing packet-mutation path and should be added with golden fixtures and failure-classifier coverage before more adaptive work starts.

2. Add adaptive fallback on top of that stable primitive set. Implement item 5 by extending the current per-network policy memory with per-destination trigger caches, and keep the decision engine close to `ripdpi-failure-classifier` and `ripdpi-session` instead of scattering it across Kotlin service code.

3. Treat WARP and relay operational hardening as one stream. Item 4 should produce a real endpoint-selection loop, item 30 should document and validate interoperability with Finalmask-enabled servers, item 31 should turn raw Amnezia parameters into operator presets, and item 34 should add a concrete mitigation for DHT-triggered failures in VPN mode.

4. Replace static Android exclusions with data-driven routing. Items 24, 28, and 36 should move the app from a hardcoded list to bundled policy data, regex expansion, richer UI disclosure, and routing decisions that understand Russian apps, global CDNs, and split-tunnel correlation risk.

5. Deliver the whitelist-era mobile preset after the routing substrate is ready. Items 27 and 29 depend on reliable relay config modeling, source-IP binding, and app-routing policy. Item 8 should stay VPN-mode-only and should not block the rest of the phase.

## Repo Touchpoints

- Native engine: `native/rust/crates/ripdpi-desync`, `native/rust/crates/ripdpi-runtime`, `native/rust/crates/ripdpi-failure-classifier`, `native/rust/crates/ripdpi-packets`, `native/rust/crates/ripdpi-monitor`
- Data and policy: `core/data/src/main/kotlin/com/poyka/ripdpi/data/StrategyPackSettings.kt`, `core/data/src/main/kotlin/com/poyka/ripdpi/data/DetectionResistanceSettings.kt`, `core/data/src/main/kotlin/com/poyka/ripdpi/data/WarpSettings.kt`, `core/data/src/main/kotlin/com/poyka/ripdpi/data/HostAutolearnSettings.kt`
- Service and VPN routing: `core/service/src/main/kotlin/com/poyka/ripdpi/services/VpnAppExclusionPolicy.kt`, `core/service/src/main/kotlin/com/poyka/ripdpi/services/RipDpiVpnService.kt`, `core/service/src/main/kotlin/com/poyka/ripdpi/services/VpnServiceRuntimeCoordinator.kt`
- UI and settings: `app/src/main/kotlin/com/poyka/ripdpi/activities/SettingsViewModel.kt`, `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/settings`, `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/config`

## Exit Criteria

- The desync engine has the new low-risk primitives, and they are covered by reproducible tests rather than only docs.
- Adaptive fallback uses runtime evidence and can avoid retry loops with clearly bad strategies.
- App exclusion and routing policies are data-driven, explainable in UI, and no longer hardcoded to a fixed package list only.
- Mobile whitelist mitigation has a concrete relay preset and routing story tied to existing per-network state.
- WARP scanner, PayloadGen presets, and DHT-trigger mitigation are all represented in runtime behavior, not just stored config fields.
