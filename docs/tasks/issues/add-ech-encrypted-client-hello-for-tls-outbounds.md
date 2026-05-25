---
title: Add Encrypted Client Hello (ECH) for TLS outbounds
type: task
status: done
area: rust-native
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-16
updated: 2026-05-25
---

- [x] #task Add Encrypted Client Hello (ECH) for TLS outbounds #repo/RIPDPI #area/rust-native #status/done 🔼 ✅ 2026-05-25

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-ech-encrypted-client-hello-for-tls-outbounds`
- **Verify:** `cargo test -p ripdpi-tls-profiles -p ripdpi-xhttp -p ripdpi-masque`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-tls-profiles/**`, `native/rust/crates/ripdpi-xhttp/**`, `native/rust/crates/ripdpi-masque/**`, `docs/native/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Wire ECH (draft-ietf-tls-esni-22) into the TLS outbound stack so the real SNI is encrypted under a server-published HPKE key. ECH eliminates the most reliable single-feature DPI signal (cleartext SNI) without requiring domain fronting.

## Context

Russian TSPU and similar DPI systems target HTTPS sessions via the cleartext SNI extension in ClientHello. Both Chrome and Firefox now ship ECH by default to ECH-enabled origins (Cloudflare, Fastly). For RIPDPI to look indistinguishable from those browsers, the outbound stack must also speak ECH.

BoringSSL has ECH primitives behind a feature flag. The `boring` crate exposes some of them; missing pieces may need additional extern declarations (see `pin-boringssl-symbols-with-build-time-existence-check` for the discipline).

## Acceptance criteria

- [x] `ripdpi-tls-profiles` exposes a `EchConfig` carrying the server's HPKE keyset (parsed from the DNS HTTPSSVC record or operator-supplied bytes).
- [x] xHTTP and MASQUE outbounds accept `EchConfig` and route the real SNI through HPKE encryption; the outer ClientHello carries only the public name.
- [x] Negotiation fallback: if ECH is rejected with retry configs, surface an ECH retry-required error rather than falling through to plain SNI silently.
- [x] Unit tests cover ECH config validation, BoringSSL application, and transport config threading.
- [x] `docs/native/relay-masque-status.md` documents the ECH option and Cloudflare-direct interaction.

## Work log

- Added `OutboundEchConfig`, BoringSSL ECH application, xHTTP TLS config threading, MASQUE H2/H3 ECH wiring, retry-required error mapping, and MASQUE runtime documentation.
- Verified with `CARGO_TARGET_DIR=/tmp/ripdpi-protocol-gaps-target cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-tls-profiles -p ripdpi-xhttp -p ripdpi-masque` (exit 0).
- Remaining risk: live ECH success/rejection behavior still needs a real ECH-enabled endpoint or owned integration fixture; current coverage validates local config parsing/application and no silent cleartext fallback.

## Risks / open questions

- BoringSSL ECH support has changed across releases; pair this with `pin-boringssl-symbols-with-build-time-existence-check` so a vendor revision swap is caught.
- Tracker for the HTTPSSVC DNS record fetch: pairs with `add-doh-json-api-resolver-path-alongside-rfc-8484-wire`.

## Links

- IETF draft-ietf-tls-esni-22
- [[recurring-upstream-watch-for-xray-core-reality-ech-xhttp-changes]]
