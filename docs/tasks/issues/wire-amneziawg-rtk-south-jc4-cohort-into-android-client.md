---
title: "Wire AmneziaWG RTK South cohort (Jc=4) into Android client"
type: task
status: backlog
area: transport
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-22
updated: 2026-05-22
source_wiki_pages:
  - "[[wireguard-rtk-south-amneziawg-bypass]]"
linked_task: null
---

- [ ] #task Wire AmneziaWG RTK South cohort (Jc=4) into Android client #repo/RIPDPI #area/transport #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `wire-amneziawg-rtk-south-jc4-cohort-into-android-client`
- **Verify:** `TODO(verify): cargo test -p <transport-crate>`
- **Scope (only modify these + this file + the ledger):** TODO(scope): <module path(s) this task may modify>
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Motivation

Plain WireGuard at Rostelecom South (RTK юг, Rostov Oblast) experiences periodic 20–30 second interruptions every ~30 seconds — TSPU DPI identifies WireGuard via the deterministic 148-byte Initiation packet structure (4-byte type, 4-byte sender index, 32-byte ephemeral public key, 48-byte encrypted static key, 28-byte encrypted timestamp, 16-byte MAC1, 16-byte MAC2). AmneziaWG (AWG) randomizes this signature with junk/header/initialization parameters.

Community-tested working parameters at RTK South: `Jc=4 Jmin=10 Jmax=50 S1-4=0 H1=1 H2=2 H3=3 H4=4` plus per-deployment `I1-I5`. Connects successfully though sometimes requires 3–4 attempts (probabilistic passing — TSPU rule may be threshold-based rather than a hard block).

> [!info] Dedup notes
> No `ripdpi-amneziawg` crate exists in the workspace. Adjacent open issue `add-wireguard-over-websocket-transport-amneziawg-disguise.md` covers a DIFFERENT mechanism (WG-over-WebSocket disguise) — confirm distinct in PR.

## Proposed change

1. Add AmneziaWG client support to RIPDPI Android. If the existing WireGuard implementation lives in the Kotlin layer, extend it with AWG parameter fields (Jc/Jmin/Jmax/S/H/I); if it lives in a Rust crate or via sing-box wrapping, create or extend a corresponding `ripdpi-amneziawg` crate.
2. Per-cohort profile selector in proxy config — when the user connects via a profile tagged for RTK South (or similar AmneziaWG-required network), apply cohort-specific parameters from the deploy-side cohort YAML.
3. JNI/Kotlin diagnostic surface for AWG vs plain WG mode selection.
4. Probabilistic-retry logic: if AWG handshake fails, retry up to 4 attempts (per community report of 3–4 attempts to succeed).

### Linked deploy task

`linked_task:` points to `add-amneziawg-rtk-south-cohort` in deploy repo. Both must ship together — the cohort YAML defines server-side parameters that the client mirrors.

## Acceptance criteria

- [ ] AmneziaWG client support compiles for all 4 Android ABIs.
- [ ] Cohort profile import populates Jc/Jmin/Jmax/S/H/I from server-provided YAML or subscription URL.
- [ ] Smoke test against synthetic AWG endpoint with RTK South parameters succeeds.
- [ ] Probabilistic-retry logic implemented (max 4 attempts, configurable per-cohort).
- [ ] Dedup confirmed in PR description: distinct from `add-wireguard-over-websocket-transport-amneziawg-disguise`.

## Risks / open questions

- Whether RIPDPI already has Kotlin-layer WireGuard integration (sing-box ships WG; if RIPDPI wraps that, AWG support may already be partially available via sing-box config) — verify before writing a new crate.
- "Sometimes stalls on handshake requiring 3–4 connection attempts" — probabilistic; empirical retry-budget tuning per ISP may be needed.
- Jc=4 was measured at one RTK South vantage; other Rostov-region Rostelecom nodes may have different thresholds.

## References

- [[wireguard-rtk-south-amneziawg-bypass]] — wiki concept page with full parameter set
- Linked deploy task: `add-amneziawg-rtk-south-cohort`
- Related (different mechanism): existing issue `add-wireguard-over-websocket-transport-amneziawg-disguise`
