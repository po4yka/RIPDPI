# VLESS-mux conformance fixtures

Wire-format byte vectors for VLESS-mux frames, exercised by the fixture-walker tests in:

- `ripdpi-relay-mux::wire_mux::yamux::upstream_yamux_fixtures_round_trip`

Two fixture families are enforced differently: pinned upstream `.bin` frames are walked by `ripdpi-relay-mux`, while the `sing-mux-6fb501d/*.hex` encoder vectors are decoded and checked by `ripdpi-vless`.

## Directory layout

```
contract-fixtures/vless/<upstream-tag>/mux/<framing>/<vector-name>.bin
```

- `<upstream-tag>` matches the pin in `native/rust/crates/ripdpi-vless/SPEC_VERSION.md` (e.g. `v1.260206.0`).
- `<framing>` is `yamux` for the current checked-in vectors. Use `sing_mux` when adding sing-mux fixtures and a matching fixture walker.
- `<vector-name>` is descriptive, lowercase-kebab; no extension collisions because every file ends in `.bin`.

## Fixture-file format

Each `.bin` file contains a single frame's wire bytes:

- **yamux:** 12-byte header followed by `length` bytes of payload. The harness decodes the header, re-encodes it, and asserts byte equality on the header bytes. Payload bytes pass through.

## How to add a vector

1. Capture a frame from a real xray-core / sing-box session (`tcpdump`, `wireshark`, or an in-process recorder) at the pinned upstream tag.
2. Save the wire bytes to `<upstream-tag>/mux/<framing>/<name>.bin`.
3. Add a comment in this README naming the source if it's reproducible.

The yamux fixture-walker tests pick new yamux files up automatically on the next test run. Sing-mux fixture files require adding the matching walker before they are enforced.

## Current vectors

### `sing-mux-6fb501d/mux/yamux/`

Eight encoder vectors derived from SagerNet sing-mux version-0/yamux carrier and TCP stream-request behavior at upstream commit `6fb501d02534177fed5567ee8f63afbc825e2861` and `sing` v0.7.14's Socksaddr serializer. Unlike the pinned `.bin` frame captures below, these `.hex` files are expected encoder outputs; `ripdpi-vless::mux::pinned_yamux_fixtures_match_encoder` decodes and verifies every vector.

### `v1.260206.0/mux/yamux/`

- `syn-data-stream1-abc.bin` — SYN Data frame on stream id 1 carrying the 3-byte payload `abc`. Hand-assembled regression boundary; not from an upstream packet capture.
