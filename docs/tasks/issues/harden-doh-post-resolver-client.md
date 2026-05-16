---
title: Harden DoH POST resolver client
type: task
status: done
area: vpn
priority: high
owner: unassigned
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-16
---

- [x] #task Harden DoH POST resolver client #repo/RIPDPI #area/vpn #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `harden-doh-post-resolver-client`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-dns-resolver`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-dns-resolver/**`, `core/service/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Make DoH POST the privacy-first runtime DNS path for proxied domains and harden it against URL/query logging, cache leakage, and resolver authentication mistakes.

## Motivation

DoH GET encodes the DNS query in the URL. RIPDPI's runtime resolver should prefer POST, no-store semantics, authenticated TLS, and no logging of request body, path, or query.

## Scope

- In scope: DoH POST runtime mode, no-store headers, resolver auth name, pinned bootstrap IP support, response validation, and redacted diagnostics.
- Out of scope: DoH JSON runtime resolver and public resolver survey probes.

## Acceptance criteria

- [x] Runtime proxy DNS uses DoH POST by default for encrypted DNS.
- [x] DoH GET is disabled for runtime resolver unless a profile explicitly enables it for compatibility.
- [x] Request URL, body, domain, and response payload are absent from release logs and diagnostics by default.
- [x] Resolver TLS authentication validates the expected auth name and configured trust/pin policy.
- [x] DoH failure integrates with strict tunneled resolver failover and never falls back to plaintext local DNS.

## Design notes

Diagnostics may still probe DoH JSON or GET as separate evidence sources, but runtime resolution should remain wire-format POST by default.

## Risks / open questions

- Some resolvers may behave differently for POST and GET; keep this as a profile capability, not a silent fallback.

## Links

- [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
- [[Build DoH primary and secondary resolver pipeline]]
- [[Add DoH JSON API resolver path alongside RFC 8484 wire]]
