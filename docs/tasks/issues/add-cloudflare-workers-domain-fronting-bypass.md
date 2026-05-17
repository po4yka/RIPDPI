---
title: Add Cloudflare Workers domain-fronting bypass adapter
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

- [ ] #task Add Cloudflare Workers domain-fronting bypass adapter #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-cloudflare-workers-domain-fronting-bypass`
- **Verify:** `cargo test -p ripdpi-cloudflare-origin -p ripdpi-masque`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-cloudflare-origin/**`, `native/rust/crates/ripdpi-masque/**`, `docs/native/cloudflare-tunnel-operations.md`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Route tunnel traffic through Cloudflare Workers (serverless edge compute) so the on-wire TLS connection targets a generic `*.workers.dev` or operator-mapped custom domain. The Worker forwards the inner stream to the real upstream. DPI sees a vanilla TLS connection to a Cloudflare-fronted hostname; the real destination is hidden inside the Worker request.

## Context

Cloudflare Workers terminate TLS at Cloudflare's edge and route HTTP requests to operator-defined backends. Combined with WebSocket upgrade and a small Worker script, this gives an operator-controlled domain-fronted relay that's indistinguishable from any other Cloudflare-fronted site.

RIPDPI already has `ripdpi-cloudflare-origin` and Cloudflare-direct MASQUE; this task adds the *Workers-fronted* deployment mode where the worker hostname is the SNI and the real upstream is in a header.

## Acceptance criteria

- [ ] Operator-supplied Worker URL + auth bearer is consumable via `core:data:model` typed schema.
- [ ] WS-tunnel transport variant routes through the Worker, using the Worker hostname for SNI and TLS, the real target in a `X-Ripdpi-Upstream` header.
- [ ] At least one reference Worker script under `docs/native/cloudflare-workers/relay.js` that operators can deploy.
- [ ] Loopback test (against a mock HTTP/2 server) exercises the Worker-routed path.
- [ ] `docs/native/cloudflare-tunnel-operations.md` documents deployment, cost model, and rate-limit considerations.

## Risks / open questions

- Cloudflare Workers free tier has request and CPU-time limits; document operator-side cost expectations.
- A worker that proxies arbitrary bytes is a TOS edge case; the reference script should be narrow (WS upgrade + framed relay, no open-relay).

## Links

- [[audit-cloudflare-only-dependencies]] (done)
- [[relay-masque-status]]
