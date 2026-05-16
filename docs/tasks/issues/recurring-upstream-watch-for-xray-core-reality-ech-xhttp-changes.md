---
title: Recurring upstream watch for xray-core REALITY ECH XHTTP changes
type: task
status: done
area: engine
priority: medium
owner: unassigned
parent: epic-control-plane-hardening
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-16
---

- [x] #task Recurring upstream watch for xray-core REALITY ECH XHTTP changes #repo/RIPDPI #area/engine #status/done 🔼

## Work log

- 2026-05-16: Watch cadence documented at
  `core/engine/docs/upstream-watch-xray-core-2026-05-16.md` —
  weekly skim + per-release deep-read; source list ordered by
  priority (GitHub releases, commits filtered to four path globs,
  pinned discussions, downstream sing-box tracker); rotating owner
  + 48 h triage SLA; append-only watch log seeded with the known
  breakages (`v1.26.1.18` XHTTP+REALITY, `v1.26.0601` VLESS flow
  deprecation). The host-pack validator rejection rule shape is
  documented (hard-reject / warn-only / compat-shim), but the
  validator itself lives in `core/data/catalog/XrayConfigValidator.kt`
  outside this row's `core/engine/**` scope; the validator extension
  is filed as a follow-up issue.

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `recurring-upstream-watch-for-xray-core-reality-ech-xhttp-changes`
- **Verify:** `just test-module core:engine`
- **Scope (only modify these + this file + the ledger):** `core/engine/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Document a recurring xray-core release-watch cadence and extend the
host-pack validator to reject deprecated configurations (e.g., VLESS
without flow) before they ship to clients.

## Research citation

[[ripdpi-android-research-2026-04-20]] §Upstream transport engines —
xray-core is on a fast release cadence (v1.260206.0 most recent; VLESS-
without-flow deprecation + `allowInsecure` auto-disable at 2026-06-01 +
XHTTP+REALITY breakage at v26.1.18). A silent breakage here sinks the
control plane; catching it at host-pack publish time is cheapest.

## Acceptance criteria

- [ ] Cadence and source list for xray-core release watch documented
    (release page, changelog, discussion tracker).
- [ ] Host-pack validator rejects deprecated flow values and any known
    broken combinations pre-publish.
- [ ] Owner and review interval (weekly or per-release) set in the
    chore body and linked from [[Epic - Control-plane hardening]].

## Links

- [[Epic - Control-plane hardening]]
- [[Sign host-pack manifests with app-trusted keys]]
- [[Add anti-rollback to strategy-pack updates]]
- [[ripdpi-android-research-2026-04-20]]


## direct-mode-diagnostic-state
