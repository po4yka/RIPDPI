---
id: RST-1786264762917304
title: Remove RSA advisory paths from russh and Arti dependencies
kind: chore
status: backlog
area: rust-native
priority: low
owner: Native security maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: rst-1786264762917304-unpin-russh-after-rsa-advisory-fix
created: 2026-06-12
updated: 2026-08-09
source_wiki_pages: []
---

## Motivation

`native/rust/Cargo.toml` pins russh at exactly `=0.62.5` and `native/rust/deny.toml` suppresses RUSTSEC-2023-0071 (rsa Marvin timing sidechannel) with the justification that:

1. rsa 0.9.10 enters via Arti 0.44.0 → ssh-key-fork-arti — RIPDPI uses Arti only as a Tor client backend and does not expose RSA private-key service operations.
2. the pre-release rsa line enters via the current russh 0.62.5 pin (ripdpi-ssh SSH outbound engine) — SSH publickey auth signs the session identifier (a transcript hash the client did not choose), not attacker-chosen plaintext, so the Marvin timing sidechannel is not practically exploitable.

No safe upgrade existed on either path at the time of pinning. The suppression is a placeholder, not a permanent decision.

## Review deadline

Re-evaluate the waiver no later than 2026-11-02. The machine-checked expiry in `native/rust/advisory-waivers.toml` intentionally blocks CI on that date until this task is reviewed.

## Trigger

A russh release that either:
- Drops the rsa dependency entirely, or
- Upgrades rsa to a version that resolves RUSTSEC-2023-0071 (i.e., a russh release compatible with rsa ≥ 0.10.0 stable with the fix applied).

Check periodically: https://github.com/Eugeny/russh/releases

## Proposed change

1. Upgrade or replace both the `russh` and Arti dependency paths until the locked graph no longer contains the vulnerable RSA line.
2. Remove the `RUSTSEC-2023-0071` suppression only after every path is gone.
3. Run focused SSH/Tor tests, the locked workspace suite, advisory validation, and waiver-expiry checks.

## Acceptance criteria

- [ ] `cargo deny check advisories` exits 0 with the RUSTSEC-2023-0071 suppression removed from deny.toml.
- [ ] `cargo nextest run -p ripdpi-ssh --locked` green.
- [ ] `cargo nextest run --workspace --locked` green.
- [ ] The `=0.62.5` exact pin is removed or updated in Cargo.toml.
- [ ] Commit message references the russh release that resolved the rsa dependency.

## References

- `native/rust/deny.toml` lines 10-14 — current suppression with full rationale.
- `native/rust/Cargo.toml` — `russh = "=0.62.5"` exact pin at this review.
- RUSTSEC-2023-0071: https://rustsec.org/advisories/RUSTSEC-2023-0071.html
- russh releases: https://github.com/Eugeny/russh/releases
