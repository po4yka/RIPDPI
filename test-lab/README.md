# RIPDPI Local Network Test Lab

This lab provides a repeatable local "mock internet" for RIPDPI debug builds.
It is intended for Android Emulator runs through `10.0.2.2` and physical-device
runs through the MacBook LAN IP.

## Quick Start

```bash
./test-lab/scripts/start-lab.sh --profile emulator
./test-lab/scripts/adb-install-debug.sh
./test-lab/scripts/adb-run-probe-emulator.sh --mode vpn
./test-lab/scripts/stop-lab.sh
```

For a physical device:

```bash
./test-lab/scripts/start-lab.sh --profile device
./test-lab/scripts/adb-run-probe-device.sh --mode vpn
```

The debug probe writes JSON to:

```text
/sdcard/Android/data/com.poyka.ripdpi/files/probe-result.json
```

Production builds do not include the probe receiver or the debug TLS trust
behavior because both live under `app/src/debug`.

## Services

| Service | Port |
|---|---:|
| CoreDNS | 53 TCP/UDP |
| httpbin | 8080 |
| WireMock | 8082 |
| Caddy HTTP | 8081 |
| Caddy HTTPS | 8443 |
| TCP echo | 9000 |
| UDP echo | 9001 UDP |
| QUIC / HTTP/3 | 9443 TCP/UDP |
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

## Debug Probe

```bash
adb shell am broadcast \
  -a com.poyka.ripdpi.DEBUG_PROBE \
  --es profile emulator \
  --es mode vpn \
  --es output /sdcard/Android/data/com.poyka.ripdpi/files/probe-result.json
```

For device profile, pass the MacBook host explicitly:

```bash
adb shell am broadcast \
  -a com.poyka.ripdpi.DEBUG_PROBE \
  --es profile device \
  --es lab_host "$(ipconfig getifaddr en0)" \
  --es mode vpn
```

The probe currently validates DNS, HTTP, HTTPS, TCP echo, UDP echo, active VPN
transport, and local proxy readiness. QUIC is represented in the JSON result as
`QUIC_UNSUPPORTED_ANDROID_DEBUG_PROBE` until an Android-side HTTP/3 client is
added; the Docker QUIC server is available for host and future app probes.

## Artifacts

- Probe JSON: `test-lab/artifacts/probe-<profile>-<mode>.json`
- Collected device logs: `test-lab/artifacts/logs-*`
- Packet captures: `test-lab/capture/*.pcap`

Bundle the current lab state for handoff or CI triage:

```bash
./test-lab/scripts/archive-artifacts.sh
```

The archive is written to `test-lab/artifacts/test-lab-artifacts-*.tar.gz` and
excludes generated TLS private keys.
