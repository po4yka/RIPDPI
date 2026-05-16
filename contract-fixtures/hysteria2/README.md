# Hysteria 2 conformance fixtures

Wire-format byte vectors for Hysteria 2 obfuscation paths, exercised by:

- `ripdpi-hysteria2::salamander::upstream_salamander_fixtures_decode_cleanly`

## Directory layout

```
contract-fixtures/hysteria2/<upstream-tag>/salamander/<key-hex>/<vector-name>.bin
```

- `<upstream-tag>` matches the pin in
  `native/rust/crates/ripdpi-hysteria2/SPEC_VERSION.md` (e.g. `v2`).
- `<key-hex>` is the lowercase hex encoding of the obfuscation key
  bytes. The harness reads the directory name and instantiates a
  `SalamanderCodec` with the decoded key.
- `<vector-name>.bin` contains `salt(8 bytes) + ciphertext`. The
  harness reads the file, runs `codec.decode(wire)`, and asserts
  the decoded length equals `wire.len() - 8`.

## How to add a vector

1. Capture a Salamander-obfuscated datagram from a real apernet/hysteria
   session at the pinned upstream tag.
2. Identify the obfuscation key. Save the wire bytes (the full
   `salt + ciphertext`) to a `.bin` file under the matching key
   directory (`<key-hex-encoded>`).
3. Add a comment in this README naming the source.

The fixture-walker test picks new files up automatically.

## Current vectors

### `v2/salamander/746f702d736563726574/`

Key: `top-secret` (ASCII).

- `hello-zero-salt.bin` — `salt = 0x0000000000000000`, plaintext
  `"hello"`. Synthetic regression boundary, not from upstream
  capture. Hand-computed via
  `blake2b256(key || salt)[:5] XOR plaintext`.
