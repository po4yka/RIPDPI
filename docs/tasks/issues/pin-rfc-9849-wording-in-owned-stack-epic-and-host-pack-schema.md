---
title: Pin RFC 9849 wording in owned-stack epic and host-pack schema
type: task
status: done
area: diagnostics
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-16
---

- [x] #task Pin RFC 9849 wording in owned-stack epic and host-pack schema #repo/RIPDPI #area/diagnostics #status/done 🔼

## Work log

- 2026-05-16: Updated the in-scope ECH spike doc to cite RFC 9849 in
  place of `draft-ietf-tls-esni-18`, noting that the entire
  `draft-ietf-tls-esni-*` series is superseded. Recorded that Conscrypt's
  `setEchConfigList` / `getEchAccepted` / `setEchRetryConfigs` method
  names already align with the stable RFC 9849 vocabulary
  (`ECHConfigList`, `ech_accept_signal`, `retry_configs`) so no rename
  is required. `core/data/model/` carries no ESNI/ECH textual references.
  The host-pack catalog (`core/data/catalog/HostPackCatalog.kt`) holds
  no draft-version string; its ECH fields are byte-blob carriers, which
  remain valid under RFC 9849.

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `pin-rfc-9849-wording-in-owned-stack-epic-and-host-pack-schema`
- **Verify:** `just lint`
- **Scope (only modify these + this file + the ledger):** `core/diagnostics/**`, `core/data/model/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Replace draft-ietf-tls-esni-25 references with RFC 9849 across the owned-
stack epic and the host-pack schema, and verify Conscrypt ECH API names
against the stable RFC vocabulary.

## Research citation

[[ripdpi-android-research-2026-04-20]] §Standards and protocol activity —
RFC 9849 was ratified in 2026; existing RIPDPI documents still cite the
draft. Bumping the reference prevents future schema reviewers from
chasing a superseded draft.

## Acceptance criteria

- [ ] Epic body and host-pack schema reference RFC 9849, not
    draft-ietf-tls-esni-25.
- [ ] Conscrypt ECH API names in code comments and docs verified against
    the stable RFC vocabulary.
- [ ] Decision-block citation list on [[Epic - Owned-stack mode with Android 17 ECH]]
    updated accordingly.

## Links

- [[Epic - Owned-stack mode with Android 17 ECH]]
- [[Parse HTTPS SVCB records with ECH config metadata]]
- [[ripdpi-android-research-2026-04-20]]
