# SPEC — `ripdpi-xhttp`

## Scope

XHTTP transport, FinalMask Sudoku padding, and gRPC length-prefixed
framing for Xray client outbounds. Layered above `ripdpi-vless`.

## Upstream

- xray-core (https://github.com/XTLS/Xray-core)
- Pin recorded in `SPEC_VERSION.md`

## XHTTP transport

Tunnels VLESS traffic over HTTP/1.1 or HTTP/2 with custom path,
headers, and pooling. Connection-pooling logic in `pool.rs`; relay
glue in `relay.rs`. Direct h2 body framing in `h2_body.rs`.

## FinalMask Sudoku padding

Sudoku-based byte-mapping padding applied at the transport boundary
on TCP. Implemented in `finalmask/`:

- `spec.rs` — table specification
- `masks.rs` — encoder/decoder
- `bridge.rs` — bridges into the TCP inbound/outbound

Operates on attacker-influenced bytes. Fuzz target tracked in
`docs/tasks/issues/add-fuzz-target-for-xhttp-finalmask-sudoku-decoder.md`.

## gRPC framing

Length-prefixed message framing per the gRPC over HTTP/2 wire (5-byte
prefix: 1 byte compression flag + 4 byte big-endian length). See
`grpc.rs`.

## Known divergences from upstream

- XHTTP+REALITY combination is documented as broken at xray-core
  v26.1.18; pinned upstream tag predates that change.
- Connection-pool behavior is RIPDPI-specific and not a faithful
  copy of upstream's pool.

## Non-goals

- Server-side XHTTP.
- Non-Sudoku FinalMask variants.
