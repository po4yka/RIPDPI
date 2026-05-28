# local-network-fixture

**Responsibility:** local deterministic network fixtures for native, JVM, Android, and CI relay/protocol tests.

The crate provides fixture servers and manifests for echo traffic, DNS, HTTP/TLS, SOCKS, MASQUE, NaiveProxy readiness, WebTunnel, Trojan, AnyTLS, and Shadowsocks. It is test infrastructure, not production runtime code.

## Current Use

- Relay crates use it as an oracle for loopback protocol behavior and failure classification.
- Android packet-smoke and local-network tests consume the manifest fields so emulators/devices can reach host fixtures through the right address.
- Environment variables such as `RIPDPI_FIXTURE_*` override ports and host addresses for CI and local runs.

Keep fixture behavior deterministic and avoid embedding live endpoints or credentials.
