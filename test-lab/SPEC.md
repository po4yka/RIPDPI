# Local Network Testing System Coverage

This directory implements the repository-local network testing harness for
RIPDPI debug builds.

## Implemented MVP

- Docker Compose lab with CoreDNS, httpbin, WireMock, Caddy HTTPS, TCP echo,
  UDP echo, mock relay, Toxiproxy, optional mitmproxy, and QUIC/HTTP3 server.
- Emulator profile with `10.0.2.2` endpoints.
- Physical-device profile rendered from the MacBook LAN IP.
- Debug-only ADB probe action: `com.poyka.ripdpi.DEBUG_PROBE`.
- Machine-readable app-private JSON result file.
- DNS, HTTP, HTTPS, TCP echo, and UDP echo probes.
- DNS over UDP with DNS-over-TCP fallback for host network paths where Docker
  UDP replies are not reliable.
- VPN active-transport and local proxy readiness checks.
- VPN-mode E2E orchestration script for lab restart, debug APK install,
  debug automation state seeding, Maestro connect/disconnect, VPN probe
  execution, and failure artifact archiving. `--skip-maestro` is reserved for
  already-connected manual or external automation runs.
- Basic typed failure codes derived from the failing exception/stage.
- Log collection with denylist redaction.
- Packet capture start/stop helpers.
- Maestro flows for VPN connect, disconnect, diagnostics, reconnect, and lab
  profile setup. VPN flows use stable Home mode-card test-tag resource IDs and
  target the VPN card so the smoke validates Android VPN transport rather than
  local proxy readiness.
- Manual/nightly GitHub Actions lab doctor that validates test-lab scripts,
  checks the Docker Compose model, starts the emulator-profile lab, probes host
  endpoints, and uploads `test-lab/artifacts`.

## Explicit MVP Boundaries

- Android QUIC probing is marked as `QUIC_UNSUPPORTED_ANDROID_DEBUG_PROBE` in
  the JSON result. The host-side QUIC server is present for future HTTP/3 app
  client support and host validation.
- UDP echo is tested and reported independently. On host paths that drop Docker
  UDP replies, the probe returns a typed UDP timeout and a `Degraded` verdict
  while preserving successful DNS, HTTP, HTTPS, and TCP results.
- The mock relay implements only a minimal lab JSON handshake. The reference
  relay directory remains documentation-only until a stable relay server
  contract is selected.
- Linux `netem` scripts require an external VM/router path and are not invoked
  by default on macOS.
- Hosted CI does not run the full Android VPN-mode smoke by default because it
  requires an emulator/device, ADB install access, and Maestro. The workflow
  keeps that lane as a manual documented step while nightly runs exercise the
  Docker lab, shell contracts, and the missing-Maestro fast-fail guard.

## Production Guardrail

The probe receiver, permissive lab TLS trust, and lab endpoint defaults are
debug source-set code only. Release and benchmark variants do not merge
`app/src/debug/AndroidManifest.xml` or `app/src/debug/kotlin`.
