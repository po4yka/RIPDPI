# RIPDPI for Android

**English** | [Русский](README-ru.md)

Android application for bypassing DPI (Deep Packet Inspection) and censorship with:

- local proxy mode
- local VPN redirection mode
- encrypted DNS in VPN mode with DoH/DoT/DNSCrypt
- integrated diagnostics and passive telemetry
- in-repository Rust native modules

RIPDPI runs a local SOCKS5 proxy derived from [ByeDPI](https://github.com/hufrea/byedpi). In VPN mode it redirects Android traffic through that local proxy by using a local TUN-to-SOCKS bridge derived from [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel).

## Diagnostics

RIPDPI includes an integrated diagnostics screen for active DPI checks and passive runtime monitoring.

Implemented diagnostic mechanisms:

- Manual scans in `RAW_PATH` and `IN_PATH` modes
- DNS integrity checks across UDP DNS and encrypted resolvers (DoH/DoT/DNSCrypt)
- Domain reachability checks with TLS and HTTP classification
- TCP 16-20 KB cutoff detection with repeated fat-header requests
- Whitelist SNI retry detection for blocked TLS paths
- Resolver recommendations with temporary session overrides and save-to-settings actions
- Passive native telemetry while proxy or VPN service is running
- Export bundles with `summary.txt`, `report.json`, `telemetry.csv`, and `manifest.json`

What the app records:

- Android network snapshot: transport, capabilities, DNS, MTU, local addresses, public IP/ASN, captive portal, validation state
- Native proxy runtime telemetry: listener lifecycle, accepted clients, route selection and route advances, native errors
- Native tunnel runtime telemetry: tunnel lifecycle, packet and byte counters, resolver id/protocol/endpoint, DNS latency and failure counters, fallback reason, network handover class

What the app does not record:

- Full packet captures
- Traffic payloads
- TLS secrets

## Settings

To bypass some blocks, you may need to change the settings. More information is in the [ByeDPI documentation](https://github.com/hufrea/byedpi/blob/v0.13/README.md).

## FAQ

**Does the application require root?** No.

**Is this a VPN?** No. It uses Android's VPN mode to redirect traffic locally. It does not encrypt general app traffic or hide your IP address. When encrypted DNS is enabled, only DNS lookups are sent through DoH/DoT/DNSCrypt.

**How to use with AdGuard?**

1. Run RIPDPI in proxy mode.
2. Add RIPDPI to AdGuard exceptions.
3. In AdGuard settings, set proxy: SOCKS5, host `127.0.0.1`, port `1080`.

## Documentation

- [Native integration and module usage](docs/native/README.md)
- [Testing, E2E, golden contracts, and soak coverage](docs/testing.md)
- [External projects analysis and feature ideas](docs/external-projects-analysis.md)

## Building

Requirements:

- JDK 17
- Android SDK
- Android NDK `29.0.14206865`
- Rust toolchain `1.94.0`
- Android Rust targets for the ABIs you want to build

Basic local build:

```bash
git clone https://github.com/po4yka/RIPDPI.git
cd RIPDPI
./gradlew assembleDebug
```

Fast local native build for one ABI:

```bash
./gradlew assembleDebug -Pripdpi.localNativeAbis=arm64-v8a
```

APK outputs:

- debug: `app/build/outputs/apk/debug/`
- release: `app/build/outputs/apk/release/`

## Testing

The project now has layered coverage for Kotlin, Rust, JNI, services, diagnostics, local-network E2E, Linux TUN E2E, golden contracts, and native soak runs.

Common commands:

```bash
./gradlew testDebugUnitTest
bash scripts/ci/run-rust-native-checks.sh
bash scripts/ci/run-rust-network-e2e.sh
```

Details and targeted commands: [docs/testing.md](docs/testing.md)

## CI/CD

The project uses GitHub Actions for continuous integration and release automation.

**PR / push CI** (`.github/workflows/ci.yml`) currently runs:

- `build`: debug APK build, ELF verification, native size verification, JVM unit tests
- `static-analysis`: Rust formatting/clippy/tests, cargo-deny, Android static analysis
- `rust-network-e2e`: repo-owned local-network proxy E2E plus focused vendored parity smoke
- `android-network-e2e`: emulator-based instrumentation E2E against the local fixture stack

**Nightly / manual CI** adds:

- `rust-native-soak`: host-side native soak for proxy and diagnostics runtimes
- `linux-tun-e2e`: privileged Linux TUN E2E and TUN soak coverage

Golden diff artifacts, Android reports, fixture logs, and soak metrics are uploaded when produced by the workflow.

**Release** (`.github/workflows/release.yml`) runs on `v*` tag pushes or manual dispatch:

- Builds a signed release APK
- Creates a GitHub Release with the APK attached

### Required GitHub Secrets

To enable signed release builds, configure these repository secrets:

| Secret | Description |
|--------|-------------|
| `KEYSTORE_BASE64` | Base64-encoded release keystore (`base64 -i release.keystore`) |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Signing key alias |
| `KEY_PASSWORD` | Signing key password |

## Native Modules

- `native/rust/crates/ripdpi-android`: proxy JNI bridge and proxy runtime telemetry surface
- `native/rust/crates/hs5t-android`: TUN-to-SOCKS JNI bridge and tunnel telemetry surface
- `native/rust/crates/ripdpi-monitor`: active diagnostics scans and passive diagnostics events
- `native/rust/crates/ripdpi-dns-resolver`: shared encrypted DNS resolver used by diagnostics and VPN mode
- `native/rust/crates/ripdpi-runtime`: shared proxy runtime layer used by `libripdpi.so`
- `native/rust/crates/android-support`: Android logging and JNI support helpers

Native integration details: [docs/native/README.md](docs/native/README.md)
