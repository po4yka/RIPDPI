# RIPDPI for Android

**English** | [Русский](README-ru.md)

Android application that runs a local VPN service to bypass DPI (Deep Packet Inspection) and censorship.

Runs a local SOCKS5 proxy derived from [ByeDPI](https://github.com/hufrea/byedpi) and redirects all traffic through it.

## Diagnostics

RIPDPI now includes an integrated diagnostics screen for active DPI checks and passive runtime monitoring.

Implemented diagnostic mechanisms:
- Manual scans in `RAW_PATH` and `IN_PATH` modes
- DNS integrity checks with UDP DNS and DoH comparison
- Domain reachability checks with TLS and HTTP classification
- TCP 16-20 KB cutoff detection with repeated fat-header requests
- Whitelist SNI retry detection for blocked TLS paths
- Passive native telemetry while proxy or VPN service is running
- Export bundles with `summary.txt`, `report.json`, `telemetry.csv`, and `manifest.json`

What the app records:
- Android network snapshot: transport, capabilities, DNS, MTU, local addresses, public IP/ASN, captive portal and validation state
- Native proxy runtime telemetry: listener lifecycle, accepted clients, route selection and route advances, native errors
- Native tunnel telemetry: tunnel lifecycle, packet and byte counters, native errors

What the app does not record:
- Full packet captures
- Traffic payloads
- TLS secrets

## Settings

To bypass some blocks, you may need to change the settings. More info in the [ByeDPI documentation](https://github.com/hufrea/byedpi/blob/v0.13/README.md).

## FAQ

**Does the application require root?** No.

**Is this a VPN?** No. It uses Android's VPN mode to redirect traffic locally. It does not encrypt traffic or hide your IP address.

**How to use with AdGuard?**
1. Run RIPDPI in proxy mode.
2. Add RIPDPI to AdGuard exceptions.
3. In AdGuard settings, set proxy: SOCKS5, host `127.0.0.1`, port `1080`.

## Building

Requirements: JDK 17+, Android SDK, Android NDK, Rust toolchain

```bash
git clone
./gradlew assembleRelease
```

APK output: `app/build/outputs/apk/release/`

## CI/CD

The project uses GitHub Actions for continuous integration and release automation.

**CI** (`.github/workflows/ci.yml`) runs on every push and PR to `main`:
- Builds the debug APK
- Runs unit tests

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

- In-repo Rust module based on [ByeDPI](https://github.com/hufrea/byedpi)
- In-repo Rust module based on [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
- In-repo Rust diagnostics crate linked into `libripdpi.so` for scan execution and report generation

Native integration details: [docs/native/README.md](docs/native/README.md)
