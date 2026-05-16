---
title: Add Xray profile UX and import flow
type: task
status: backlog
area: outbound
priority: medium
owner: unassigned
parent: epic-xray-provider-mode
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [ ] #task Add Xray profile UX and import flow #repo/RIPDPI #area/outbound #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-xray-profile-ux-and-import-flow`
- **Verify:** `just test-module core:data:runtime-state`
- **Scope (only modify these + this file + the ledger):** `core/data/runtime-state/**`, `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add the user-facing flow for selecting Xray VPN mode and importing or editing
initial Xray profiles.

## Motivation

tunneled outbound profile support needs to fit the existing Mode Editor, Settings, and
onboarding model without exposing low-level config trivia or secrets.

## Scope

- In scope: provider selection, profile import, validation errors, selected
route summary, onboarding validation, and localized copy.
- Out of scope: subscription management, server purchase/provisioning, and
multi-provider catalogs.

## Acceptance criteria

- [ ] Mode Editor can select Xray-backed VPN mode separately from native
    RIPDPI direct/proxy modes.
- [ ] Import supports at least the first approved share/config shapes and
    fails closed on unsupported or unsafe fields.
- [ ] Validation errors are actionable but redact credentials and endpoints.
- [ ] Onboarding can validate an Xray profile as the chosen mode before finish.
- [ ] Compose/UI tests cover selection, validation failure, and successful
    imported-profile state.

## Design notes

Use provider capability labels rather than protocol jargon wherever possible:
VPN privacy, relay, split/full tunnel, anti-DPI, and DNS protection.

## Risks / open questions

- Imported raw JSON can become an expert-only escape hatch; the first UX should
prefer typed forms and known share links.

## Links

- [[Epic - Xray provider mode]]
- [[Render validated Xray client configs]]
- [[ripdpi-android-xray-provider-plan-2026-04-24]]
