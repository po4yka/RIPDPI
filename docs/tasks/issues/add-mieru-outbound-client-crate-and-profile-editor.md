---
title: Add Mieru outbound client crate and profile editor
type: task
status: backlog
area: outbound
priority: medium
owner: unassigned
parent: epic-extended-outbound-protocol-support
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [ ] #task Add Mieru outbound client crate and profile editor #repo/RIPDPI #area/outbound #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-mieru-outbound-client-crate-and-profile-editor`
- **Verify:** `just test-module core:data:runtime-state`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-proxy-config/**`, `core/data/runtime-state/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add a `ripdpi-mieru` Rust crate implementing the Mieru outbound client and a `MieruProfileScreen` editor. Mieru (enfein/mieru) is actively developed and used in the Chinese bypass community; ignoring it blocks that user cohort.

## Context

Mieru uses a custom UDP-based protocol with replay resistance; the Go reference implementation is the canonical spec. Upstream tests are the reference for protocol-level correctness. TCP transport mode is also supported upstream; both should land.

## Acceptance criteria

- [ ] `ripdpi-mieru` crate passes upstream reference handshake + session-framing test vectors.
- [ ] UDP and TCP transport modes both supported.
- [ ] Multiplexing behavior matches upstream.
- [ ] `MieruProfileScreen` validates server + port, username, password, protocol mode (TCP/UDP), mTU.
- [ ] Mieru's time-based replay protection is clock-synced via the existing network-time source, not `System.currentTimeMillis`.
- [ ] Credentials redacted in all diagnostic surfaces.
- [ ] Subscription import path recognizes `mieru://` URIs.

## Source references

**Reference implementation notes:**:

- `app/src/main/java/io/nekohasekai/sagernet/fmt/mieru/MieruBean.java` — bean fields: `username`, `password`, `mtu`, `protocol` (TCP/UDP), `multiplexing` (OFF/LOW/MIDDLE/HIGH).
- `app/src/main/java/io/nekohasekai/sagernet/ui/profile/MieruSettingsActivity.kt` — editor.
- reference implementation has no `mieru://` URI codec (editor + plugin-config-only); **RIPDPI should invent one** since subscription import is a stated goal.

**Outbound engine (NOT from reference implementation):** upstream [`enfein/mieru`](https://github.com/enfein/mieru) (Go). Reference implementation shells out to the `mieru-plugin` APK; RIPDPI needs a pure-Rust port or vendored build. The protocol is custom UDP-based with replay protection — non-trivial port effort.

**Adapt:** Bean fields, multiplexing level mapping. **Invent:** `mieru://` URI scheme (e.g. `mieru://username:password@host:port?protocol=tcp&mux=middle`). **Skip:** Reference implementation's external-process plugin path.

## Links

- [[Epic - Extended outbound protocol support]]
