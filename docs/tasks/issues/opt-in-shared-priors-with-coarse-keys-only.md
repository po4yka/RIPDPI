---
title: Opt-in shared priors with coarse keys only
type: task
status: backlog
area: service
priority: medium
owner: unassigned
parent: epic-privacy-preserving-strategy-learner
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-20
---

- [ ] #task Opt-in shared priors with coarse keys only #repo/RIPDPI #area/service #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `opt-in-shared-priors-with-coarse-keys-only`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`, `native/rust/crates/ripdpi-shared-priors/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

If the user opts in, upload summaries that help future users on similar
networks. Hard constraints: no payloads, no raw URLs, no SSID, no precise
geolocation. Key only by coarse `(asn, access_type, dns_class, udp443_ok,
fail_phase)`.

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] §5 shared priors.

## Acceptance criteria

- [ ] Default: off. Opt-in is explicit and explained in the UI.
- [ ] Uploader enforces coarse-key schema at serialization time — any
    unexpected field is a build-time error, not a runtime filter.
- [ ] Upload batches are delayed and shuffled to avoid temporal
    correlation with user activity.
- [ ] Upload is subject to the same kill switch as any other non-essential
    network activity.
- [ ] Static analysis test asserts that the uploader module only depends
    on sanitized types — no path to leak URLs or SSIDs.

## Links

- [[Epic - Privacy-preserving strategy learner]]
- [[Limit DNS measurement to user-requested destinations]]
- [[Coarsen location-derived egress hints to regional buckets]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]


## qr-code-and-clipboard
