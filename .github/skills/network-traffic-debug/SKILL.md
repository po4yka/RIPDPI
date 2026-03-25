---
name: network-traffic-debug
description: Use when inspecting SOCKS5 proxy traffic, debugging VPN tunnel packets, capturing network traffic on device, or setting up mitmproxy for HTTPS interception
---

# Network Traffic Debug

Capturing and inspecting traffic from the RIPDPI SOCKS5 proxy and VPN tunnel on Android devices and emulators.

## Tool Comparison

| Tool | Root Required | Works Alongside VPN | Best For |
|------|:------------:|:-------------------:|---------|
| mitmproxy | No | N/A (proxy mode) | SOCKS5 HTTPS inspection with decryption |
| tcpdump | Yes (emulator OK) | Yes | Raw TUN interface packet capture |
| PCAPdroid | No | Root mode only | Quick app-level capture to PCAP |
| HTTP Toolkit | No | Uses own VPN | HTTP-level request/response inspection |

## mitmproxy for SOCKS5 Inspection

### Start mitmproxy in SOCKS5 Mode

```bash
# On host machine
mitmproxy --mode socks5 --listen-port 8050
```

### Configure Device to Use Proxy

On emulator, the host is reachable at `10.0.2.2`:

```bash
# Set global proxy (emulator)
adb shell settings put global http_proxy 10.0.2.2:8050
# Remove when done
adb shell settings put global http_proxy :0
```

On physical device, configure the Wi-Fi proxy settings to point to your machine's IP on port 8050.

### HTTPS Interception

1. Navigate to `http://mitm.it` on the device to download the CA certificate
2. Install the certificate in device settings (Security > Encryption > Install a certificate)
3. For Android 7+, user-installed CAs are not trusted by default. Add a debug-only network security config:

```xml
<!-- app/src/debug/res/xml/network_security_config.xml -->
<network-security-config>
    <debug-overrides>
        <trust-anchors>
            <certificates src="user" />
        </trust-anchors>
    </debug-overrides>
</network-security-config>
```

Reference in `AndroidManifest.xml`:

```xml
<application android:networkSecurityConfig="@xml/network_security_config">
```

This only affects debug builds -- release builds ignore `<debug-overrides>`.

## tcpdump on TUN Interface

Works on rooted devices and emulators (emulator images are rooted by default).

### Capture

```bash
# Capture all traffic on the VPN TUN interface
adb shell tcpdump -i tun0 -p -s 0 -w /sdcard/capture.pcap

# Capture with packet count limit
adb shell tcpdump -i tun0 -c 1000 -w /sdcard/capture.pcap
```

### Analyze

```bash
# Pull and open in Wireshark
adb pull /sdcard/capture.pcap
wireshark capture.pcap

# Quick summary on host
tcpdump -r capture.pcap -n | head -50

# Filter to specific port (e.g., proxy SOCKS5 port)
tcpdump -r capture.pcap -n 'port 1080'
```

### Cleanup

```bash
adb shell rm /sdcard/capture.pcap
```

## PCAPdroid

Install from F-Droid or Google Play. No root required for basic capture.

**VPN conflict:** PCAPdroid uses Android's `VPNService` API, which conflicts with the RIPDPI VPN tunnel. Two workarounds:

1. **Root mode:** On rooted devices, PCAPdroid can capture without VPNService, running alongside the app's VPN
2. **Proxy-only mode:** When debugging the SOCKS5 proxy (without VPN tunnel), PCAPdroid works without conflict

**Features:**
- Exports standard PCAP files for Wireshark
- Tags packets with app name and UID
- Wireshark plugin (`pcapdroid.lua`) adds app metadata to packet dissection

## TUN Interface Diagnostics

```bash
# Check if TUN interface is up
adb shell ip addr show tun0

# Show all routing tables (VPN creates its own)
adb shell ip route show table all | grep tun

# Interface traffic counters
adb shell cat /proc/net/dev | grep tun

# Verify DNS routing
adb shell nslookup example.com

# Check active network connections
adb shell ss -tlnp
```

## Debugging with Local Network Fixture

The repo includes a local network fixture for controlled testing. Start it and forward ports to the device:

```bash
# Start fixture on host
bash scripts/ci/start-local-network-fixture.sh

# Forward all fixture ports to device
adb reverse tcp:46090 tcp:46090   # control/health endpoint
adb reverse tcp:46001 tcp:46001   # TCP echo
adb reverse tcp:46003 tcp:46003   # TLS echo
adb reverse tcp:46053 tcp:46053   # DNS responder
adb reverse tcp:46054 tcp:46054   # DNS secondary

# Verify fixture is reachable from device
adb shell curl -fsS http://127.0.0.1:46090/health
```

### Fault Injection

The fixture control API supports injecting network faults:

```bash
# Check fixture manifest (available services and ports)
curl -fsS http://127.0.0.1:46090/manifest | jq .

# View recorded events
curl -fsS http://127.0.0.1:46090/events | jq .
```

See `android-device-debug` skill for full port forwarding reference.

## Log Correlation for Network Issues

### Log Level Strategy

| Level | Network Events |
|-------|---------------|
| ERROR | TUN fd errors, SOCKS5 handshake failures, DNS resolution crashes |
| WARN | Connection resets, DNS timeouts, retry attempts, upstream unreachable |
| INFO | Proxy start/stop, VPN connected/disconnected, route changes |
| DEBUG | Per-connection: SOCKS5 handshake steps, routing decisions, DNS queries |
| TRACE | Per-packet: raw bytes, TCP window sizes, packet timing |

### Runtime Log Level Control

Bump verbosity for a debugging session without rebuilding:

```rust
// From Kotlin via JNI, or from Rust directly:
android_support::set_android_log_scope_level("network-debug", LevelFilter::Trace);
// ... reproduce the issue ...
android_support::clear_android_log_scope_level("network-debug");
```

The `set_android_log_scope_level` function (in `native/rust/crates/android-support/src/lib.rs`) keeps the most verbose active scope as the global log level.

### Logcat Filtering for Network Issues

```bash
# Native proxy + tunnel logs only
adb logcat -s ripdpi-native:V ripdpi-tunnel-native:V

# Filter for connection events
adb logcat -s ripdpi-native:V | grep -i "connect\|handshake\|route\|dns"

# Filter for errors only
adb logcat -s ripdpi-native:E ripdpi-tunnel-native:E

# Capture clean log for analysis
adb logcat -c && sleep 1
# ... reproduce the issue ...
adb logcat -d -s ripdpi-native:V ripdpi-tunnel-native:V > network-debug.txt
```

### Correlation IDs

When investigating a specific connection, grep for its identifiers across both layers:

```bash
# Kotlin side logs with session/connection IDs
# Rust side uses tracing spans that include the same IDs
adb logcat | grep -i "session_id\|conn_id\|<specific-value>"
```

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| PCAPdroid conflicts with VPN | Use root mode, or test proxy-only (without tunnel) |
| mitmproxy can't decrypt HTTPS | Install CA cert + add `network_security_config.xml` with `<debug-overrides>` |
| tcpdump: `tun0: No such device` | VPN tunnel is not running; start it first via the app UI or automation extras |
| Fixture ports not reachable from device | Run `adb reverse tcp:<port> tcp:<port>` for each fixture port |
| Emulator can't reach host mitmproxy | Use `10.0.2.2` (emulator's alias for host loopback), not `127.0.0.1` |
| Log level change has no effect | `set_android_log_scope_level` sets the global max; check that logcat filter isn't hiding output |
| Physical device can't reach fixture | Set `RIPDPI_FIXTURE_ANDROID_HOST=127.0.0.1` and use `adb reverse` |

## Quick Reference

| Task | Command |
|------|---------|
| Start mitmproxy SOCKS5 | `mitmproxy --mode socks5 --listen-port 8050` |
| Capture TUN traffic | `adb shell tcpdump -i tun0 -p -s 0 -w /sdcard/capture.pcap` |
| Check TUN interface | `adb shell ip addr show tun0` |
| Check VPN routes | `adb shell ip route show table all \| grep tun` |
| Forward fixture ports | `adb reverse tcp:46090 tcp:46090` (repeat for 46001, 46003, 46053, 46054) |
| Native network logs | `adb logcat -s ripdpi-native:V ripdpi-tunnel-native:V` |
| Capture clean log | `adb logcat -c && adb logcat -d -s ripdpi-native:V > network-debug.txt` |

## See Also

- `android-device-debug` -- Device connection, logcat basics, crash debugging, fixture port forwarding
- `native-jni-development` -- JNI exports, lifecycle rules, build pipeline
- `native-profiling` -- CPU/memory profiling when investigating performance-related network issues
