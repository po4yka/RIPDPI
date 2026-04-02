---
name: android-device-debug
description: Use when debugging the app on a device or emulator, capturing logs, running instrumented tests, managing emulators, or investigating crashes and network issues
---

# Android Device Debug

ADB-based debugging for the RIPDPI app on physical devices and emulators. No third-party tools -- uses only `adb`, Gradle, and existing repo scripts.

Full test stack documentation: `docs/testing.md`. Do not duplicate it here -- read it when you need runner details, CI lanes, or fixture locations.

## Device Discovery & Connection

```bash
adb devices -l                        # list connected devices/emulators
adb wait-for-device                   # block until a device is available
```

Multi-device disambiguation:

```bash
export ANDROID_SERIAL=<serial>        # all subsequent adb commands target this device
adb -s <serial> <command>             # one-off targeting
```

## Build, Install & Launch

### Build

```bash
# Physical device (ARM)
./gradlew assembleDebug -Pripdpi.localNativeAbis=arm64-v8a

# Emulator (x86_64 -- fast path)
./gradlew assembleDebug -Pripdpi.localNativeAbis=x86_64
```

### Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Launch with Automation Extras

Source of truth: `app/src/main/kotlin/com/poyka/ripdpi/automation/AutomationLaunchContract.kt`

```bash
adb shell am start -n com.poyka.ripdpi/.activities.MainActivity \
  --ez com.poyka.ripdpi.automation.ENABLED true \
  --ez com.poyka.ripdpi.automation.RESET_STATE true \
  --ez com.poyka.ripdpi.automation.DISABLE_MOTION true \
  --es com.poyka.ripdpi.automation.START_ROUTE home \
  --es com.poyka.ripdpi.automation.PERMISSION_PRESET granted \
  --es com.poyka.ripdpi.automation.SERVICE_PRESET idle \
  --es com.poyka.ripdpi.automation.DATA_PRESET clean_home
```

Deep link equivalent:

```bash
adb shell am start -a android.intent.action.VIEW \
  -d "ripdpi-debug://automation/launch?enabled=true&reset_state=true&start_route=home"
```

### Automation Preset Values

| Extra | Values |
|-------|--------|
| `PERMISSION_PRESET` | `granted`, `notifications_missing`, `vpn_missing`, `battery_review` |
| `SERVICE_PRESET` | `idle`, `connected_proxy`, `connected_vpn`, `live` |
| `DATA_PRESET` | `clean_home`, `settings_ready`, `diagnostics_demo` |
| `START_ROUTE` | `onboarding`, `home`, `config`, `diagnostics`, `history`, `logs`, `settings`, `mode_editor`, `dns_settings`, `advanced_settings`, `biometric_prompt`, `app_customization`, `about`, `data_transparency` |

Route source of truth: `app/src/main/kotlin/com/poyka/ripdpi/ui/navigation/Route.kt`

## Logcat Filtering

Native logcat tags (from Rust source):

| Tag | Source |
|-----|--------|
| `ripdpi-native` | Proxy engine + diagnostics (`ripdpi-android` crate) |
| `ripdpi-tunnel-native` | VPN tunnel (`ripdpi-tunnel-android` crate) |

```bash
# Native layer only
adb logcat -s ripdpi-native:V ripdpi-tunnel-native:V

# App process only (all tags)
adb logcat --pid=$(adb shell pidof com.poyka.ripdpi)

# Broad filter
adb logcat | grep -i ripdpi

# Crash buffer
adb logcat -b crash -d

# Clear buffer, then reproduce and capture
adb logcat -c
# ... reproduce the issue ...
adb logcat -d > android-logcat.txt
```

## Port Forwarding for Local Network Fixture

The fixture exposes TCP/UDP/TLS echo, DNS responders, DoH, SOCKS5 relay, and fault injection.

```bash
# Start fixture on host
bash scripts/ci/start-local-network-fixture.sh

# Forward ports to device
adb reverse tcp:46090 tcp:46090   # control/health
adb reverse tcp:46001 tcp:46001   # TCP echo
adb reverse tcp:46003 tcp:46003   # TLS echo
adb reverse tcp:46053 tcp:46053   # DNS
adb reverse tcp:46054 tcp:46054   # DNS secondary

# Verify
curl -fsS http://127.0.0.1:46090/health
curl -fsS http://127.0.0.1:46090/manifest | jq .
adb reverse --list
```

Stop: `bash scripts/ci/stop-local-network-fixture.sh`

For physical devices set `RIPDPI_FIXTURE_ANDROID_HOST=127.0.0.1` before starting the fixture.

## Instrumented Tests on Device

```bash
# Full suite
./gradlew :app:connectedDebugAndroidTest -Pripdpi.localNativeAbis=arm64-v8a

# Integration tests only
./gradlew :app:connectedDebugAndroidTest -Pripdpi.localNativeAbis=arm64-v8a \
  -Pandroid.testInstrumentationRunnerArguments.package=com.poyka.ripdpi.integration

# E2E tests only
./gradlew :app:connectedDebugAndroidTest -Pripdpi.localNativeAbis=arm64-v8a \
  -Pandroid.testInstrumentationRunnerArguments.package=com.poyka.ripdpi.e2e

# Single test class
./gradlew :app:connectedDebugAndroidTest -Pripdpi.localNativeAbis=arm64-v8a \
  -Pandroid.testInstrumentationRunnerArguments.class=com.poyka.ripdpi.e2e.NativeTelemetryGoldenSmokeTest

# Maestro smoke flows
bash scripts/ci/run-maestro-smoke.sh

# Coverage report
./gradlew :app:createDebugAndroidTestCoverageReport -Pripdpi.localNativeAbis=x86_64
```

Use `-Pripdpi.localNativeAbis=x86_64` for emulator, `arm64-v8a` for physical device.

## Screenshot & Screen Recording

```bash
# Screenshot
adb exec-out screencap -p > screenshot.png

# Screen recording (Ctrl+C to stop, max 3 min)
adb shell screenrecord /sdcard/recording.mp4
adb pull /sdcard/recording.mp4 .
adb shell rm /sdcard/recording.mp4
```

## App State Inspection

```bash
# Package info (permissions, components, version)
adb shell dumpsys package com.poyka.ripdpi

# Running services
adb shell dumpsys activity services com.poyka.ripdpi

# VPN and network state
adb shell dumpsys connectivity

# DataStore / shared prefs
adb shell run-as com.poyka.ripdpi ls shared_prefs/

# App ops (battery optimization, etc.)
adb shell cmd appops get com.poyka.ripdpi

# Force stop
adb shell am force-stop com.poyka.ripdpi

# Clear all app data
adb shell pm clear com.poyka.ripdpi
```

## Network Debugging

```bash
# Check VPN tunnel interface
adb shell ip addr show tun0

# Routing table (all tables -- VPN creates its own)
adb shell ip route show table all

# Listening sockets (proxy port)
adb shell ss -tlnp

# Active reverse forwards
adb reverse --list

# Remove all reverse forwards
adb reverse --remove-all

# DNS resolution
adb shell nslookup example.com
```

## Crash & ANR Debugging

### Java/Kotlin Crash

```bash
adb logcat -b crash -d
adb shell dumpsys dropbox --print | grep -A 20 data_app_crash
```

### Native Crash (Rust)

```bash
# Tombstone summary
adb logcat -s DEBUG:E

# Full tombstone files (may need root)
adb shell ls /data/tombstones/

# Complete bug report
adb bugreport bugreport.zip
```

Symbolicate with NDK addr2line:

```bash
$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/*/bin/llvm-addr2line \
  -e native/rust/target/<triple>/debug/libripdpi.so <address>
```

Debug `.so` files with symbols are under `native/rust/target/<triple>/debug/`. The stripped copies in `core/engine/build/generated/jniLibs/` lack symbols.

### ANR

```bash
# Main thread state
adb shell dumpsys activity processes com.poyka.ripdpi

# ANR traces (may need root)
adb pull /data/anr/traces.txt
```

Common RIPDPI ANR cause: JNI call blocking the main thread. The proxy `jniStart` is intentionally blocking -- see `native-jni-development` skill.

## Emulator Management

CI uses API 34, x86_64, google_apis, pixel_6 profile. Match for CI parity.

```bash
# List existing AVDs
emulator -list-avds

# Download system image (if needed)
sdkmanager "system-images;android-34;google_apis;x86_64"

# Create CI-parity AVD
avdmanager create avd -n ripdpi-debug \
  -k "system-images;android-34;google_apis;x86_64" -d pixel_6

# Start with GUI
emulator -avd ripdpi-debug -gpu host

# Start headless (CI style)
emulator -avd ripdpi-debug -no-window -noaudio -no-boot-anim -gpu swiftshader_indirect

# Cold boot (no snapshot)
emulator -avd ripdpi-debug -no-snapshot-load

# Delete AVD
avdmanager delete avd -n ripdpi-debug
```

## Quick Reference

| Task | Command |
|------|---------|
| List devices | `adb devices -l` |
| Install debug APK | `adb install -r app/build/outputs/apk/debug/app-debug.apk` |
| Launch app (clean) | `adb shell am start -n com.poyka.ripdpi/.activities.MainActivity --ez com.poyka.ripdpi.automation.ENABLED true --ez com.poyka.ripdpi.automation.RESET_STATE true` |
| Native logs | `adb logcat -s ripdpi-native:V ripdpi-tunnel-native:V` |
| Screenshot | `adb exec-out screencap -p > screenshot.png` |
| Forward fixture ports | `adb reverse tcp:46090 tcp:46090` (repeat for 46001, 46003, 46053, 46054) |
| Run integration tests | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.poyka.ripdpi.integration` |
| Force stop | `adb shell am force-stop com.poyka.ripdpi` |
| VPN interface check | `adb shell ip addr show tun0` |
| Crash logs | `adb logcat -b crash -d` |

## See Also

- `native-profiling` -- CPU flamegraphs with simpleperf, HWASan memory debugging, offline symbolication
- `network-traffic-debug` -- mitmproxy SOCKS5 inspection, tcpdump on TUN, PCAPdroid, log correlation
- `native-jni-development` -- Build pipeline, JNI exports, lifecycle rules
