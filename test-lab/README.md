# RIPDPI Local Network Test Lab

This lab provides a repeatable local "mock internet" for RIPDPI debug builds. It is intended for Android Emulator runs through `10.0.2.2` and physical-device runs through the MacBook LAN IP. On macOS, `start-lab.sh` runs DNS and UDP echo endpoints as host processes because Docker Desktop UDP port forwarding can receive datagrams without returning replies reliably.

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

For the full VPN-mode smoke on a prepared emulator or attached device, use the orchestrator. It restarts the lab, installs the debug APK unless skipped, uses Maestro connect/disconnect flows when `maestro` is on `PATH`, `MAESTRO_BIN` is set, or Maestro is installed at `~/.maestro/bin/maestro`, runs the debug probe in VPN mode, and archives failure artifacts. Before Maestro starts the real service, the script launches the debug app once with automation extras so the persisted test state is deterministic: onboarding complete, mode `vpn`, relay disabled, permissions granted, motion disabled, and `SERVICE_PRESET=live`.

```bash
./test-lab/scripts/run-vpn-e2e.sh --profile emulator
./test-lab/scripts/run-vpn-e2e.sh --profile device --keep-lab
```

`run-vpn-e2e.sh` requires Maestro unless `--skip-maestro` is passed. Use `--skip-maestro` only when VPN mode is already connected by a manual or external automation flow; otherwise the VPN probe cannot validate Android VPN transport. The bundled Maestro VPN flows target stable Compose test-tag resource IDs, not localized button labels.

For the matching proxy-mode smoke, use `run-proxy-e2e.sh`. It uses the same lab startup, install, automation seeding, Maestro, and debug-probe pattern, but it drives the local proxy card and checks that no RIPDPI foreground service remains after the disconnect flow:

```bash
./test-lab/scripts/run-proxy-e2e.sh --profile emulator
./test-lab/scripts/run-proxy-e2e.sh --profile device --keep-lab
```

`run-proxy-e2e.sh` also requires Maestro unless `--skip-maestro` is passed. Use `--skip-maestro` only after a manual or external flow has already connected proxy mode.

The debug probe writes JSON to:

```text
/sdcard/Android/data/com.poyka.ripdpi/files/probe-result.json
```

Production builds do not include the probe receiver or the debug TLS trust behavior because both live under `app/src/debug`.

Before a release or broad manual pass, record which remaining checklist rows the current host and attached device can cover:

```bash
./test-lab/scripts/check-feature-gap-readiness.sh
```

The readiness probe writes `test-lab/artifacts/feature-gap-readiness.json` and is read-only. It checks for an attached Android device, root availability, active TalkBack, visible Wi-Fi and cellular transports, routed netem host prerequisites, operator-provided relay matrix configuration, and whether local commits still need fresh remote workflow confirmation.

Before treating the feature-test checklist as complete, run the sign-off guard:

```bash
./test-lab/scripts/check-feature-test-signoff.sh
```

It is read-only and expected to fail while the completion audit is not marked complete or while required readiness rows are still `blocked`/`manual`. After every external checklist run is complete, keep the filled manual evidence template with the release artifacts and run the guard against an operator-reviewed readiness JSON whose required rows are all `ready`:

```bash
./test-lab/scripts/check-feature-test-signoff.sh \
  --audit /path/to/current-completion-audit.md \
  --readiness /path/to/operator-reviewed-feature-readiness.json
```

Create the reviewed JSON from the generated readiness artifact, then change a required row to `ready` only when the manual evidence template names the matching artifact, run ID, transcript, or lab archive. Keep `blocked` or `manual` for any row whose evidence is missing or still under review. The reviewed JSON must keep all canonical required rows: `android_device`, `rooted_physical_device`, `manual_talkback`, `physical_network_handover`, `routed_netem_vm`, `production_relay_matrix`, and `remote_workflow_confirmation`. Print the canonical list with:

```bash
./test-lab/scripts/check-feature-test-signoff.sh --list-required-readiness
```

The guard also validates the reviewed JSON shape before sign-off: required rows must not be duplicated, `required` must be boolean, messages must be strings, and statuses must be one of `ready`, `manual`, or `blocked`.

Provider-backed relay runs use an operator-owned matrix manifest. Keep live endpoints and secrets outside the repository, then validate the manifest before running the matrix:

```bash
cp test-lab/relay/provider-matrix.example.json /path/to/private-relay-matrix.json
test-lab/scripts/check-relay-matrix-config.sh --config /path/to/private-relay-matrix.json
RIPDPI_RELAY_MATRIX_CONFIG=/path/to/private-relay-matrix.json \
  ./test-lab/scripts/check-feature-gap-readiness.sh
```

## External Checklist Runs

Use `docs/feature-test-manual-evidence-template.md` to record these runs. Do not paste live endpoints, credentials, SSIDs, BSSIDs, account names, or private hostnames into repository docs or committed artifacts.

### Rooted Physical Device

Run this only on a rooted physical device prepared for lab testing:

```bash
adb shell su 0 id
adb shell pm path com.poyka.ripdpi
adb shell pidof ripdpi-root-helper || true
adb logcat -c
./test-lab/scripts/adb-install-debug.sh
```

Then enable `root_mode_enabled` through the app UI or an approved automation fixture, start the relevant service mode, and collect:

```bash
adb logcat -d > test-lab/artifacts/root-helper-logcat.txt
adb shell pidof ripdpi-root-helper || true
adb shell ls -la /data/data/com.poyka.ripdpi/files 2>/dev/null || true
```

The evidence row must include root detection, helper extraction/startup, readiness polling, one privileged operation result, negative readiness timeout behavior, helper stop cleanup, and a log redaction check.

### Physical Network Matrix

The readiness preflight only confirms that Wi-Fi and cellular transports are visible. A human or external harness still has to perform the actual network changes.

Capture state before and after each network transition:

```bash
adb shell dumpsys connectivity > test-lab/artifacts/connectivity-before.txt
./test-lab/scripts/adb-run-probe.sh \
  --profile device \
  --mode vpn \
  --timeout-ms 7000 \
  --out-dir test-lab/artifacts/network-vpn-before

# Operator switches Wi-Fi/cellular, IPv4-only, IPv6-only, captive, or
# limited-path condition here.

adb shell dumpsys connectivity > test-lab/artifacts/connectivity-after.txt
./test-lab/scripts/adb-run-probe.sh \
  --profile device \
  --mode vpn \
  --timeout-ms 7000 \
  --out-dir test-lab/artifacts/network-vpn-after
./test-lab/scripts/adb-run-probe.sh \
  --profile device \
  --mode diagnostics \
  --timeout-ms 7000 \
  --out-dir test-lab/artifacts/network-diagnostics-after
```

Repeat the probe for cellular baseline, Wi-Fi-to-cellular handover, cellular-to-Wi-Fi handover, IPv4-only, IPv6-only, captive, limited-path, and private-DNS-enabled rows. When the MacBook lab host is not reachable from the active network, use `adb-run-probe.sh --profile custom` with redacted public or routed-lab endpoint labels.

### Provider Relay Matrix

Validate the private manifest before every provider-backed batch:

```bash
test-lab/scripts/check-relay-matrix-config.sh \
  --config /path/to/private-relay-matrix.json
RIPDPI_RELAY_MATRIX_CONFIG=/path/to/private-relay-matrix.json \
  ./test-lab/scripts/check-feature-gap-readiness.sh
```

For each relay ID in the private manifest, record proxy, VPN, diagnostics, restart, invalid-credential, reset, timeout, malformed-response, DNS fallback, and handover outcomes in the manual evidence template. Store provider secrets and endpoint material outside the repository; committed docs should reference only redacted relay IDs such as `relay-masque-primary`. The manifest validator rejects duplicate relay IDs, unknown scenario names, literal URL/userinfo refs, and sensitive-looking literal values before any provider-backed run starts.

The private matrix must also retain the two paired initial-transport scenarios from the example manifest: `tcp_application_blackhole_udp_healthy` completes the Reality handshake and then blackholes application data while Hysteria2 remains healthy, and `udp_drop_reality_healthy` drops the Hysteria2 path while Reality remains healthy. Record the selected transport class and bounded race latency; do not copy provider endpoints, probe URLs, credentials, or payloads into artifacts.

### TalkBack Manual Pass

Do not enable or disable accessibility services from repository scripts. The operator should enable TalkBack manually, then capture a settings dump and a screen recording or transcript:

```bash
adb shell settings get secure accessibility_enabled
adb shell settings get secure enabled_accessibility_services
adb shell screenrecord /sdcard/ripdpi-talkback-pass.mp4
# Stop recording from another terminal with Ctrl-C, then pull it:
adb pull /sdcard/ripdpi-talkback-pass.mp4 test-lab/artifacts/
```

Cover buttons, switches, tabs, progress messages, error messages, and reachability of important controls. Summarize timestamps or transcript excerpts in `docs/feature-test-manual-evidence-template.md`.

### Remote Workflow Confirmation

When the release owner approves remote verification, follow the repository ruleset: push the local commits to a review branch, let the pull request checks run, and merge to `main` only after required reviews/checks pass. For final sign-off on the merged commit, trigger the hosted validation lanes that are not covered by the push event:

```bash
gh workflow run ci.yml --ref main
gh workflow run local-network-lab.yml --ref main -f run_vpn_emulator_lane=false
gh workflow run offline-analytics.yml --ref main -f private_corpus_path=''
gh workflow run mutation-testing.yml --ref main -f packages='' -f in_diff=false
gh workflow run fuzz-nightly.yml --ref main -f fuzz_seconds=1800
```

`CodeQL` does not expose manual dispatch; record the push-triggered run for the same commit. Capture run IDs, commit SHA, workflow conclusion, and artifact links in `docs/feature-test-manual-evidence-template.md` under `Remote Workflows`.

`start-lab.sh` writes the resolved host IP, DNS port, and profile to `test-lab/artifacts/lab-env.sh`; the ADB probe scripts source that file automatically. The host DNS port defaults to `1053` because macOS often already owns port `53`. Set `RIPDPI_DNS_PORT=53` before starting the lab only when that port is free.

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

The helper targets `http://127.0.0.1:8474` by default. Set `TOXIPROXY_API_URL` or pass `--api-url` when the API is exposed elsewhere. Each apply is idempotent for the named toxics in that scenario. Clear all active toxics with:

```bash
./test-lab/scripts/clear-toxiproxy.sh
```

Packet loss and QUIC drop scenarios use Linux `tc`/netem and must run inside a Linux VM or router namespace that carries the Android/device traffic:

```bash
export NETEM_DEV=eth0 NETEM_RUN_ID=manual-$(date +%s)
export NETEM_STATE_DIR=/var/tmp/ripdpi-netem-$NETEM_RUN_ID
./test-lab/chaos/netem/apply-loss.sh 10%
./test-lab/chaos/netem/apply-quic-drop.sh
./test-lab/chaos/netem/clear.sh
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

The wrapper exposes the same endpoint overrides, which is useful when the device is on cellular or behind a routed VM lab instead of the MacBook LAN:

```bash
./test-lab/scripts/adb-run-probe.sh \
  --profile custom \
  --mode diagnostics \
  --host lab.example.test \
  --dns-server lab.example.test \
  --dns-port 1053 \
  --dns-hostname ok.test \
  --http-url http://lab.example.test:8080/get \
  --https-url https://lab.example.test:8443/ \
  --tcp-host lab.example.test \
  --tcp-port 9000 \
  --udp-host lab.example.test \
  --udp-port 9001 \
  --relay-endpoint lab.example.test:10080
```

Add `--print-broadcast` to inspect the resolved ADB command without touching a device or requiring a live lab.

The probe currently validates DNS, HTTP, HTTPS, TCP echo, UDP echo, active VPN transport, and local proxy readiness. QUIC is represented in the JSON result as `QUIC_UNSUPPORTED_ANDROID_DEBUG_PROBE` until an Android-side HTTP/3 client is added; the Docker QUIC server is available for host and future app probes.

The mock relay on port `10080` exposes a minimal JSON handshake for readiness, auth-failure, and malformed-response tests. It is not a production relay protocol implementation.

Run the controlled mock-relay fault matrix with:

```bash
./test-lab/scripts/run-mock-relay-matrix.sh --profile emulator
```

The matrix writes `summary.tsv` plus one probe JSON per scenario and covers ready, invalid credentials, malformed response, server reset, and timeout. The failure scenarios are expected to exit non-zero and prove the debug diagnostics contract reports relay failures without breaking DNS, HTTP, HTTPS, TCP, or UDP probes.

Use `--mode diagnostics` for a lab reachability smoke without requiring an active RIPDPI service. Use `--mode proxy` after proxy mode is running; that mode requires local proxy readiness on the configured loopback listener. Use `--mode vpn` after VPN mode is running; that mode requires Android VPN transport and end-to-end lab traffic success. VPN mode does not require the fixed `127.0.0.1:1080` listener because the service may use an ephemeral authenticated internal SOCKS hop between the tunnel and proxy.

## Artifacts

- Probe JSON: `test-lab/artifacts/probe-<profile>-<mode>.json`
- VPN E2E run directories: `test-lab/artifacts/vpn-e2e-*`
- Proxy E2E run directories: `test-lab/artifacts/proxy-e2e-*`
- Collected device logs: `test-lab/artifacts/logs-*`
- Packet captures: `test-lab/capture/*.pcap`

Bundle the current lab state for handoff or CI triage:

```bash
./test-lab/scripts/archive-artifacts.sh
```

The archive is written to `test-lab/artifacts/test-lab-artifacts-*.tar.gz` and excludes generated TLS private keys.
