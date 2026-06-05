# Task Board — RIPDPI

Plain-Markdown task board. The **source of truth is `issues/<slug>.md`** (one file per
task/epic, YAML frontmatter + spec). This file is a generated, read-only index — do not
hand-edit it; regenerate it from the issue frontmatter (see `README.md` § Regenerate).

_Statuses: `doing` · `review` · `blocked` · `todo` · `backlog` (`done`/`dropped` files are deleted)._

## Doing (14)

| Priority | Area | Task | Parent epic |
| --- | --- | --- | --- |
| critical | vpn | [Epic - Fail-closed Android VPN policy engine](issues/epic-fail-closed-android-vpn-policy-engine.md) | — |
| critical | relay | [Epic - Remove Cloudflare from critical path](issues/epic-remove-cloudflare-from-critical-path.md) | — |
| high | testing | [Add credential redaction tests for VLESS UUID, TUIC UUID, NaiveProxy auth](issues/add-credential-redaction-tests-for-vless-uuid-tuic-uuid-naive-auth.md) | — |
| high | rust-native | [Add uTLS per-connection TLS-fingerprint rotation for outbound TLS handshakes](issues/add-utls-per-connection-tls-fingerprint-rotation.md) | — |
| high | epic | [Epic - Extended outbound protocol support](issues/epic-extended-outbound-protocol-support.md) | — |
| high | testing | [Epic - Orchestration test posture](issues/epic-orchestration-test-posture.md) | — |
| medium | outbound | [Finish AnyTLS profile editor and compatibility gaps](issues/add-anytls-outbound-client-crate-and-profile-editor.md) | epic-extended-outbound-protocol-support |
| medium | outbound | [Add Mieru outbound client crate and profile editor](issues/add-mieru-outbound-client-crate-and-profile-editor.md) | epic-extended-outbound-protocol-support |
| medium | testing | [Add port-hopping window soak test for Hysteria 2](issues/add-port-hopping-window-soak-test-for-hysteria2.md) | — |
| medium | rust-native | [Add post-quantum hybrid KEM (X25519MLKEM768) for outbound TLS handshakes](issues/add-post-quantum-hybrid-kem-x25519mlkem768-for-tls-handshakes.md) | — |
| medium | rust-native | [Add TUIC v4 fallback or explicit version detection](issues/add-tuic-v4-fallback-or-version-detection.md) | — |
| medium | outbound | [Add Xray profile UX and import flow](issues/add-xray-profile-ux-and-import-flow.md) | epic-xray-provider-mode |
| medium | epic | [Epic - Localization expansion](issues/epic-localization-expansion.md) | — |
| medium | diagnostics | [Report OWNED_STACK_ONLY verdict from diagnostic](issues/report-owned-stack-only-verdict-from-diagnostic.md) | — |

## Review (2)

| Priority | Area | Task | Parent epic |
| --- | --- | --- | --- |
| high | testing | [Add fuzz target for xHTTP FinalMask Sudoku decoder](issues/add-fuzz-target-for-xhttp-finalmask-sudoku-decoder.md) | — |
| medium | ui | [Review landed zh-CN translation and initial human sign-off](issues/add-zh-cn-translation-and-initial-human-review.md) | epic-localization-expansion |

## Blocked (9)

_The six Xray-provider rows are blocked on the gomobile/libXray AAR toolchain + on-device
smoke lane; the remaining rows carry their own blocker (see each issue's Work log)._

| Priority | Area | Task | Parent epic |
| --- | --- | --- | --- |
| high | outbound | [Bridge TUN traffic through Xray local inbound](issues/bridge-tun-traffic-through-xray-local-inbound.md) | epic-xray-provider-mode |
| high | outbound | [Epic - Xray provider mode](issues/epic-xray-provider-mode.md) | — |
| high | outbound | [Package libXray for Android ABIs](issues/package-libxray-for-android-abis.md) | epic-xray-provider-mode |
| high | outbound | [Run Xray as managed VPN relay runtime](issues/run-xray-as-managed-vpn-relay-runtime.md) | epic-xray-provider-mode |
| medium | testing | [Add Hysteria 2 Salamander obfuscation conformance fixtures](issues/add-hysteria2-salamander-obfuscation-conformance-fixtures.md) | — |
| medium | diagnostics | [Add network-security-config with opportunistic domainEncryption](issues/add-network-security-config-with-opportunistic-domainencryption.md) | — |
| medium | outbound | [Add SSH outbound client crate and profile editor](issues/add-ssh-outbound-client-crate-and-profile-editor.md) | epic-extended-outbound-protocol-support |
| medium | outbound | [Add Xray provider regression matrix](issues/add-xray-provider-regression-matrix.md) | epic-xray-provider-mode |
| medium | outbound | [Surface Xray diagnostics and telemetry](issues/surface-xray-diagnostics-and-telemetry.md) | epic-xray-provider-mode |

## Todo (8)

| Priority | Area | Task | Parent epic |
| --- | --- | --- | --- |
| medium | testing | [Add Criterion throughput benchmarks for each transport](issues/add-protocol-throughput-benchmarks-for-each-transport.md) | — |
| medium | testing | [Add QUIC path-MTU discovery regression test](issues/add-quic-path-mtu-discovery-regression-test.md) | — |
| medium | testing | [Add VLESS mux conformance tests against xray-core](issues/add-vless-mux-conformance-tests-against-xray-core.md) | — |
| medium | rust-native | [Extract MasqueProviderAdapter trait to decouple Cloudflare-specific paths](issues/extract-masque-provider-adapter-trait-to-decouple-cloudflare.md) | — |
| medium | tooling | [Adopt clippy::pedantic / clippy::nursery per-crate for high-AI-authorship crates](issues/lints-pedantic-nursery-M7.md) | — |
| medium | service | [Wire NaiveProxy helper probe into manager startup](issues/make-naiveproxy-helper-probe-return-structured-version-json.md) | — |
| medium | transport | [Wire AmneziaWG RTK South cohort (Jc=4) into Android client](issues/wire-amneziawg-rtk-south-jc4-cohort-into-android-client.md) | — |
| low | testing | [Add ShadowTLS loopback test server for soak runs](issues/add-shadowtls-loopback-test-server-for-soak-runs.md) | — |

## Backlog (23)

| Priority | Area | Task | Parent epic |
| --- | --- | --- | --- |
| high | vpn | [Add tun2socks UID validation to close SO_BINDTODEVICE escape (kernel 5.7+)](issues/add-tun2socks-uid-validation-against-so-bindtodevice-bypass.md) | epic-fail-closed-android-vpn-policy-engine |
| high | rust-native | [Add WireGuard-over-WebSocket transport with AmneziaWG disguise](issues/add-wireguard-over-websocket-transport-amneziawg-disguise.md) | — |
| high | transport | [Enforce per-exit-IP concurrent-TLS-connection cap (~12, RU home-ISP policing)](issues/enforce-per-exit-ip-concurrent-tls-cap.md) | — |
| high | testing | [Operate Phase-16 real-provider SIM runner](issues/operate-phase16-real-provider-sim-runner.md) | — |
| medium | rust-native | [Add Cloudflare Workers domain-fronting bypass adapter](issues/add-cloudflare-workers-domain-fronting-bypass.md) | — |
| medium | rust-native | [Add constant-rate traffic shaping with VoIP camouflage profile](issues/add-constant-rate-traffic-shaping-voip-camouflage.md) | — |
| medium | rust-native | [Validate H3-to-H2 MASQUE fallback telemetry sufficiency](issues/add-h3-to-h2-fallback-telemetry-rollout-validation.md) | — |
| medium | routing | [Adopt Android 17 system split-tunnel UI via ACTION_VPN_APP_EXCLUSION_SETTINGS](issues/adopt-android-17-system-split-tunnel-ui-via-action-vpn-app-exclusion.md) | — |
| medium | routing | [Adopt process-based per-package routing via Xray TUN routeOnly](issues/adopt-process-based-per-package-routing-via-xray-tun-routeonly.md) | — |
| medium | rust-native | [Adopt tls_spoof pre-handshake ClientHello SNI desync for whitelist bypass](issues/adopt-tls-spoof-prehandshake-clienthello-sni-desync.md) | — |
| medium | testing | [Audit VLESS chained connect_over relay end-to-end test coverage](issues/audit-vless-chained-connect-over-relay-end-to-end-tests.md) | — |
| medium | ci | [CI: build ripdpi-diagnostics-probes with both compat-facade on and off](issues/ci-build-ripdpi-diagnostics-probes-with-both-compat-facade-on-and-off.md) | — |
| medium | transport | [Investigate RKN unannounced protocol-class signatures (Dec 2025 shift)](issues/investigate-rkn-unannounced-protocol-class-signatures.md) | — |
| medium | testing | [Spike CensorLab as offline censor-replay harness](issues/spike-censorlab-as-offline-censor-replay-harness.md) | epic-orchestration-test-posture |
| medium | transport | [Spike: DNS-Morph bootstrap as fallback bootstrap channel](issues/spike-dns-morph-bootstrap-fallback-channel.md) | — |
| medium | transport | [Wire Hysteria Realm STUN-discovered NAT traversal (sing-box v1.14.0-alpha.22)](issues/wire-hysteria-realm-stun-nat-traversal.md) | — |
| low | rust-native | [Add format-transforming encryption (Marionette-style) for protocol shape-shifting](issues/add-format-transforming-encryption-marionette-style-protocol-shapeshift.md) | — |
| low | testing | [Add cross-stack chain tests (VLESS over xHTTP over Reality)](issues/add-protocol-cross-stack-chain-tests-vless-over-xhttp-over-reality.md) | — |
| low | rust-native | [Add upstream HTTP and SOCKS5 proxy override for diagnostic probes](issues/add-upstream-http-and-socks5-proxy-override-for-diagnostic-probes.md) | — |
| low | diagnostics | [Evaluate sing-box 1.14 rule-action model for policy DSL parity](issues/evaluate-sing-box-1-14-rule-action-model-for-policy-dsl-parity.md) | — |
| low | vpn | [Spike FakeIP mode compatibility on Android](issues/spike-fakeip-mode-compatibility-on-android.md) | epic-fail-closed-android-vpn-policy-engine |
| low | service | [Spike - native core crash isolation tradeoffs](issues/spike-native-core-crash-isolation-tradeoffs.md) | — |
| low | diagnostics | [Spike relay-assisted QUICstep rescue mode after NO_DIRECT_SOLUTION](issues/spike-relay-assisted-quicstep-rescue-mode-after-no-direct-solution.md) | — |
