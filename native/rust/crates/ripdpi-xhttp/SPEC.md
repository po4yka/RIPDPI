# SPEC — `ripdpi-xhttp`

## Scope

XHTTP transport, Finalmask transforms, and gRPC length-prefixed framing for Xray client outbounds. Layered above `ripdpi-vless`.

## Upstream

- xray-core (https://github.com/XTLS/Xray-core)
- Pin recorded in `SPEC_VERSION.md`

## XHTTP transport

Tunnels raw VLESS bytes over HTTP/2 with a configured path, Host override,
default Referer padding and connection pooling. The supported client modes are
`stream-up` and `stream-one`. REALITY auto selects `stream-one`; TLS auto and
`packet-up` are rejected. `stream-down` is the GET download leg, not a selectable
mode. Separate `downloadSettings` endpoints and arbitrary request headers are
not supported.

Connection pooling is in `pool.rs`; session ownership is in `relay.rs`.
HTTP request construction and body framing are in `h2_body.rs`. The gRPC-like
Content-Type is a header mask; the xHTTP driver does not apply `grpc.rs` framing.

## Finalmask transforms

Sudoku-based byte-mapping padding applied at the transport boundary on TCP. Implemented in `finalmask/`:

- `spec.rs` — table specification
- `masks.rs` — encoder/decoder
- `bridge.rs` — bridges into the TCP inbound/outbound

The implemented modes are `HeaderCustom`, `Noise`, `Fragment`, and `Sudoku`.
They operate on attacker-influenced bytes and are exercised by the
`finalmask_spec` and `finalmask_decoder` fuzz targets.

## gRPC framing

Length-prefixed message framing per the gRPC over HTTP/2 wire (5-byte prefix: 1 byte compression flag + 4 byte big-endian length). See `grpc.rs`.

## Known divergences from upstream

- The xHTTP wire reference is Xray v26.2.6. The separate Android Xray provider has its own version admission policy; this crate pin is not proof of that AAR.
- Only the supported H2 modes and default metadata placement are implemented.
- Finalmask uses its own reference and supported subset in `SPEC_VERSION.md`; no full-release parity is claimed.
- `fragment` splits the first carrier chunk without changing TLS records. It does not guarantee separate TCP segments.
- Header and noise support is a one-way prefix contract. Variable-length prefixes need a peer with an explicit length agreement; the Xray example uses a fixed length and an empty server sequence.
- Connection-pool behavior is RIPDPI-specific and not a faithful copy of upstream's pool.

## Non-goals

- Server-side XHTTP.
