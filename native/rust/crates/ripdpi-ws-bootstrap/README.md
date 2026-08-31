# ripdpi-ws-bootstrap

**Layer:** L4 -- runtime / application.

`ripdpi-ws-bootstrap` coordinates WebSocket-tunnel bootstrap flows, combining
encrypted DNS resolution, proxy config, and platform protection without
depending on the concrete MTProto tunnel implementation.

## Dependencies

- **Upstream:** `ripdpi-dns-resolver`, `ripdpi-runtime-dns-cache`,
  `ripdpi-proxy-config`, `ripdpi-runtime-platform`, `ripdpi-tls-profiles`,
  `ripdpi-ws-transport-port`.
- **Downstream:** runtime and proxy adapter paths that need WS tunnel bootstrap.

## Boundaries

- Bootstrap orchestration belongs here.
- Shared DC/config contracts stay in `ripdpi-ws-transport-port`;
  MTProto/WebSocket I/O stays in `ripdpi-ws-tunnel`; DNS transport stays in
  `ripdpi-dns-resolver`.

## Checks

Run focused checks with `cargo test --locked -p ripdpi-ws-bootstrap`.
