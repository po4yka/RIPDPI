---
id: RLY-1794300186609700
title: Fix SSH relay audit findings
kind: bug
status: doing
area: relay
priority: high
owner: SSH audit team
parent: null
blocked_by: []
spec_mode: required
openspec_change: ssh-relay-audit-fixes
created: 2026-11-10
updated: 2026-11-10
---

## Goal

Correct RLY-11 findings F1–F5 without adding an SSH provider or changing the saved profile schema.

## Acceptance criteria

- A changed host key fails the configured pin during rekey; an unchanged key works.
- Invalid imports do not change the active profile; unpinned imports require an explicit trust decision.
- A whitespace-only nonempty passphrase survives sharing.
- A stalled first discovery address does not prevent a later address from returning a key.
- Closing the SSH owner terminates work with an unread retained channel.
- Targeted regressions, independent review, and required source gates pass before integration.

## Parallel ownership

- Native writer: `native/rust/crates/ripdpi-ssh/**`; serialized ownership of `native/rust/Cargo.toml`, `native/rust/Cargo.lock`, and any SSH dependency patch. Separate commits for F1 and F5.
- Import writer: shared SSH admission and generic import/editor trust integration plus their tests. F2 only.
- Integration writer: share mapper and tests (F3); host-key observer and tests (F4); integration and final validation.
- Planning writer: this task, generated board, and `openspec/changes/ssh-relay-audit-fixes/**`.
- No writer owns locale or baseline changes. Escalate any required overlap before editing.
