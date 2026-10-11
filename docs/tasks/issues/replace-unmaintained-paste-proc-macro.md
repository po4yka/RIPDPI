---
id: RST-1786264762917234
title: Replace unmaintained paste proc-macro dependency
kind: feature
status: blocked
area: rust-native
priority: low
owner: Native security maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: rst-1786264762917234-replace-unmaintained-paste-proc-macro
created: 2026-07-13
updated: 2026-10-11
status_detail: Reviewed on 2026-10-11. The latest Arti 0.47.0, slotmap-careful 0.9.0, and pwd-grp 1.0.2 still require paste 1.0.15. Target-gated netlink-packet-core 0.8.1 also requires paste through netconfig-rs and route_manager. A netlink update alone cannot remove the Arti paths. Replacement remains blocked on upstream releases. The owner approved renewal of the unmaintained advisory exception through 2026-11-10.
---

## Goal

Remove the `RUSTSEC-2024-0436` waiver by upgrading or replacing all Arti and target-gated netlink paths that still pull `paste 1.0.15`.

## Review deadline

Re-evaluate the waiver before 2026-11-10. The machine-checked expiry in `native/rust/advisory-waivers.toml` blocks CI on that date until the owner reviews, removes, or renews the exception.

## Review on 2026-10-11

- The owner approved renewal of this exception through 2026-11-10.
- `cargo tree --manifest-path native/rust/Cargo.toml --locked --target all -i paste` confirmed that `paste 1.0.15` remains in Arti `0.47.0`, `slotmap-careful 0.9.0`, `pwd-grp 1.0.2`, and `netlink-packet-core 0.8.1` paths.
- The crates.io registry reports Arti `0.47.0`, `slotmap-careful 0.9.0`, and `pwd-grp 1.0.2` as their latest stable releases. Each still requires `paste`. A netlink update alone cannot remove it from the workspace.
- [RustSec RUSTSEC-2024-0436](https://rustsec.org/advisories/RUSTSEC-2024-0436.html) classifies this as `INFO Unmaintained` and lists no patched `paste` version. The notice does not establish that the dependency is free of other risks.
- The proposed policy passed all four checks with the CI-pinned `cargo-deny 0.19.8`. The waiver metadata validator passed for 2026-10-11 and rejected the renewed waiver at its 2026-11-10 boundary.
- Removal remains blocked on upstream changes. No dependency, source allowlist, license allowlist, or validator changed in this renewal.

## Acceptance criteria

- `cargo tree --manifest-path native/rust/Cargo.toml --locked --target all -i paste` no longer reports `paste 1.0.15`.
- `RUSTSEC-2024-0436` is removed from `native/rust/deny.toml` and `native/rust/advisory-waivers.toml`.
- `cargo deny --manifest-path native/rust/Cargo.toml --locked check advisories` and `python3 scripts/ci/check_rust_advisory_waivers.py` pass.
