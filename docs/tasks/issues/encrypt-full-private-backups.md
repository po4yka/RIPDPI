---
title: Encrypt full private backups
type: task
status: doing
area: data
priority: high
owner: Codex data lane
parent: epic-close-remaining-android-audit-risks
blocks: []
blocked_by: []
created: 2026-07-13
updated: 2026-07-13
---

## Goal

Ensure FULL exports containing credentials or private keys are authenticated ciphertext rather than plaintext JSON.

## Acceptance criteria

- [ ] Serialized FULL output does not expose known seeded secrets.
- [ ] Restore rejects tampering and wrong credentials while valid encrypted round trips succeed.
- [ ] Non-private backup compatibility remains explicit and tested.
