---
title: Add DNS interceptor and split DNS leak tests
type: task
status: blocked
area: vpn
priority: critical
owner: unassigned
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-16
---

- [ ] #task Add DNS interceptor and split DNS leak tests #repo/RIPDPI #area/vpn #status/blocked 🔺 — interceptor + leak tests pass; just test-module core:service blocked by pre-existing ConnectionPolicyResolverDirectPathTest failure

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-dns-interceptor-and-split-dns-leak-tests`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`, `native/rust/crates/ripdpi-dns-resolver/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Route app DNS through RIPDPI's VPN DNS interceptor and add leak tests proving proxied domains do not fall back to the device or ISP resolver.

## Motivation

DNS leaks are one of the main ways GUI tunneled outbound profiles fail despite a working transport. The VPN profile must set an internal DNS address and enforce split DNS through policy, not rely on the underlying network defaults.

## Scope

- In scope: VPN DNS address setup, DNS hijack/intercept path, bootstrap resolution policy, direct-domain resolver, proxied-domain resolver, and leak-test instrumentation.
- Out of scope: broad public resolver benchmarking and server-side DNS operation.

## Acceptance criteria

- [ ] VPN builder always sets DNS servers for secure VPN profiles.
- [ ] Transport endpoint bootstrap resolution is explicitly scoped and cannot route back into the TUN loop.
- [ ] RU/direct domains can resolve through direct policy while proxied domains resolve through the selected outbound.
- [ ] Proxy/default DNS failure uses encrypted backup or fails closed; it never falls back to plaintext system DNS.
- [ ] Leak test detects fallback to default-network DNS for proxied domains.
- [ ] Network-switch tests verify DNS policy remains intact across Wi-Fi and cellular changes.

## Design notes

This task is about Android VPN DNS enforcement; it should reuse the existing DNS classifier where possible instead of duplicating DNS classification logic.

## Risks / open questions

- Captive portal assist may need a temporary DNS exception; keep it explicit and short-lived.

## Links

- [[Epic - Fail-closed Android VPN policy engine]]
- [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
- [[Epic - Encrypted DNS and HTTPS SVCB classifier]]
- [[Select resolver mapping from DNS classification]]
