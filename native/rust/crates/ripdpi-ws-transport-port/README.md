# ripdpi-ws-transport-port

**Layer:** L2 -- contracts / config.

This crate owns the implementation-independent contracts for the Telegram
WebSocket transport: DC and target classification, validated Worker routing
configuration, `WsTunnelConfig`, and the object-safe `WsTransport` port.

## Ownership and boundaries

- Keep this crate free of workspace and third-party dependencies.
- Blocking TLS/WebSocket connection and relay behavior belongs in
  `ripdpi-ws-tunnel`, which implements `WsTransport` as
  `TelegramWsTransport`.
- L4 runtime/bootstrap and L6 diagnostics/monitor crates consume this port and
  must not depend directly on the L7 implementation.

## Checks

Run `cargo test --locked -p ripdpi-ws-transport-port` and
`python3 scripts/ci/test_ws_transport_layer_contract.py` from the repository
root.
