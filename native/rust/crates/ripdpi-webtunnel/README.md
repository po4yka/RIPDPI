# ripdpi-webtunnel

`ripdpi-webtunnel` is the in-repository Rust client implementation of the WebTunnel pluggable transport used by RIPDPI. The library owns WebTunnel client behavior; the binary is a thin Tor PT managed-proxy entry point.

Implemented scope:

- WebTunnel bridge argument parsing for `url`, `version`, `addr`, `servername`, and `utls`.
- RIPDPI-oriented ClientHello profile selection metadata for the next TLS slice.

Planned scope:

- Browser-mimicking TLS through BoringSSL via `ripdpi-tls-profiles`.
- HTTP/1.1 Upgrade to the bridge secret path and raw bidirectional tunneling after `101 Switching Protocols`.
- Tor PT managed-client IPC with a local SOCKS5 listener.

Non-goals:

- WebTunnel server or bridge mode.
- Byte-perfect browser or uTLS parity. TLS mimicry is best-effort through BoringSSL and must be golden-tested against an explicit target profile.
