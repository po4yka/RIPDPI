# tor-dirmgr vendor notes

This directory is the published `tor-dirmgr` 0.47.0 crate, from
`https://static.crates.io/crates/tor-dirmgr/tor-dirmgr-0.47.0.crate`.
The archive SHA-256 is
`34ea5e77b43bf1ef5fa213a89cb67080f218df6b418c82527e6bb9862a39aef5`.
Its source revision is `ce8bc6e0998bd5a4efdf06dd62dce53c98ea1087`.

The only code-package change widens the normalized Cargo.toml `rusqlite`
range from `<0.41` to `<0.42`. This permits `rusqlite` 0.41 and its bundled
SQLite fix for RUSTSEC-2026-0459. `Cargo.toml.orig` retains the upstream text.
Remove this patch when a compatible Arti release supports the fixed SQLite.
