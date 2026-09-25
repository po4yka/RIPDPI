---
id: RLY-1790340340749310
title: Expose complete relay kinds in Mode Editor
kind: bug
status: doing
area: relay
priority: medium
owner: relay editor
parent: null
blocked_by: []
spec_mode: required
openspec_change: expose-relay-mode-editor-kinds
created: 2026-09-25
updated: 2026-09-25
---

## Goal

Allow users to create and edit the six existing relay kinds missing from Mode Editor without losing imported settings or credentials.

## Acceptance criteria

- VLESS TLS/xHTTP, Trojan, Shadowsocks, Google Apps Script, Mieru, and SSH have complete editable fields and validation.
- Opening, changing, and saving imported profiles retains every untouched field and secret, with existing identity and concurrent-edit protection intact.
- Relevant app tests and lint pass.

## Ownership

The relay editor agent owns Mode Editor relay fields/actions, ConfigDraft relay mapping, persistence and validation, relevant tests, and this task/OpenSpec change in its isolated worktree. Other P2 agents own separate worktrees. Shared locale resources and generated board are serialized during integration.
