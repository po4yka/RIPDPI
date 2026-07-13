---
title: Trim remembered network policies
type: task
status: doing
area: diagnostics
priority: medium
owner: Codex data lane
parent: epic-close-remaining-android-audit-risks
blocks: []
blocked_by: []
created: 2026-07-13
updated: 2026-07-13
---

## Goal

Apply configured diagnostics retention to remembered-network policies and their privacy-sensitive identifiers.

## Acceptance criteria

- [ ] Seeded expired policies are removed while fresh policies remain.
- [ ] The scheduled retention worker exercises this store through the production trim contract.
