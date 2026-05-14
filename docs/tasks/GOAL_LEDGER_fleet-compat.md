# Goal Ledger — epic-ripdpi-vpn-deploy-fleet-compatibility closure

Auto-generated. The transitive dependency closure of
`epic-ripdpi-vpn-deploy-fleet-compatibility` — every task it needs plus the epic itself — in
**topological order (feeders first)**. Working top-to-bottom keeps every
row's Blocked-by satisfied. The `/goal` loop edits only Status and Proof.

- Rows: **45**  ·  global source: `docs/tasks/GOAL_LEDGER.md`
- Phase size **12** -> **4** phases

| # | Phase | Task (ledger key = slug) | Type | Area | Prio | Status | Proof |
|---|-------|--------------------------|------|------|------|--------|-------|
| 1 | P1 | `add-base64-and-plain-uri-list-subscription-parser`<br>Add base64 and plain URI-list subscription parser | task | outbound | 🔺 | DONE | DONE — part of `:core:data:testDebugUnitTest` exit 0 (44 parser tests) |
| 2 | P1 | `add-clash-and-clash-meta-yaml-subscription-parser`<br>Add Clash and Clash.Meta YAML subscription parser | task | outbound | 🔺 | DONE | DONE — `:core:data:testDebugUnitTest` exit 0 |
| 3 | P1 | `add-proxygroup-and-subscription-entities-to-ripdpi-data-layer`<br>Add ProxyGroup and Subscription entities to RIPDPI data layer | task | outbound | 🔺 | DONE | DONE — `./gradlew :core:data:testDebugUnitTest` exit 0, BUILD SUCCESSFUL, 13 new tests pass |
| 4 | P1 | `add-sing-box-json-subscription-parser`<br>Add sing-box JSON subscription parser | task | outbound | 🔺 | DONE | DONE — `:core:data:testDebugUnitTest` exit 0 |
| 5 | P1 | `add-sing-box-selector-and-urltest-group-import-from-subscription`<br>Add sing-box selector and urltest group import from subscription | task | outbound | 🔺 | DONE | DONE — `:core:data:testDebugUnitTest` exit 0 |
| 6 | P1 | `add-singbox-uri-deeplink-intent-filter-and-handler`<br>Add sing-box URI deep-link Intent filter and handler | task | android | 🔺 | DONE | DONE — `:app:testDebugUnitTest` + `:app:assembleDebug` exit 0 (52 proxyimport tests) |
| 7 | P1 | `decouple-vless-xhttp-transport-from-the-reality-relay-kind`<br>Decouple VLESS xHTTP transport from the Reality relay kind | task | relay | 🔺 | DONE | DONE — 3-module verify exit 0; all app flavors compile |
| 8 | P1 | `add-amneziawg-kotlin-config-model-and-dot-conf-parser-extensions`<br>Add AmneziaWG Kotlin config model and dot-conf parser extensions | task | outbound | ⏫ | DONE | DONE — `:core:data:testDebugUnitTest` exit 0 (16 tests) |
| 9 | P1 | `add-duplicate-profile-detection-on-subscription-merge`<br>Add duplicate-profile detection on subscription merge | task | outbound | ⏫ | DONE | DONE — `:core:data:testDebugUnitTest` exit 0 (12 tests) |
| 10 | P1 | `add-grpc-transport-crate-with-tonic-and-xray-compatible-framing`<br>Add gRPC transport crate with tonic and Xray-compatible framing | task | transport | ⏫ | DONE | DONE — `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-xhttp` exit 0, 25 tests pass |
| 11 | P1 | `add-qr-scanner-screen-with-camerax-and-ml-kit`<br>Add QR scanner screen with CameraX and ML Kit | task | ui | ⏫ | DONE | DONE — `:app:testDebugUnitTest` + `:app:assembleDebug` exit 0 |
| 12 | P1 | `add-share-sheet-handler-for-proxy-uri-schemes`<br>Add share-sheet handler for proxy URI schemes | task | ui | ⏫ | DONE | DONE — `:app:testDebugUnitTest` + `:app:assembleDebug` exit 0 |
| 13 | P2 | `add-subscription-auto-update-workmanager-worker`<br>Add subscription auto-update WorkManager worker | task | outbound | ⏫ | DONE | DONE — :app:testGithubDebugUnitTest + :app:assembleDebug + staticAnalysis exit 0 |
| 14 | P2 | `add-wireguard-ini-subscription-parser`<br>Add WireGuard INI subscription parser | task | outbound | ⏫ | DONE | DONE — :core:data:testDebugUnitTest + staticAnalysis exit 0 |
| 15 | P2 | `fork-boringtun-and-add-amneziawg-handshake-obfuscation`<br>Fork boringtun and add AmneziaWG handshake obfuscation | task | outbound | ⏫ | DONE | DONE — cargo nextest --workspace exit 0 (2723), ripdpi-warp-core 38 tests |
| 16 | P2 | `generalize-websocket-transport-for-outbound-composition`<br>Generalize WebSocket transport for outbound composition | task | transport | ⏫ | DONE | DONE — cargo nextest --workspace exit 0, ripdpi-ws-tunnel 58 tests |
| 17 | P2 | `add-dns-ipv6-and-kill-switch-release-gates`<br>Add DNS IPv6 and kill-switch release gates | task | testing | ⏫ | DONE | DONE — release-gate scripts + 15 tests; staticAnalysis (just lint) exit 0 |
| 18 | P2 | `add-fleet-release-gating-and-cadence-policy`<br>Add fleet release gating and cadence policy | task | testing | ⏫ | DONE | DONE — release-gate scripts + 18 tests; staticAnalysis (just lint) exit 0 |
| 19 | P2 | `add-multi-delivery-subscription-mirror-support`<br>Add multi-delivery subscription mirror support | task | relay | ⏫ | DONE | DONE — :core:data:testDebugUnitTest exit 0 |
| 20 | P2 | `add-per-device-subscription-token-ux-and-shared-link-warnings`<br>Add per-device subscription token UX and shared-link warnings | task | vpn | ⏫ | DONE | DONE — :app:testGithubDebugUnitTest + :app:assembleDebug exit 0 |
| 21 | P2 | `add-priority-based-outbound-failover-state-machine`<br>Add priority-based outbound failover state machine | task | vpn | ⏫ | DONE | DONE — cargo nextest --workspace exit 0, ripdpi-runtime-strategy 107 tests |
| 22 | P2 | `add-amneziawg-russian-isp-cohort-preset-catalog`<br>Add AmneziaWG Russian ISP cohort preset catalog | task | data | ⏫ | DONE | DONE — :core:data:testDebugUnitTest exit 0 (data layer + asset catalog) |
| 23 | P2 | `add-bootstrap-one-time-subscription-token-import-flow`<br>Add bootstrap one-time subscription token import flow | task | data | ⏫ | DONE | DONE — :core:data + :app:testGithubDebugUnitTest + :app:assembleDebug exit 0 |
| 24 | P2 | `add-ripdpi-vpn-deploy-fleet-compatibility-golden-file-tests`<br>Add ripdpi-vpn-deploy fleet compatibility golden-file tests | task | testing | ⏫ | DONE | DONE — :core:data:testDebugUnitTest exit 0 (golden-file harness + fixtures) |
| 25 | P3 | `add-sing-box-route-rules-android-per-app-routing-import`<br>Add sing-box route.rules Android per-app routing import | task | routing | ⏫ | DONE | DONE — :core:data:testDebugUnitTest exit 0 (route.rules parser + per-app routing model/merge) |
| 26 | P3 | `add-amneziawg-profile-editor-screen-with-obfuscation-fields`<br>Add AmneziaWG profile editor screen with obfuscation fields | task | outbound | 🔼 | DONE | DONE — :app:testGithubDebugUnitTest + :app:assembleDebug exit 0 |
| 27 | P3 | `add-amneziawg-uri-codec-for-profile-share-and-import`<br>Add amneziawg URI codec for profile share and import | task | outbound | 🔼 | DONE | DONE — :core:data:testDebugUnitTest exit 0 (amneziawg:// codec) |
| 28 | P3 | `add-clipboard-import-menu-action-with-explicit-user-consent`<br>Add clipboard-import menu action with explicit user consent | task | ui | 🔼 | DONE | DONE — :app:testGithubDebugUnitTest + :app:assembleDebug exit 0 |
| 29 | P3 | `add-force-resolve-dns-and-subscription-userinfo-handling`<br>Add force-resolve DNS and Subscription-Userinfo handling | task | outbound | 🔼 | DONE | DONE — :core:data:testDebugUnitTest exit 0 |
| 30 | P3 | `add-httpupgrade-transport-crate`<br>Add HTTPUpgrade transport crate | task | transport | 🔼 | DONE | DONE — cargo nextest -p ripdpi-ws-tunnel exit 0; workspace 2842 passed; clippy -D warnings clean |
| 31 | P3 | `add-qr-generation-and-share-for-saved-profiles`<br>Add QR generation and share for saved profiles | task | ui | 🔼 | DONE | DONE — :app:testGithubDebugUnitTest + :app:assembleDebug exit 0 (zxing-core) |
| 32 | P3 | `add-selector-outbound-runtime-for-group-based-profile-switching`<br>Add selector outbound runtime for group-based profile switching | task | outbound | 🔼 | DONE | DONE — :core:data + :core:service:testDebugUnitTest exit 0 |
| 33 | P3 | `epic-subscription-profile-import`<br>Epic - Subscription and profile import | epic | outbound | 🔺 | DONE | DONE — all child tasks (entities, 4 parsers, dedup, auto-update worker, force-resolve, selector runtime, mirror, token UX) DONE & verified |
| 34 | P3 | `epic-qr-code-and-clipboard-profile-import`<br>Epic - QR code and clipboard profile import | epic | ui | ⏫ | DONE | DONE — all child tasks (QR scanner, share-sheet handler, clipboard import, QR generation/share) DONE & verified |
| 35 | P3 | `add-sing-mux-and-yamux-wire-multiplexing`<br>Add sing-mux and yamux wire multiplexing | task | transport | 🔼 | DONE | DONE — cargo nextest --workspace 2842 passed exit 0; clippy clean |
| 36 | P3 | `refactor-quic-and-h3-into-a-composable-transport-crate`<br>Refactor QUIC and H3 into a composable transport crate | task | transport | 🔼 | DONE | DONE — cargo nextest -p ripdpi-hysteria2 exit 0; workspace 2842 passed; clippy clean |
| 37 | P4 | `wire-amneziawg-into-the-subscription-wireguard-ini-parser`<br>Wire AmneziaWG into the subscription WireGuard-INI parser | task | outbound | 🔼 | DONE | DONE — :core:data:testDebugUnitTest exit 0 (AWG-flavored INI -> AmneziaWgSubscriptionProfile) |
| 38 | P4 | `add-randomized-port-hopping-window-to-hysteria2-outbound`<br>Add randomized port-hopping window to Hysteria2 outbound | task | transport | 🔼 | DONE | DONE — cargo nextest -p ripdpi-hysteria2 exit 0 (50 tests); clippy -D warnings clean |
| 39 | P4 | `epic-composable-transport-layer-parity`<br>Epic - Composable transport layer parity | epic | epic | ⏫ | DONE | DONE — all child transport tasks (gRPC, WS transport, HTTPUpgrade, sing-mux/yamux, QUIC/H3 refactor, hysteria port-hopping) DONE & verified |
| 40 | P4 | `add-captive-portal-and-whitelist-mode-test-cases`<br>Add captive portal and whitelist-mode test cases | task | testing | 🔼 | DONE | DONE — :core:service:testDebugUnitTest exit 0 (classifier + assist-window + 15 tests) |
| 41 | P4 | `add-client-compatibility-regression-matrix-for-fleet-profiles`<br>Add client compatibility regression matrix for fleet profiles | task | testing | 🔼 | DONE | DONE — :core:service:testDebugUnitTest + staticAnalysis exit 0 (fleet client-compat matrix + 15 tests) |
| 42 | P4 | `epic-vpn-fleet-testing-matrix-and-release-gates`<br>Epic - VPN fleet testing matrix and release gates | epic | testing | ⏫ | DONE | DONE — all child tasks (release gates, golden-file tests, captive-portal tests, client-compat matrix) DONE & verified |
| 43 | P4 | `add-strategy-pack-compatibility-hints-for-amneziawg-servers`<br>Add strategy-pack compatibility hints for AmneziaWG servers | task | outbound | 🔽 | DONE | DONE — :core:data:testDebugUnitTest exit 0 (fixedConfigProtocols + candidate-arm validation; Rust learner enforcement noted as native follow-up) |
| 44 | P4 | `epic-amneziawg-outbound-support`<br>Epic - AmneziaWG outbound support | epic | epic | 🔼 | DONE | DONE — all child tasks (boringtun AWG fork, kotlin config+.conf parser, profile editor, URI codec, WG-INI wiring, strategy-pack hints) DONE & verified |
| 45 | P4 | `epic-ripdpi-vpn-deploy-fleet-compatibility`<br>Epic - ripdpi-vpn-deploy fleet compatibility | epic | epic | 🔺 | DONE | DONE — all 44 child rows (7 new tasks + 19 feeders + transitive deps) DONE & verified across phases 1-4 |
