# ripdpi-ws-bootstrap

**Layer:** L4 -- runtime / application.

`ripdpi-ws-bootstrap` coordinates WebSocket-tunnel bootstrap flows, combining DNS resolution, proxy config, platform protection, TLS profiles, and MTProto WebSocket tunnel support.

## Dependencies

- **Upstream:** `ripdpi-dns-resolver`, `ripdpi-runtime-dns-cache`, `ripdpi-proxy-config`, `ripdpi-runtime-platform`, `ripdpi-tls-profiles`, `ripdpi-ws-tunnel`.
- **Downstream:** runtime and proxy adapter paths that need WS tunnel bootstrap.

## Boundaries

- Bootstrap orchestration belongs here.
- MTProto/WebSocket protocol details stay in `ripdpi-ws-tunnel`; DNS transport stays in `ripdpi-dns-resolver`.

## Checks

Run focused checks with `cargo test -p ripdpi-ws-bootstrap`.
