# RIPDPI BoringSSL vendor notes

The source is the published `boring-sys` 5.5.0 crate:

- Archive: `https://static.crates.io/crates/boring-sys/boring-sys-5.5.0.crate`
- SHA-256: `666b6d090823b306ff043582051c10afd83ad4170cb5d677cb33f45c475d6feb`
- Upstream Git revision: `bbdc50f0462a5e2ee3cdd71b09ea7a5eccd77240`.

The local changes are:

- `build/main.rs` and `build/config.rs` retain the Android CMake, C++ runtime,
  and build freshness fixes. They also retain the previous internal config and
  build entry points; the upstream install/export interface is not used by RIPDPI.
  Cargo watches the checked-in BoringSSL sources and patches. It does not watch
  the generated CMake output directory.
  The NDK toolchain owns CMake compiler selection. `ANDROID_PLATFORM` and
  bindgen use the API level from the selected Android compiler. This prevents
  compiler changes from resetting an existing CMake cache to the host platform.
  Keep these changes when the crate is imported.
- `deps/boringssl/include/openssl/ssl.h` and the four files in
  `deps/boringssl/ssl/` named `handshake_client.cc`, `internal.h`,
  `ssl_key_share.cc`, and `ssl_lib.cc` implement the REALITY ClientHello hook,
  X25519 private-key access, and fixed client random. See
  `../../../../docs/design/reality-boringssl-patch.md`.

For an update, extract the published crate, compare it with the previous
published crate, and apply these local changes to the new source. Do not change
only the package version. BoringSSL has no stable internal ABI. Version 5.5.0
uses `FromOpaque` in public functions to access `SSLImpl` and `SSLContext`.
The hook still runs after ClientHello serialization and before `add_message`.

`deps/boringssl/ssl/extensions.cc` keeps signature-algorithm GREASE disabled
for the existing browser packet contracts. BoringSSL 5.4 enables it with
general GREASE.

`deps/boringssl/ssl/extensions.cc` retains RFC 7685 length padding on initial
full handshakes. This keeps the Firefox and Safari browser packet contracts
stable. Resumed handshakes use upstream behavior.

The same file preserves the alert selected by key exchange validation in TLS
1.3. Invalid hybrid share lengths and invalid X25519 shares report
`illegal_parameter`, rather than replacing that alert with `internal_error`.

Resolve the workspace patch with `cargo update -p boring-sys --precise <version>`.
The workspace lock must select this path package and must not list it in
`patch.unused`. Update the REALITY vector version metadata and its Python and
Rust assertions. Run `python3 -m unittest scripts.tests.test_reality_boring_vector`
from the repository root and `cargo test -p ripdpi-vless --locked` from
`native/rust/`. The live hook test must use the real library.

`deps/boringssl/ssl/encrypted_client_hello.cc` returns a null buffer and zero
length from the ECH retry getter until the backend has authenticated rejection.
It keeps retry data private on TLS alerts or certificate failures, instead of
aborting or returning a malformed placeholder. The corresponding `ssl.h` API
comment records this local safe optional-accessor contract. Absence of retry
data never authorizes a cleartext fallback.

`deps/boringssl/ssl/encrypted_client_hello.cc` treats a supplied ECHConfigList
as required Real ECH. After the setter stores a valid list, it enables
`reject_unusable_ech_config`; an unsupported-only list fails before the first
ClientHello instead of exposing the inner server name. GREASE without a list
keeps upstream behavior.
