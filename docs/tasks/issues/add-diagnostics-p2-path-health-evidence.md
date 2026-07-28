---
title: Add diagnostics P2 path health evidence
type: task
status: doing
area: diagnostics
priority: high
owner: Codex diagnostics P2 coordinator
parent: null
blocks: []
blocked_by: []
created: 2026-07-29
updated: 2026-07-29
---

## Goal

Make opt-in diagnostics distinguish path-MTU, IPv6/NAT64, background-survival,
and runtime root-cause failures using bounded evidence from the real VPN data
plane.

## Scope and ownership

1. **Path MTU and IPv6 lane** — one serialized writer owns the active UDP
   payload ladder, IPv4/IPv6/NAT64 reachability, PTB availability, transport
   overhead projection, typed verdict, and focused tests.
2. **Background-survival lane** — one serialized writer owns guided screen-off
   evidence, before/during/after data-plane deltas, process-death-safe partial
   evidence, and public-API-only Samsung classification.
3. **Runtime verdict lane** — one serialized writer owns deterministic
   correlation of P1 device, exit, network-transition, and data-plane events
   into a versioned root-cause assessment.
4. **UI and locale lane** — one serialized writer owns any user-facing opt-in
   controls, all locale files, and translation-export parity.
5. **Verifier lane** — a read-only reviewer checks privacy, lifecycle,
   concurrency, measured-versus-inferred claims, and the rebased combined tree.

## Boundaries

- No hidden Android or Samsung APIs and no claim that Sleeping Apps membership
  was verified. Unsupported evidence is reported as unavailable.
- No serial, Android ID, IMEI, IP, DNS address, interface name, SSID/BSSID,
  hostname, endpoint, profile secret, or raw exception text.
- A smaller successful payload followed by repeated larger failures is required
  for `MTU_BLACKHOLE`; missing controls or unsupported PTB observation remains
  inconclusive.
- RAW/direct evidence and in-VPN application-payload evidence are labeled
  separately. SOCKS evidence must not be presented as an authoritative
  underlay PMTU measurement.
- Existing host-only PMTUD fixtures remain test evidence, not physical Android
  evidence.
- Do not change Room, diagnostics wire/schema, archive goldens, or quality
  baselines unless the implementation proves that boundary unavoidable and
  explicit approval is obtained first.
- Preserve the unfinished P0 worktree and all unrelated worktrees.

## Acceptance

- Each functional slice is an atomic Conventional Commit with focused
  regression tests and an independent read-only review.
- Collections and timelines are bounded, cancel-safe, privacy-safe, and scoped
  to the correct connection/run generation.
- The automatic verdict never changes user configuration and fails closed to
  `INCONCLUSIVE` for absent or contradictory evidence.
- Kotlin/Rust unit tests, locale and translation-export parity, static analysis
  parity, architecture health, locked Cargo metadata, and the local PMTUD
  evidence contracts are checked on the combined tree.

## Work log

- 2026-07-29: Reconstructed P2 scope from the diagnostics review. Confirmed the
  existing PMTUD worktree is a clean ancestor whose two commits are already in
  `main`; production device-level PMTU evidence remains absent.
