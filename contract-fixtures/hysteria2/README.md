# Hysteria 2 conformance fixtures

Wire-format byte vectors for Hysteria 2 obfuscation paths, exercised by:

- `ripdpi-hysteria2::salamander::tests::upstream_salamander_vectors_match_encode_and_decode`

## Directory layout

```
contract-fixtures/hysteria2/<upstream-tag>/salamander/<vector-name>.{bin,plain,psk}
```

- `<upstream-tag>` matches the pin in `native/rust/crates/ripdpi-hysteria2/SPEC_VERSION.md`.
- `<vector-name>.bin` is one datagram as upstream writes it: `salt(8 bytes) + ciphertext`.
- `<vector-name>.plain` is the payload upstream obfuscated.
- `<vector-name>.psk` is the obfuscation password bytes.

The test decodes each `.bin` and asserts it equals `.plain`, then encodes `.plain` with the `.bin` salt and asserts it equals `.bin`. It requires at least eight vectors.

## Regenerating

`scripts/fixtures/hysteria2_salamander_vectors.go` drives upstream's public `obfs.WrapPacketConnSalamander`. It writes each vector through the upstream obfuscator and reads it back through the upstream deobfuscator; a datagram holding only a salt is queued first and upstream must drop it. It also checks that upstream rejects a 3-byte PSK. Salts are random, so a regeneration rewrites every `.bin`.

```bash
git clone --depth 1 --branch app/v2.13.0 https://github.com/HyNetworks/hysteria.git /tmp/hysteria
mkdir /tmp/salamander-gen && cp scripts/fixtures/hysteria2_salamander_vectors.go /tmp/salamander-gen/main.go
cd /tmp/salamander-gen && go mod init salamandergen \
  && go mod edit -require=github.com/apernet/hysteria/extras/v2@v2.0.0 \
       -replace=github.com/apernet/hysteria/extras/v2=/tmp/hysteria/extras \
       -replace=github.com/apernet/hysteria/core/v2=/tmp/hysteria/core \
  && go mod tidy && go run . "$OLDPWD/contract-fixtures/hysteria2/<upstream-tag>/salamander"
```

When the pin moves, generate into the new tag directory, remove the old one, and update `SPEC_VERSION.md`.

## Current vectors

### `v2.13.0/salamander/`

Generated from `app/v2.13.0`, commit `ca8fbd874fd6ca82413948de6dc5924405e103e1`, on 2026-10-31. The cases follow the upstream implementation's boundaries:

| Vector | PSK | Payload | Boundary |
| --- | --- | --- | --- |
| `upstream-test-key-1200-bytes` | `average_password` | 1200 B | upstream `TestSalamanderObfuscator` key and size |
| `min-psk-1-byte` | `abcd` | 1 B | `smPSKMinLen = 4`, shortest payload upstream accepts |
| `keystream-31-bytes` | `top-secret` | 31 B | one byte short of the 32-byte BLAKE2b-256 keystream |
| `keystream-32-bytes` | `top-secret` | 32 B | exactly one keystream |
| `keystream-wrap-33-bytes` | `top-secret` | 33 B | first keystream wrap |
| `keystream-wrap-65-bytes` | `top-secret` | 65 B | second keystream wrap |
| `psk-one-block-120-bytes` | 120 random bytes | 64 B | PSK + salt fill one 128-byte BLAKE2b block |
| `psk-two-blocks-121-bytes` | 121 random bytes | 64 B | PSK + salt spill into a second block |
| `utf8-psk-1350-bytes` | `пароль-obfs` (UTF-8) | 1350 B | non-ASCII PSK, full-size QUIC datagram |
