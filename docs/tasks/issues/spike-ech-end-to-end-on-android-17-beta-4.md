---
title: Spike ECH end-to-end on Android 17 Beta 4
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-22
---

- [ ] #task Spike ECH end-to-end on Android 17 Beta 4 #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `spike-ech-end-to-end-on-android-17-beta-4`
- **Verify:** `just test-module core:diagnostics`
- **Scope (only modify these + this file + the ledger):** `core/diagnostics/**`, `native/rust/crates/ripdpi-diagnostics-tls/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Validate the full platform-ECH happy path on Android 17 Beta 4: query an
HTTPS/SVCB record that carries an ECHConfig, feed it to Conscrypt, and
complete a TLS handshake against a known ECH-capable host.

## Research citation

[[ripdpi-android-research-2026-04-20]] §Android platform — Android 17
Beta 4 (April 2026) exposes `DnsResolver` HTTPS-RR queries with ECH and
new Conscrypt `SSLEngine`/`SSLSocket` ECH knobs. This is the platform
path owned-stack mode depends on; verify it works before deeper design.

## Acceptance criteria

- [ ] `DnsResolver` HTTPS-RR query returns a parseable ECHConfig on
    Beta 4 for at least one known ECH-capable host.
- [ ] Conscrypt `SSLEngine` / `SSLSocket` completes a handshake using
    that ECHConfig (ClientHelloInner encrypted, ClientHelloOuter
    innocuous).
- [ ] Spike note records: emulator/device matrix, flaky paths, pre-stable
    API caveats, and any deltas from the documented surface.
- [ ] Spike note records whether successful ECH changes only metadata
    privacy / owned-stack reachability, or actually changes the practical
    bypass verdict on the tested host class.
- [ ] Spike note records the DNS dependency explicitly: which resolver path
    and `HTTPS/SVCB` bootstrap were required before ECH could even be tried.

## Links

- [[Epic - Owned-stack mode with Android 17 ECH]]
- [[Parse HTTPS SVCB records with ECH config metadata]]
- [[Document Android 17 ECH requirement and graceful degradation]]
- [[ripdpi-android-research-2026-04-20]]
- [[ech-practical-censorship-value-2026]]


## privacy-preserving-strategy-learner
