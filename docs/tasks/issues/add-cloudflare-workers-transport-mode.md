---
id: RST-1786264762917044
title: Add optional Cloudflare Workers transport mode
kind: feature
status: backlog
area: rust-native
priority: low
risk: high
owner: Relay transport maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: rst-1786264762917044-add-cloudflare-workers-transport-mode
created: 2026-05-16
updated: 2026-08-09
---

## Summary

Add an optional operator-supplied Cloudflare Workers transport mode. The outer TLS metadata uses the Worker hostname, and the Worker forwards an authenticated framed stream to an operator-configured upstream.

## Context

Cloudflare Workers terminate TLS at Cloudflare's edge and route HTTP requests to operator-defined backends. Combined with WebSocket upgrade and a small Worker script, this provides the same Worker-hosted transport shape used by the operator's own Workers traffic.

RIPDPI already has `ripdpi-cloudflare-origin` and Cloudflare-direct MASQUE; this task adds the optional *Workers transport* deployment mode where the Worker hostname is the SNI and the operator-configured upstream is carried in an authenticated header.

## Acceptance criteria

- [ ] Operator-supplied Worker URL + auth bearer is consumable via `core:data:model` typed schema.
- [ ] WS-tunnel transport variant routes through the Worker, using the Worker hostname for SNI and TLS, the real target in a `X-Ripdpi-Upstream` header.
- [ ] At least one reference Worker script under `docs/native/cloudflare-workers/relay.js` that operators can deploy.
- [ ] Loopback test (against a mock HTTP/2 server) exercises the Worker-routed path.
- [ ] `docs/native/cloudflare-tunnel-operations.md` documents deployment, cost model, and rate-limit considerations.

## Risks / open questions

- **Reconcile direction with `epic-remove-cloudflare-from-critical-path` before starting.** That epic removes Cloudflare as a *mandatory* bootstrap/delivery dependency; this task adds Cloudflare Workers as an *optional, operator-supplied* transport mode. They are compatible only if this stays strictly opt-in and never becomes a default critical-path hop. Confirm that framing in the PR; if it cannot stay optional, drop this task.
- Cloudflare Workers free tier has request and CPU-time limits; document operator-side cost expectations.
- A worker that proxies arbitrary bytes is a TOS edge case; the reference script should be narrow (WS upgrade + framed relay, no open-relay).

## Links

- audit-cloudflare-only-dependencies (closed task; done)
- `native/rust/crates/ripdpi-masque/CONFORMANCE.md`

## Work log

- 2026-06-05: No implementation exists — no Workers URL/auth schema fields, no WS-tunnel Workers variant, no `docs/native/cloudflare-workers/` dir or `relay.js`, no loopback test, and `docs/native/cloudflare-tunnel-operations.md` covers cloudflare_tunnel mode only (not the optional Workers transport). All 5 acceptance criteria remain unstarted.
