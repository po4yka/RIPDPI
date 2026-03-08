# RIPDPI for Android

**English** | [Русский](README-ru.md)

Android application that runs a local VPN service to bypass DPI (Deep Packet Inspection) and censorship.

Runs a SOCKS5 proxy [ByeDPI](https://github.com/hufrea/byedpi) locally and redirects all traffic through it.

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

Requirements: JDK 8+, Android SDK, Android NDK, CMake 3.22.1+

```bash
git clone --recurse-submodules
./gradlew assembleRelease
```

APK output: `app/build/outputs/apk/release/`

## Dependencies

- [ByeDPI](https://github.com/hufrea/byedpi)
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
