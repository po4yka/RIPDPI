# RIPDPI BoringSSL vendor notes

The source is the published `boring-sys` 5.3.0 crate:

- Archive: `https://static.crates.io/crates/boring-sys/boring-sys-5.3.0.crate`
- SHA-256: `4ebc865c335508b2eb7bdcfd35e644ce00ddf385a8feda9b5b9031d8b0b49c35`
- Upstream Git revision: `2bea8245d9b21776bc9f767b4e82dc2a78a5a262`.

The local changes are:

- `build/main.rs` and `build/config.rs` retain the Android CMake, C++ runtime,
  and build freshness fixes. They also retain the previous internal config and
  build entry points; the upstream install/export interface is not used by RIPDPI.
  Cargo watches the checked-in BoringSSL sources and patches. It does not watch
  the generated CMake output directory.
  Keep these changes when the crate is imported.
- `deps/boringssl/include/openssl/ssl.h` and the four files in
  `deps/boringssl/ssl/` named `handshake_client.cc`, `internal.h`,
  `ssl_key_share.cc`, and `ssl_lib.cc` implement the REALITY ClientHello hook,
  X25519 private-key access, and fixed client random. See
  `../../../../docs/design/reality-boringssl-patch.md`.

For an update, extract the published crate, compare it with the previous
published crate, and apply these local changes to the new source. Do not change
only the package version. BoringSSL has no stable internal ABI. Version 5.3.0
uses `FromOpaque` in public functions to access `SSLImpl` and `SSLContext`.
The hook still runs after ClientHello serialization and before `add_message`.

`deps/boringssl/ssl/extensions.cc` retains RFC 7685 length padding on initial
full handshakes. This keeps the Firefox and Safari browser packet contracts
stable. Resumed handshakes use upstream behavior.

Resolve the workspace patch with `cargo update -p boring-sys --precise <version>`.
The workspace lock must select this path package and must not list it in
`patch.unused`. Update the REALITY vector version metadata and its Python and
Rust assertions. Run `python3 -m unittest scripts.tests.test_reality_boring_vector`
from the repository root and `cargo test -p ripdpi-vless --locked` from
`native/rust/`. The live hook test must use the real library.
