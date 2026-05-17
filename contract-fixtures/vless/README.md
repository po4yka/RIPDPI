# VLESS-mux conformance fixtures

Wire-format byte vectors for VLESS-mux frames, exercised by the fixture-walker tests in:

- `ripdpi-relay-mux::wire_mux::yamux::upstream_yamux_fixtures_round_trip`
- `ripdpi-relay-mux::wire_mux::sing_mux::upstream_sing_mux_fixtures_round_trip` (planned)

## Directory layout

```
contract-fixtures/vless/<upstream-tag>/mux/<framing>/<vector-name>.bin
```

- `<upstream-tag>` matches the pin in `native/rust/crates/ripdpi-vless/SPEC_VERSION.md` (e.g. `v1.260206.0`).
- `<framing>` is `yamux` or `sing_mux`.
- `<vector-name>` is descriptive, lowercase-kebab; no extension collisions because every file ends in `.bin`.

## Fixture-file format

Each `.bin` file contains a single frame's wire bytes:

- **yamux:** 12-byte header followed by `length` bytes of payload. The harness decodes the header, re-encodes it, and asserts byte equality on the header bytes. Payload bytes pass through.

## How to add a vector

1. Capture a frame from a real xray-core / sing-box session (`tcpdump`, `wireshark`, or an in-process recorder) at the pinned upstream tag.
2. Save the wire bytes to `<upstream-tag>/mux/<framing>/<name>.bin`.
3. Add a comment in this README naming the source if it's reproducible.

The fixture-walker tests will pick the new files up automatically on the next test run. No code changes are required.

## Current vectors

### `v1.260206.0/mux/yamux/`

- `syn-data-stream1-abc.bin` — SYN Data frame on stream id 1 carrying the 3-byte payload `abc`. Hand-assembled regression boundary; not from an upstream packet capture.
