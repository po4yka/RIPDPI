---
title: Limit DNS measurement to user-requested destinations
type: task
status: backlog
area: dns
priority: medium
owner: unassigned
parent: epic-encrypted-dns-and-https-svcb-classifier
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-14
---

- [ ] #task Limit DNS measurement to user-requested destinations #repo/RIPDPI #area/dns #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `limit-dns-measurement-to-user-requested-destinations`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Measure DNS only for destinations the user is actually trying to reach. No preloaded target lists, no broad scanning. Matches the C-Saw measurement-with-consent posture.

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] §2 final operational note.

## Acceptance criteria

- [ ] No code path exists that scans a preloaded domain list.
- [ ] Measurement is always tied to a live flow request.
- [ ] If measurement results are uploaded later (see shared priors), they carry only coarse keys — no raw user URLs, no SSIDs, no precise geolocation.
- [ ] Review documented so future contributors don't accidentally add background probing.

## Links

- [[Epic - Encrypted DNS and HTTPS SVCB classifier]]
- [[Opt-in shared priors with coarse keys only]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]


## extended-outbound-protocol-support
