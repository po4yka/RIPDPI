# ripdpi-tunnel-intercept

**Layer:** L4 -- runtime / application.

`ripdpi-tunnel-intercept` applies TUN-egress interception and strategy execution over packets flowing through the tunnel runtime.

## Dependencies

- **Upstream:** `ripdpi-packets`, `ripdpi-protocol-detect`, `ripdpi-runtime-platform`, `ripdpi-strategy-config`, `ripdpi-strategy-ipv6`, `ripdpi-strategy-registry`, `ripdpi-strategy-trait`, `ripdpi-strategy-udp`.
- **Downstream:** `ripdpi-tunnel-core`.

## Boundaries

- Packet interception and strategy dispatch belong here.
- TUN device I/O belongs in `ripdpi-tun-driver` / `ripdpi-tunnel-core`; individual strategy implementations stay in `ripdpi-strategy-*` crates.

UDP rules use the existing payload classifier for QUIC, DTLS, STUN, DHT, and
WireGuard. A protocol filter does not match unrelated UDP packets. Use an empty
filter or `proto: [any]` to accept all protocols. Port and hostname filters still
apply. TUN Lua receives the detected protocol and payload subtype; QUIC retains
its parsed version and hostname markers. Detection uses existing signature
heuristics, not complete protocol validation. TCP rules match per flow, like
zapret's `--filter-l7`: the first payload segment of a flow fixes its protocol (a
TLS ClientHello or its first part for `tls`, an HTTP request for `http`, the
MTProto heuristic for `mtproto`), and every later segment of that flow, up to the
FIN or RST, matches that protocol only. Before its first payload a flow matches
every TCP protocol filter, so SYN-phase steps such as `syndata` and `wssize`
still run. Hostname filters still read each segment's own payload.

## Checks

Run focused checks with `cargo test --locked -p ripdpi-tunnel-intercept`.
