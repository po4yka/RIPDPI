# RIPDPI Local Network Test Lab

This lab provides a repeatable local "mock internet" for RIPDPI debug builds.
It is intended for Android Emulator runs through `10.0.2.2` and physical-device
runs through the MacBook LAN IP.

## Quick Start

```bash
./test-lab/scripts/start-lab.sh --profile emulator
./test-lab/scripts/adb-install-debug.sh
./test-lab/scripts/adb-run-probe-emulator.sh --mode diagnostics
./test-lab/scripts/stop-lab.sh
```

For a physical device:

```bash
./test-lab/scripts/start-lab.sh --profile device
./test-lab/scripts/adb-install-debug.sh
./test-lab/scripts/adb-run-probe-device.sh --mode diagnostics
./test-lab/scripts/stop-lab.sh
```

For the full VPN-mode smoke on a prepared emulator or attached device, use the
orchestrator. It restarts the lab, installs the debug APK unless skipped, uses
Maestro connect/disconnect flows when `maestro` is on `PATH`, runs the debug
probe in VPN mode, and archives failure artifacts:

```bash
./test-lab/scripts/run-vpn-e2e.sh --profile emulator
./test-lab/scripts/run-vpn-e2e.sh --profile device --keep-lab
```

The debug probe writes JSON to:

```text
/sdcard/Android/data/com.poyka.ripdpi/files/probe-result.json
```

Production builds do not include the probe receiver or the debug TLS trust
behavior because both live under `app/src/debug`.

`start-lab.sh` writes the resolved host IP, DNS port, and profile to
`test-lab/artifacts/lab-env.sh`; the ADB probe scripts source that file
automatically. The host DNS port defaults to `1053` because macOS often already
owns port `53`. Set `RIPDPI_DNS_PORT=53` before starting the lab only when that
port is free.

## Services

| Service | Port |
|---|---:|
| CoreDNS | 1053 TCP/UDP on host, 53 TCP/UDP in container |
| httpbin | 8080 |
| WireMock | 8082 |
| Caddy HTTP | 8081 |
| Caddy HTTPS | 8443 |
| TCP echo | 9000 |
| UDP echo | 9001 UDP |
| QUIC / HTTP/3 | 9443 TCP/UDP |
| Mock relay | 10080 |
| Toxiproxy | 8474 API, 18080, 18443 |
| mitmproxy | 8088, 8089 with `--profile inspect` |

## Fault Scenarios

Start the lab, then apply a Toxiproxy scenario by name:

```bash
./test-lab/scripts/apply-toxiproxy-scenario.sh latency
./test-lab/scripts/apply-toxiproxy-scenario.sh timeout
./test-lab/scripts/apply-toxiproxy-scenario.sh reset
```

The helper targets `http://127.0.0.1:8474` by default. Set
`TOXIPROXY_API_URL` or pass `--api-url` when the API is exposed elsewhere.
Each apply is idempotent for the named toxics in that scenario. Clear all active
toxics with:

```bash
./test-lab/scripts/clear-toxiproxy.sh
```

Packet loss and QUIC drop scenarios use Linux `tc`/netem and must run inside a
Linux VM or router namespace that carries the Android/device traffic:

```bash
NETEM_DEV=eth0 ./test-lab/chaos/netem/apply-loss.sh 10%
NETEM_DEV=eth0 ./test-lab/chaos/netem/apply-quic-drop.sh
NETEM_DEV=eth0 ./test-lab/chaos/netem/clear.sh
```

## Debug Probe

```bash
adb shell am broadcast \
  -a com.poyka.ripdpi.DEBUG_PROBE \
  -n com.poyka.ripdpi/.debug.DebugNetworkProbeReceiver \
  --es profile emulator \
  --es mode vpn \
  --ei dns_port 1053
```

For device profile, pass the MacBook host explicitly:

```bash
adb shell am broadcast \
  -a com.poyka.ripdpi.DEBUG_PROBE \
  -n com.poyka.ripdpi/.debug.DebugNetworkProbeReceiver \
  --es profile device \
  --es lab_host "$(ipconfig getifaddr en0)" \
  --ei dns_port 1053 \
  --es mode vpn
```

The probe currently validates DNS, HTTP, HTTPS, TCP echo, UDP echo, active VPN
transport, and local proxy readiness. QUIC is represented in the JSON result as
`QUIC_UNSUPPORTED_ANDROID_DEBUG_PROBE` until an Android-side HTTP/3 client is
added; the Docker QUIC server is available for host and future app probes.

The mock relay on port `10080` exposes a minimal JSON handshake for readiness,
auth-failure, and malformed-response tests. It is not a production relay
protocol implementation.

Use `--mode diagnostics` for a lab reachability smoke without requiring an
active RIPDPI service. Use `--mode vpn` after VPN mode is running; that mode
requires Android VPN transport plus local proxy readiness and returns `Fail`
when either precondition is absent.

## Artifacts

- Probe JSON: `test-lab/artifacts/probe-<profile>-<mode>.json`
- VPN E2E run directories: `test-lab/artifacts/vpn-e2e-*`
- Collected device logs: `test-lab/artifacts/logs-*`
- Packet captures: `test-lab/capture/*.pcap`

Bundle the current lab state for handoff or CI triage:

```bash
./test-lab/scripts/archive-artifacts.sh
```

The archive is written to `test-lab/artifacts/test-lab-artifacts-*.tar.gz` and
excludes generated TLS private keys.
