# Local Network Testing System Coverage

This directory implements the repository-local harness described by
`ripdpi_local_network_testing_system_spec.md`.

## Implemented MVP

- Docker Compose lab with CoreDNS, httpbin, WireMock, Caddy HTTPS, TCP echo,
  UDP echo, Toxiproxy, optional mitmproxy, and QUIC/HTTP3 server.
- Emulator profile with `10.0.2.2` endpoints.
- Physical-device profile rendered from the MacBook LAN IP.
- Debug-only ADB probe action: `com.poyka.ripdpi.DEBUG_PROBE`.
- Machine-readable app-private JSON result file.
- DNS, HTTP, HTTPS, TCP echo, and UDP echo probes.
- VPN active-transport and local proxy readiness checks.
- Basic typed failure codes derived from the failing exception/stage.
- Log collection with denylist redaction.
- Packet capture start/stop helpers.
- Maestro flow skeletons for VPN connect, disconnect, diagnostics, reconnect,
  and lab profile setup.

## Explicit MVP Boundaries

- Android QUIC probing is marked as `QUIC_UNSUPPORTED_ANDROID_DEBUG_PROBE` in
  the JSON result. The host-side QUIC server is present for future HTTP/3 app
  client support and host validation.
- Relay directories document mock/reference test roles, but no relay container
  starts by default until a stable relay server contract is selected.
- Linux `netem` scripts require an external VM/router path and are not invoked
  by default on macOS.

## Production Guardrail

The probe receiver, permissive lab TLS trust, and lab endpoint defaults are
debug source-set code only. Release and benchmark variants do not merge
`app/src/debug/AndroidManifest.xml` or `app/src/debug/kotlin`.
