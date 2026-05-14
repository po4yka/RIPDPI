<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="120" alt="RIPDPI Logo"/>
</p>

<h1 align="center">RIPDPI</h1>
<p align="center"><b>Routing & Internet Performance Diagnostics Platform Interface</b></p>

<p align="center">
  <a href="https://github.com/po4yka/RIPDPI/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/po4yka/RIPDPI/ci.yml?style=flat-square&label=CI" alt="CI"/></a>
  <a href="https://github.com/po4yka/RIPDPI/releases/latest"><img src="https://img.shields.io/github/v/release/po4yka/RIPDPI?style=flat-square" alt="Release"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/po4yka?style=flat-square" alt="License"/></a>
  &nbsp;
  <img src="https://img.shields.io/badge/Android-8.1%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8.1+"/>
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Rust-000000?style=flat-square&logo=rust&logoColor=white" alt="Rust"/>
</p>

<p align="center"><b>English</b> | <a href="README-ru.md">Русский</a> | <a href="README-es.md">Español</a> | <a href="README-de.md">Deutsch</a> | <a href="README-fr.md">Français</a> | <a href="docs/fa/README.md">فارسی</a> | <a href="README-zh-CN.md">简体中文</a></p>

RIPDPI is an Android network-path diagnostics and optimization toolkit. It applies configurable packet strategies on-device, can connect to relay servers you control, and runs per-connection diagnostics to identify why each target is failing or degrading. The three capabilities work independently or in combination.

## Three pillars

### On-device packet strategies

Applies configurable packet-level transformations on-device without routing traffic to a relay server. No root is required for the core path.

Supported techniques: TCP segment splitting and disorder, fake packet injection, OOB (urgent pointer), TLS record fragmentation, fake TLS first-flight, QUIC handshake variation, DTLS fingerprint normalization, UDP length-field variation, IPv6 extension-header insertion, Lua-defined raw packet sends, and adaptive semantic markers that resolve position against live `TCP_INFO`. Strategy chains are built from Rust crates in this repository with no external strategy binary.

When no relay is configured, traffic exits the device directly — on-device mutations are the only change to the path.

### VPN relay

Chains local proxy or VPN traffic through encrypted relay protocols to a server you configure:

- **VLESS Reality and xHTTP** — native Rust implementation, no Go runtime
- **WARP, Cloudflare Tunnel, MASQUE**
- **Hysteria2, TUIC v5, ShadowTLS v3, NaiveProxy**
- **AmneziaWG** — WireGuard with handshake obfuscation for high-censorship networks
- **WebTunnel, obfs4, Snowflake, Google Apps Script path**

Both local proxy mode and Android VPN redirection mode work with or without a relay configured.

### Diagnostics

Scans each connection target independently and produces a typed verdict:

- `TRANSPARENT_WORKS` — raw path works, no intervention needed
- `OWNED_STACK_ONLY` — works only via the app's owned TLS stack
- `NO_DIRECT_SOLUTION` — on-device mutations cannot recover this target; relay required
- `IP_BLOCK_SUSPECT` — IP-level block detected

Verdicts are stored per network fingerprint and replayed automatically when the same network is seen again. The diagnostics screen adds TCP and QUIC strategy probing across 24 TCP + 6 QUIC candidates, DNS tampering detection, DoH/DoT/DNSCrypt/DoQ resolver recommendations, and exportable diagnostic archives.

## Why RIPDPI

Modern Android networks regularly apply L7 fingerprinting (TLS JA3/JA4, QUIC), aggressive QoS on cellular and public Wi-Fi, MTU and ECN desync, and middlebox-induced TLS handshake aborts — causing some targets to fail while others on the same network work fine. A single global setting cannot address all cases.

RIPDPI's design principle: classify each target and each network separately, apply the lightest fix that works, and remember it.

1. **Per-target, per-network answer** — not one global policy. Diagnostics classify each authority and store the verdict keyed to a network fingerprint hash.
2. **Mutate the local path when the network is the problem.** Semantic markers, adaptive split placement, fake-payload chains, OOB/disorder, randomized TLS records, QUIC and DTLS fingerprint variation — assembled from in-repo Rust crates.
3. **Fall back to a tunneled relay when the direct path is degraded.** Native-Rust VLESS Reality/xHTTP, plus WARP, MASQUE, Hysteria2, TUIC v5, ShadowTLS v3, NaiveProxy, AmneziaWG, and Cloudflare Tunnel handle targets that cannot be recovered on-device.
4. **Honest reporting.** Verdicts are typed and displayed; failure classifier results are surfaced rather than suppressed; diagnostic export bundles redact secrets.

## Screenshots

<p align="center">
  <img src="docs/screenshots/01-hero.png" width="200" alt="RIPDPI home screen"/>
  &nbsp;
  <img src="docs/screenshots/02-no-root.png" width="200" alt="RIPDPI without root"/>
  &nbsp;
  <img src="docs/screenshots/03-privacy.png" width="200" alt="RIPDPI privacy screen"/>
  &nbsp;
  <img src="docs/screenshots/04-controls.png" width="200" alt="RIPDPI controls"/>
</p>
<p align="center">
  <img src="docs/screenshots/05-diagnostics.png" width="200" alt="RIPDPI diagnostics"/>
  &nbsp;
  <img src="docs/screenshots/06-more-features.png" width="200" alt="RIPDPI feature overview"/>
</p>

## Features

- **Proxy mode**: local SOCKS5 proxy on the configured localhost port.
- **VPN mode**: routes Android device traffic through a local TUN-to-SOCKS bridge via `VpnService`.
- **Profile import**: QR-code scan and generation, plus clipboard and share-sheet import of proxy URIs (`vless://`, `hysteria2://`, `ss://`, `amneziawg://`, and more).
- **Subscriptions**: base64, Clash / Clash.Meta YAML, sing-box JSON, and WireGuard-INI subscription formats with background auto-update, duplicate-profile detection, selector/urltest groups, and multi-mirror delivery.
- **Encrypted DNS**: DoH, DoT, DNSCrypt, and DoQ resolver support in VPN-related paths.
- **Strategy controls**: TCP split/disorder/fake families, TLS record fragmentation and fake profiles, QUIC and DTLS handshake variation, UDP length-field variation, IPv6 extension headers, Lua `rawsend`, per-step activation filters, IPv4 ID control, and OOB injection.
- **Per-network policy memory**: validated per-authority verdicts keyed to a network fingerprint; automatically replayed on reconnect.
- **Adaptive probing**: automatic strategy probing for first-seen networks; background `quick_v1` recheck on network handover.
- **Handover-aware restart**: live policy re-evaluation on transitions between Wi-Fi, cellular, and roaming.
- **RIPDPI Browser**: app-owned browser for HTTPS targets that require the owned TLS stack; shared `SecureHttpClient` path for app-originated requests.
- **Runtime telemetry and logs**: proxy lifecycle, route decisions, DNS failover events, diagnostics progress, and native runtime events — available as in-app history and support export.
- **Optional root helper**: on rooted devices, unlocks raw-socket operations (FakeRst, MultiDisorder, IP fragmentation, full SeqOverlap, raw IPv4/IPv6 packet emission) via a privileged helper process.

## Runtime modes

### Proxy

SOCKS5 proxy on a configured localhost port. For apps that support proxy configuration. Strategy mutations and relay chaining apply to all traffic that enters through the proxy.

### VPN

Uses Android `VpnService` to redirect device traffic through RIPDPI's local engine. When no relay is configured, VPN mode applies on-device mutations without changing the egress IP. When a relay is configured, traffic is forwarded encrypted to the configured endpoint.

## Privacy

RIPDPI records operational metadata for diagnostics and troubleshooting: network snapshots, resolver status, route decisions, scan results, service state, and native runtime events.

RIPDPI does not record:
- Full packet captures
- Traffic payloads
- TLS secrets

Relay traffic privacy depends on the relay endpoint and profile you configure.

## Build

Requirements: JDK 17, Android SDK, Android NDK `29.0.14206865`, Rust toolchain `1.94.0`, Android Rust targets for the needed ABIs.

```bash
git clone https://github.com/po4yka/RIPDPI.git
cd RIPDPI
./gradlew assembleDebug
```

Local builds default to `arm64-v8a` (`ripdpi.localNativeAbisDefault`). For emulator: `./gradlew assembleDebug -Pripdpi.localNativeAbis=x86_64`.

APK output: `app/build/outputs/apk/debug/` and `app/build/outputs/apk/release/`.

## Testing

```bash
./gradlew testDebugUnitTest
bash scripts/ci/run-rust-native-checks.sh
bash scripts/ci/run-rust-network-e2e.sh
./test-lab/scripts/start-lab.sh --profile emulator
./test-lab/scripts/adb-install-debug.sh
./test-lab/scripts/adb-run-probe-emulator.sh --mode diagnostics
./test-lab/scripts/stop-lab.sh
python3 -m unittest scripts.tests.test_offline_analytics_pipeline
```

Details: [docs/testing.md](docs/testing.md)

## Documentation

- [Native integration and modules](docs/native/README.md)
- [Packet strategy runtime](docs/packet-strategy-runtime.md)
- [Proxy engine and strategy surface](docs/native/proxy-engine.md)
- [TUN-to-SOCKS bridge](docs/native/tunnel.md)
- [Strategy-pack and TLS catalog operations](docs/strategy-pack-operations.md)
- [Relay profile examples](docs/relay-profile-examples.md)
- [Local network test lab](test-lab/README.md)
- [External UI automation](docs/automation/README.md)
- [Architecture notes](docs/architecture/README.md)
- [Roadmap](ROADMAP.md)
