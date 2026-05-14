---
title: Add randomized port-hopping window to Hysteria2 outbound
type: task
status: done
area: transport
priority: medium
owner: unassigned
parent: epic-composable-transport-layer-parity
blocks: []
blocked_by: []
created: 2026-04-25
updated: 2026-05-14
---

- [x] #task Add randomized port-hopping window to Hysteria2 outbound #repo/RIPDPI #area/transport #status/done 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-randomized-port-hopping-window-to-hysteria2-outbound`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-hysteria2`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-hysteria2/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Hysteria 2.8.0 introduced `minHopInterval` / `maxHopInterval` so port-hopping intervals can be randomized within a configured window instead of using a fixed cadence. Adopt the same fields in RIPDPI's Hysteria2 outbound config so the per-session hopping interval becomes unpredictable to interval-based DPI traffic classifiers.

## Research citation

[[ripdpi-android-research-2026-04-25]] §Upstream transport engines — Hysteria app/v2.8.0 (2026-03-30) added `minHopInterval` / `maxHopInterval` for randomized port-hopping (vs the previous fixed interval), reducing predictability for DPI traffic classifiers. Same release added selectable congestion control (3 BBR profiles + Reno) and server-side UDP port-range listening with auto nftables/iptables rule injection.

## Acceptance criteria

- [x] Outbound config schema gains `minHopInterval` / `maxHopInterval` fields per Hysteria 2.8.0
- [x] Runtime randomizes hop interval per session within the configured window
- [x] Telemetry surfaces actual interval distribution per session for verification
- [x] Backward compatibility: omitted fields fall back to existing fixed-interval behavior

## Work log

- 2026-05-14 — Added `hop_interval` / `min_hop_interval` / `max_hop_interval` (`Option<Duration>`)
  to `ripdpi_hysteria2::Config`, parsed from the `hopInterval` / `minHopInterval` /
  `maxHopInterval` URL query params (whole seconds, matching the Hysteria CLI units;
  non-numeric or zero values rejected as `InvalidAddress`). Omitted fields default to
  `None`, so the existing `Config::from_url` callers in `ripdpi-relay-core` compile and
  behave unchanged.
- New `port_hopping` module: `PortHoppingWindow` (`Disabled` / `Fixed` / `Randomized`)
  resolved via `Config::port_hopping_window()`. `next_interval(rng)` draws a fresh
  uniform sample inside the inclusive `[min, max]` window per call (nanosecond
  precision, honors sub-second windows; reversed bounds swapped). Backward compatibility:
  with no `min`/`max` window it degrades to the fixed `hopInterval` cadence, or no hopping.
- `HopIntervalTelemetry` accumulates per-session count / min / max / mean / spread so the
  realized interval distribution can be surfaced for verification (spread is non-zero for
  a randomized window, zero for a fixed cadence). Both new types are re-exported from the
  crate root.
- TDD: 19 new tests (`port_hopping::tests` + `config::tests`) written first. Verify:
  `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-hysteria2` → 50
  passed, exit 0. `cargo clippy --manifest-path native/rust/Cargo.toml --workspace
  --no-deps --all-targets -- -D warnings` → exit 0. `cargo fmt --manifest-path
  native/rust/Cargo.toml --all` → exit 0. `cargo check --manifest-path
  native/rust/Cargo.toml --workspace` → exit 0.

## Links

- Project: [[ripdpi-android]]
- Epic: [[Epic - Composable transport layer parity]]
- Research: [[ripdpi-android-research-2026-04-25]] §Upstream transport engines
