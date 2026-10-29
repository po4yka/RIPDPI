# RIPDPI boringtun patch

The source is the published boringtun 0.7.1 crate:

- Archive: `https://static.crates.io/crates/boringtun/boringtun-0.7.1.crate`
- SHA-256: `15dd6a8a89cbe8997f37ca0cf035e6ea4d64cd2ecea4aed83ffb9f99f7126939`.
- Upstream revision: `051c9d47dc9c5cb36e461b7d36dcd673820dc98b`.
- License: BSD-3-Clause. `LICENSE.md` is copied from that upstream revision.

This package stays outside the application workspace. Its separate workspace and
lockfile let Cargo run the upstream and local backend tests without adding its
benchmark and test dependencies to the Android dependency graph. Production uses
the application workspace lockfile and does not enable `mock-instant`.

Local changes:

- `noise/awg.rs` supplies an opt-in AWG timer policy and event deadlines.
- `noise/mod.rs` connects authenticated protocol events and send/receive expiry to
  that policy. It recognizes authenticated all-zero AWG keepalives so they reset
  idle timers. The original constructor retains ordinary WireGuard behavior.
- `noise/timers.rs` selects the AWG policy only when configured, and contains real
  encrypted-peer regression tests with deterministic elapsed time.

The contract and independent expected outcomes are in
`openspec/changes/awg31-behavior-parity/design.md` at the repository root. Cookie,
padding, and receive-result changes remain separate execution steps.

From `native/rust`, run:

```sh
build-gate --lane ripdpi -- cargo test --manifest-path vendor/boringtun/Cargo.toml --lib --locked --jobs 4
build-gate --lane ripdpi -- cargo test -p ripdpi-warp-core --locked --jobs 4
```

On an upstream update, verify the published archive checksum, compare the source,
and reapply only the listed local changes. Preserve license notices. Review timer
and session semantics again, run both test sets and the pinned-peer matrix, and
confirm that default WireGuard behavior still passes. The existing
`scripts/ci/run-rust-workspace-tests.sh` runs this package's tests explicitly.
Do not change only the
package version or copy registry build outputs into this directory.

The root lockfile was regenerated with Cargo 1.98.1 and a targeted update of
boringtun 0.7.1. Cargo also chose existing compatible getrandom and Windows
packages for several dependency edges. No registry package version was added or
removed. These resolver changes are not hand-written lockfile edits.
