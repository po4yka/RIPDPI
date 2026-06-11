# Task Board — RIPDPI

Plain-Markdown task board. The **source of truth is `issues/<slug>.md`** (one file per
task/epic, YAML frontmatter + spec). This file is a generated, read-only index — do not
hand-edit it; regenerate it from the issue frontmatter (see `README.md` § Regenerate).

_Statuses: `doing` · `review` · `blocked` · `todo` · `backlog` (`done`/`dropped` files are deleted)._

## Doing (23)

| Priority | Area | Task | Parent epic |
| --- | --- | --- | --- |
| critical | relay | [Epic - Remove Cloudflare from critical path](issues/epic-remove-cloudflare-from-critical-path.md) | — |
| critical | vpn | [Epic - Fail-closed Android VPN policy engine](issues/epic-fail-closed-android-vpn-policy-engine.md) | — |
| high | epic | [Epic - Extended outbound protocol support](issues/epic-extended-outbound-protocol-support.md) | — |
| high | epic | [Epic - June 2026 full-project audit remediation](issues/epic-june-2026-audit-remediation.md) | — |
| high | outbound | [Bridge TUN traffic through Xray local inbound](issues/bridge-tun-traffic-through-xray-local-inbound.md) | epic-xray-provider-mode |
| high | testing | [Epic - Orchestration test posture](issues/epic-orchestration-test-posture.md) | — |
| high | testing | [Operate Phase-16 real-provider SIM runner](issues/operate-phase16-real-provider-sim-runner.md) | — |
| high | transport | [Enforce per-exit-IP concurrent-TLS-connection cap (~12, RU home-ISP policing)](issues/enforce-per-exit-ip-concurrent-tls-cap.md) | — |
| high | vpn | [Add tun2socks UID validation to close SO_BINDTODEVICE escape (kernel 5.7+)](issues/add-tun2socks-uid-validation-against-so-bindtodevice-bypass.md) | epic-fail-closed-android-vpn-policy-engine |
| medium | diagnostics | [Report OWNED_STACK_ONLY verdict from diagnostic](issues/report-owned-stack-only-verdict-from-diagnostic.md) | — |
| medium | epic | [Epic - Protocol conformance and regression tests](issues/epic-protocol-conformance-tests.md) | — |
| medium | outbound | [Add Mieru outbound client crate and profile editor](issues/add-mieru-outbound-client-crate-and-profile-editor.md) | epic-extended-outbound-protocol-support |
| medium | outbound | [Add SSH outbound client crate and profile editor](issues/add-ssh-outbound-client-crate-and-profile-editor.md) | epic-extended-outbound-protocol-support |
| medium | outbound | [Add Xray profile UX and import flow](issues/add-xray-profile-ux-and-import-flow.md) | epic-xray-provider-mode |
| medium | outbound | [Add Xray provider regression matrix](issues/add-xray-provider-regression-matrix.md) | epic-xray-provider-mode |
| medium | outbound | [Finish AnyTLS profile editor and compatibility gaps](issues/add-anytls-outbound-client-crate-and-profile-editor.md) | epic-extended-outbound-protocol-support |
| medium | routing | [Adopt Android 17 system split-tunnel UI via ACTION_VPN_APP_EXCLUSION_SETTINGS](issues/adopt-android-17-system-split-tunnel-ui-via-action-vpn-app-exclusion.md) | epic-fail-closed-android-vpn-policy-engine |
| medium | routing | [Adopt process-based per-package routing via Xray TUN routeOnly](issues/adopt-process-based-per-package-routing-via-xray-tun-routeonly.md) | epic-fail-closed-android-vpn-policy-engine |
| medium | rust-native | [Add TUIC v4 fallback or explicit version detection](issues/add-tuic-v4-fallback-or-version-detection.md) | — |
| medium | service | [Wire NaiveProxy helper probe into manager startup](issues/wire-naiveproxy-probe-into-manager-startup.md) | — |
| medium | testing | [Add QUIC path-MTU discovery regression test](issues/add-quic-path-mtu-discovery-regression-test.md) | epic-protocol-conformance-tests |
| medium | transport | [Wire AmneziaWG RTK South cohort (Jc=4) into Android client](issues/wire-amneziawg-rtk-south-jc4-cohort-into-android-client.md) | — |
| low | testing | [Add cross-stack chain tests (VLESS over xHTTP over Reality)](issues/add-protocol-cross-stack-chain-tests-vless-over-xhttp-over-reality.md) | epic-protocol-conformance-tests |

## Review (0)

_No tasks in review._

## Blocked (6)

_The six Xray-provider rows are blocked on the gomobile/libXray AAR toolchain + on-device
smoke lane; the remaining rows carry their own blocker (see each issue's Work log)._

| Priority | Area | Task | Parent epic |
| --- | --- | --- | --- |
| high | outbound | [Epic - Xray provider mode](issues/epic-xray-provider-mode.md) | — |
| high | outbound | [Package libXray for Android ABIs](issues/package-libxray-for-android-abis.md) | epic-xray-provider-mode |
| high | outbound | [Run Xray as managed VPN relay runtime](issues/run-xray-as-managed-vpn-relay-runtime.md) | epic-xray-provider-mode |
| medium | diagnostics | [Add network-security-config with opportunistic domainEncryption](issues/add-network-security-config-with-opportunistic-domainencryption.md) | — |
| medium | outbound | [Surface Xray diagnostics and telemetry](issues/surface-xray-diagnostics-and-telemetry.md) | epic-xray-provider-mode |
| medium | testing | [Add Hysteria 2 Salamander obfuscation conformance fixtures](issues/add-hysteria2-salamander-obfuscation-conformance-fixtures.md) | epic-protocol-conformance-tests |

## Todo (3)

| Priority | Area | Task | Parent epic |
| --- | --- | --- | --- |
| medium | android | [Harden JNI callbacks: daemon thread-attach, nullable array returns, drop runBlocking](issues/harden-jni-callback-thread-attach-and-null-sentinels.md) | epic-june-2026-audit-remediation |
| medium | rust-native | [Annotate and harden async cancel-safety in relay-core and tunnel-core](issues/annotate-and-harden-async-cancel-safety.md) | epic-june-2026-audit-remediation |
| medium | rust-native | [Centralize JavaVM::from_raw behind a SharedJvm newtype and fix root-helper signal cast](issues/centralize-unsafe-javavm-from-raw-and-signal-cast.md) | epic-june-2026-audit-remediation |

## Backlog (18)

| Priority | Area | Task | Parent epic |
| --- | --- | --- | --- |
| high | rust-native | [Add WireGuard-over-WebSocket transport with AmneziaWG disguise](issues/add-wireguard-over-websocket-transport-amneziawg-disguise.md) | — |
| medium | android | [Introduce a VPN-session Hilt scope to reset per-session service state](issues/introduce-vpn-session-hilt-scope.md) | epic-june-2026-audit-remediation |
| medium | epic | [Epic - Transport obfuscation and censor-signature research](issues/epic-transport-obfuscation-research.md) | — |
| medium | rust-native | [Add Cloudflare Workers domain-fronting bypass adapter](issues/add-cloudflare-workers-domain-fronting-bypass.md) | — |
| medium | rust-native | [Add constant-rate traffic shaping with VoIP camouflage profile](issues/add-constant-rate-traffic-shaping-voip-camouflage.md) | epic-transport-obfuscation-research |
| medium | rust-native | [Introduce a WsTransport port to fix L6/L4 -> L7 dependencies on ripdpi-ws-tunnel](issues/introduce-ws-transport-port-to-fix-layer-violations.md) | epic-june-2026-audit-remediation |
| medium | rust-native | [Split the 12-method PolicyPort trait into selection and learning sub-traits](issues/split-policyport-trait-selection-learning.md) | epic-june-2026-audit-remediation |
| medium | testing | [Spike CensorLab as offline censor-replay harness](issues/spike-censorlab-as-offline-censor-replay-harness.md) | epic-orchestration-test-posture |
| medium | transport | [Investigate RKN unannounced protocol-class signatures (Dec 2025 shift)](issues/investigate-rkn-unannounced-protocol-class-signatures.md) | epic-transport-obfuscation-research |
| medium | transport | [Spike: DNS-Morph bootstrap as fallback bootstrap channel](issues/spike-dns-morph-bootstrap-fallback-channel.md) | epic-transport-obfuscation-research |
| medium | transport | [Wire Hysteria Realm STUN-discovered NAT traversal (sing-box v1.14.0-alpha.22)](issues/wire-hysteria-realm-stun-nat-traversal.md) | — |
| medium | ui | [Decompose BlockcheckViewModel, DetectionCheckViewModel, BackupRestoreViewModel](issues/decompose-god-viewmodels-blockcheck-detection-backup.md) | epic-june-2026-audit-remediation |
| medium | ui | [Key session-scoped LaunchedEffect refreshes on the session id, not Unit](issues/fix-launchedeffect-unit-session-keyed-refresh.md) | epic-june-2026-audit-remediation |
| medium | vpn | [Verify no leak/black-hole window between TUN establish() and native relay readiness](issues/verify-no-leak-window-between-tun-establish-and-relay-ready.md) | — |
| low | relay | [Guard RelayBackend manual match arms against silently-omitted QUIC variants](issues/guard-relaybackend-quic-snapshot-exhaustiveness.md) | epic-june-2026-audit-remediation |
| low | rust-native | [Add upstream HTTP and SOCKS5 proxy override for diagnostic probes](issues/add-upstream-http-and-socks5-proxy-override-for-diagnostic-probes.md) | — |
| low | rust-native | [Reduce pub surface of monitor-engine/config and add golden contracts for high-fan-in crates](issues/reduce-pub-surface-monitor-engine-and-config.md) | epic-june-2026-audit-remediation |
| low | rust-native | [Triage undocumented orphan crates and document NATIVE_RUST.md prune candidates](issues/triage-undocumented-orphan-diagnostics-crates.md) | epic-june-2026-audit-remediation |
