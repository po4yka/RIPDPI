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
updated: 2026-06-10
---

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

- **Reconcile direction with `epic-remove-cloudflare-from-critical-path` before starting.** That epic removes Cloudflare as a *mandatory* bootstrap/delivery dependency; this task adds Cloudflare Workers as an *optional, operator-supplied* fronting transport. They are compatible only if this stays strictly opt-in and never becomes a default critical-path hop. Confirm that framing in the PR; if it cannot stay optional, drop this task.
- Cloudflare Workers free tier has request and CPU-time limits; document operator-side cost expectations.
- A worker that proxies arbitrary bytes is a TOS edge case; the reference script should be narrow (WS upgrade + framed relay, no open-relay).

## Links

- audit-cloudflare-only-dependencies (closed task; done)
- `native/rust/crates/ripdpi-masque/CONFORMANCE.md`

## Work log

- 2026-06-05: No implementation exists — no Workers URL/auth schema fields, no WS-tunnel Workers variant, no `docs/native/cloudflare-workers/` dir or `relay.js`, no loopback test, and `docs/native/cloudflare-tunnel-operations.md` covers cloudflare_tunnel mode only (not Workers fronting). All 5 acceptance criteria remain unstarted.
