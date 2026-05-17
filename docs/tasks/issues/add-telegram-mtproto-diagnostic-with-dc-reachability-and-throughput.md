---
title: Add Telegram MTProto diagnostic with DC reachability and throughput
type: task
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-25
updated: 2026-04-25
---

- [ ] #task Add Telegram MTProto diagnostic with DC reachability and throughput #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-telegram-mtproto-diagnostic-with-dc-reachability-and-throughput`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-diagnostics-telegram`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-diagnostics-telegram/**`, `native/rust/crates/ripdpi-ws-tunnel/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

A diagnostic profile that probes Telegram MTProto reachability and throughput from the current network: per-DC TCP ping across all known Telegram datacenters, plus a transient upload/download throughput run against a Telegram-owned endpoint with stall/slowdown classification.

## Motivation

dpi-detector's Test 6 ("Telegram") fills a gap that RIPDPI's current diagnostics surface does not cover: it answers "is Telegram itself reachable on this network, and at what speed?" — independent of the WS tunnel relay path. RIPDPI already ships the WS tunnel (`skill: ws-tunnel-telegram`), but a diagnostic that quantifies the underlying transparent-Telegram baseline tells the user whether the tunnel is even necessary on the current network and gives a concrete throughput delta when it is.

## Scope

- **In scope:** new diagnostic profile in `ripdpi-monitor` that enumerates Telegram DCs, performs a TCP-connect reachability probe per DC, and runs a short bidirectional throughput measurement against one healthy DC. Result class includes `ok`, `slow`, `stalled`, `blocked`, with timing and byte counts for both directions. Result surfaces as a Diagnostics screen card and an export-bundle entry.
- **Out of scope:** any change to the WS tunnel relay path; persistent speed history; payload-level MTProto correctness (this is a transport reachability + throughput probe, not a protocol conformance test).

## Acceptance criteria

- [ ] DC IP database from `ripdpi-ws-tunnel` (`dc_from_ip` / `TelegramDc`) is reused — no second source of truth.
- [ ] Per-DC reachability probe reports `reachable: bool` plus median RTT for ports 443 and 80.
- [ ] Throughput probe runs for a bounded wall-clock budget (default 10s up, 10s down) and reports avg bps + total bytes per direction.
- [ ] Stall detection: `stalled` if a transfer hits zero progress for ≥3s mid-run; `slow` if avg bps falls below a configurable floor.
- [ ] Result surfaces in the diagnostics summary card and is included in `summary.txt` / `report.json` export bundle entries.
- [ ] No payload data, IDs, or auth keys are logged or exported.
- [ ] Probe is gated behind an explicit user toggle in the diagnostics profile picker — never runs automatically.

## Design notes

Reuse the existing TCP probe primitives in `ripdpi-monitor`. The throughput measurement should select a DC from the reachable set; if none reachable, return `blocked` with the reachability matrix and skip the throughput stage. Honor VPN socket protection when running while the tunnel is active so the probe measures the correct path.

## Source reference

dpi-detector v3.2.2: `core/telegram_scanner.py` — `_check_dc`, `_run_upload`, `_run_download`, `run_telegram_test`. The status taxonomy (`ok` / `stalled` / `slow` / `blocked`) and the upload/download asymmetry are taken directly from there.

## Risks / open questions

- Endpoint selection for upload: dpi-detector uses Telegram CDN endpoints; confirm that the chosen endpoints are operationally acceptable to probe and not rate-limited.
- "Slow" floor: 250 kbps is a defensible default but should be a configuration knob with sane regional defaults rather than a hard-coded constant.

## Links

- [[ripdpi-android]]
