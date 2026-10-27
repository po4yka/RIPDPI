# ripdpi-webtunnel

`ripdpi-webtunnel` is the in-repository Rust client implementation of the WebTunnel pluggable transport used by RIPDPI. The library owns WebTunnel client behavior; the binary is a thin Tor PT managed-proxy entry point.

Implemented scope:

- WebTunnel bridge argument parsing for `url`, `version`, `addr`, `servername`, `utls`, `cert`, `cert-domain`, and `sni-imitation`.
- Browser-mimicking TLS through BoringSSL via `ripdpi-tls-profiles`, golden-tested against an explicit uTLS-derived target profile.
- Bridge certificate verification is always on, as in upstream WebTunnel. By default the chain must lead to a bundled Mozilla root, and the certificate name must match `cert-domain` when set, else `servername` (default: the `url` host). `sni-imitation=<name>` sends that name in SNI and checks the certificate against the `url` host. As upstream, `cert=<base64 SHA-256 chain hash>` pins the presented chain, which replaces the CA and name checks; this reaches a bridge with a self-signed certificate. Tests trust a loopback fixture through `WebTunnelBridgeConfig::root_certificate_pem`. There is no option to skip verification. A comma-separated `servername` list (upstream picks one) is not supported.
- HTTP/1.1 Upgrade to the bridge secret path, preserving post-upgrade bytes for raw bidirectional tunneling.
- Tor PT managed-client IPC with `VERSION`, `CMETHOD webtunnel socks5`, `CMETHODS DONE`, RFC1929 bridge-argument decoding, and stdin-close shutdown.
- Full WebTunnel client dial path that connects the managed SOCKS5 listener to TLS, HTTP Upgrade, and post-upgrade relay.
- Local-network-fixture E2E coverage and Gradle PT manifest packaging from the in-repository Rust crate.

Non-goals:

- WebTunnel server or bridge mode.
- Byte-perfect browser or uTLS parity. TLS mimicry is best-effort through BoringSSL and must be golden-tested against an explicit target profile.
