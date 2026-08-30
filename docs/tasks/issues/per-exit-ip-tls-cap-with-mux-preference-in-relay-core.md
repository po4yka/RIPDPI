---
id: TRN-1786264762917184
title: Per-exit-IP TLS cap with true mux-preference in relay-core backend
kind: feature
status: doing
area: transport
priority: medium
owner: codex
parent: null
blocked_by: []
spec_mode: required
openspec_change: trn-1786264762917184-per-exit-ip-tls-cap-with-mux-preference-in-relay-core
created: 2026-06-11
updated: 2026-08-30
source_wiki_pages:
  - tls-policing-home-isps
linked_task: null
---

## Motivation

The per-exit-IP concurrent-TLS cap (`ExitIpSessionLimiter`, `ripdpi-proxy-runtime/src/exit_ip_cap.rs`) was wired into `ripdpi-proxy-runtime`'s outbound connect path as an admission **gate with route-preference on cap** (skip an at-cap exit-IP candidate for an alternate; advisory fall-through when all are capped). That closed the originally-filed task.

A source audit during that work surfaced an architecture finding worth a follow-up: **`ripdpi-proxy-runtime` is the direct-desync path** — it opens raw `TcpStream`s straight to destinations and cannot multiplex (there is no mux protocol on a direct connection to an arbitrary TLS server), so "mux-preference reuse" is not implementable there. The threat the cap actually targets — ~12 simultaneous TLS sessions to a single foreign **VLESS+Reality+Vision exit IP** — is established on the **`ripdpi-relay-core`** path, which is a separate crate that `proxy-runtime` does not depend on and which **already has a mux pool** (`ripdpi-relay-mux` `RelayMux` / `RelaySessionFactory`, consumed by `relay-core/src/backend/pool.rs` and every protocol backend).

So a per-exit-IP TLS cap with *true* mux-preference (the 9th stream reuses an existing muxed session instead of opening a new TLS session) belongs in `relay-core`'s backend pool, where the foreign-exit sessions and the mux machinery already live.

## Proposed change

1. Add (or reuse) a per-`(exit_ip, transport)` concurrent-session cap in `ripdpi-relay-core/src/backend/pool.rs` (or a shared primitive both crates use) keyed by the real foreign exit IP of the VLESS+Reality+Vision backend.
2. For mux-enabled profiles, prefer `RelayMux::open_stream` onto the existing compatible session for that exit (true mux-preference — the machinery already exists) rather than opening a new outbound TLS session. For non-mux profiles, reject a ninth physical carrier until a slot is released.
3. Default cap 8 for `vless_reality` on port 443; per-transport overrides (mirror `ExitIpSessionCaps`).
4. Decide whether `ExitIpSessionLimiter` should be promoted to a shared crate consumed by both `proxy-runtime` (direct-path gate) and `relay-core` (relay-path mux-preference), or duplicated. Avoid two diverging caps.
5. Keep the original optional near-cap diagnostics/UI item outside this task's acceptance scope; it requires a separate telemetry and UI contract.

## Acceptance criteria

- [ ] Per-exit-IP concurrent-session cap enforced on the `relay-core` foreign-exit path (the path that actually opens VLESS+Reality+Vision TLS sessions).
- [ ] Nine concurrent logical streams on a mux-enabled backend reuse one physical carrier via `RelayMux::open_stream`; a ninth non-mux carrier is rejected at cap.
- [ ] No double-counting between the `proxy-runtime` direct-path gate and the `relay-core` cap.
- [ ] `cargo nextest run -p ripdpi-relay-core -p ripdpi-relay-mux --locked` green; clippy clean; `pr-reviewer` pass (hot path).

## References

- `tls-policing-home-isps` — wiki concept page (mechanism + workarounds).
- `ripdpi-proxy-runtime/src/exit_ip_cap.rs` — the existing accounting primitive + direct-path gate.
- `ripdpi-relay-mux` (`RelayMux`, `RelaySessionFactory`), `ripdpi-relay-core/src/backend/pool.rs` — the existing mux pool this should build on.
- Architecture finding: `proxy-runtime` (direct desync, non-muxable) vs `relay-core` (foreign-exit relay, already muxed) are independent outbound subsystems.
