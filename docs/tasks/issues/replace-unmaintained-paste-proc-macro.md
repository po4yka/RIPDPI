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
updated: 2026-08-26
status_detail: "Blocked on upstream: paste 1.0.15 now reaches the graph only through the Arti stack (pwd-grp 1.0.2 latest, slotmap-careful 0.8.1, and direct deps of tor-* 0.44.0 crates; newest Arti line tor-basic-utils 0.45.0 still depends on paste), plus a target-gated netlink-packet-core 0.8.1 path whose consumers (netlink-packet-route 0.25.1/0.28.0) pin core ^0.8.0 while only core 0.9.0 dropped paste. The netlink path named in the issue is no longer the live host-graph path. No upstream release removes paste today; removal would require patch-forking multiple third-party crates, which violates the deny.toml sources policy and exceeds this change's scope. Re-evaluate when Arti releases a paste-free version or netlink-packet-route bumps to core ^0.9."
---

## Goal

Remove the `RUSTSEC-2024-0436` waiver by upgrading or replacing the `netlink-packet-core` path that still pulls `paste 1.0.15`.

## Review deadline

Re-evaluate the waiver no later than 2026-10-11. The machine-checked expiry in `native/rust/advisory-waivers.toml` intentionally blocks CI on that date until this task is reviewed.

## Acceptance criteria

- `cargo tree --manifest-path native/rust/Cargo.toml -i paste` no longer reports `paste 1.0.15`.
- `RUSTSEC-2024-0436` is removed from `native/rust/deny.toml` and `native/rust/advisory-waivers.toml`.
- `cargo deny --manifest-path native/rust/Cargo.toml check advisories` and `python3 scripts/ci/check_rust_advisory_waivers.py` pass.
