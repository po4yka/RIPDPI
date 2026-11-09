# ripdpi-protocol-detect

**Layer:** L1 -- protocol / core.

`ripdpi-protocol-detect` classifies the first payload of a client-to-server flow into an `L7Protocol` with `classify_l7`, without heap allocation.

## Dependencies

- **Upstream:** `ripdpi-strategy-trait`.
- **Downstream:** `ripdpi-proxy-runtime-desync-adapter` (`socket_lua`) and `ripdpi-tunnel-intercept` (`egress`). Both classify outbound packets only.

## Detected protocols

- TCP: TLS ClientHello, MTProto obfuscated2 init (AES-256-CTR validated, any port; MTProxy secret-keyed inits are not detected), DNS on port 53 or 5353.
- UDP: QUIC v1 and v2 client Initial (datagram of 1200 bytes or more, version reported), WireGuard, DTLS, STUN, DNS on port 53 or 5353, DHT (BEP-5 KRPC).

Discord, XMPP, BitTorrent, and uTP are `L7Protocol` variants without a detector here. TLS `sni` is not filled; the backends parse it.

## Boundaries

- Detection primitives belong here.
- Runtime policy, diagnostics orchestration, and packet mutation belong in their dedicated crates.

## Checks

Run focused checks with `cargo test -p ripdpi-protocol-detect --locked`.
