---
title: Add DNS-over-HTTPS tunnel mode for arbitrary payloads
type: task
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-16
updated: 2026-05-16
---

- [ ] #task Add DNS-over-HTTPS tunnel mode for arbitrary payloads #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-dns-over-https-tunnel-mode-for-arbitrary-payloads`
- **Verify:** `cargo test -p ripdpi-doh-tunnel`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-doh-tunnel/**` (new crate), `docs/native/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Encapsulate arbitrary tunnel payloads inside DNS-over-HTTPS queries and responses to a controlled DoH endpoint. Combined with domain-fronting via Cloudflare/Google DoH resolvers, the on-wire traffic is indistinguishable from ordinary browser DNS resolution.

## Context

DoH (RFC 8484) wraps DNS messages inside HTTP/2 POST requests to well-known resolver endpoints. Russian whitelisting cannot easily block Cloudflare's `1.1.1.1` or Google's `8.8.8.8` DoH endpoints without breaking enormous downstream traffic. By tunneling payload bytes inside synthetic DNS query names (encoded as base32-subdomains) and reading reply bytes from the synthetic TXT/AAAA answers, we get a tunnel that's hard to block without collateral damage.

Throughput is low (DNS message budget ~512 bytes per round trip) but suffices for control-plane traffic, fallback bootstrap, and low-bandwidth web browsing.

## Acceptance criteria

- [ ] New crate `ripdpi-doh-tunnel` with client-side encoder/decoder for tunneled DNS queries.
- [ ] Encoding scheme documented: query name format, answer chunking, sequence numbering, reorder handling.
- [ ] Configurable DoH endpoint (Cloudflare, Google, or operator- supplied).
- [ ] Loopback test exercises a 64-KB round trip through a mock DoH server.
- [ ] Telemetry: bytes-tunneled, round-trips, drop-rate.
- [ ] Documentation under `docs/native/` explains the throughput ceiling and intended use cases (control-plane, not bulk).

## Risks / open questions

- Synthetic DNS queries with high entropy in the QNAME may trip anti-DNS-tunneling heuristics on the resolver side. Mitigate by rate-limiting and using dictionary-encoded subdomain syllables (`apple-12-banana` instead of `aiwQ8x2`).
- DoH endpoint blocking would render this useless on any path where Russia eventually decides Cloudflare DoH is collateral- acceptable. Pair with the upstream-watch task.

## Links

- RFC 8484 (DoH)
- [[add-doh-json-api-resolver-path-alongside-rfc-8484-wire]]
