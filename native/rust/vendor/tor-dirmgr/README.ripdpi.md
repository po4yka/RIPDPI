# tor-dirmgr vendor notes

This directory is the published `tor-dirmgr` 0.48.0 crate, from
`https://static.crates.io/crates/tor-dirmgr/tor-dirmgr-0.48.0.crate`.
The archive SHA-256 is
`86f7ac56b491b26a6f2b73f0f16fa7a0c66b507163e0f0ed850334b86169b326`.
Its source revision is `7c038125baef924a4bf287429c192c5af606f79c`.

The only code-package change widens the normalized Cargo.toml `rusqlite`
range from `<0.41` to `<0.42`. This permits `rusqlite` 0.41 and its bundled
SQLite fix for RUSTSEC-2026-0459. `Cargo.toml.orig` retains the upstream text.
Remove this patch when a compatible Arti release supports the fixed SQLite.
