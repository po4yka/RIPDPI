---
id: RST-1790419862638443
title: Correct native contract and configuration audit defects
kind: bug
status: review
area: rust-native
priority: high
owner: Native Rust
parent: null
blocked_by: []
spec_mode: required
openspec_change: correct-native-contract-config-audit-defects
created: 2026-09-26
updated: 2026-09-26
---

## Goal

Correct confirmed native contract and configuration defects found in the ten-crate audit. This worktree owns the Rust files named in the linked OpenSpec design and is the only writer for them.

## Acceptance criteria

- Invalid explicit configuration is rejected with a field-specific error.
- Public proxy listeners enforce authentication on all entry points; SOCKS4 cannot bypass token protection.
- HTTP diagnostics reject truncated or ambiguous framing, decode chunked bodies, and respect bodyless responses.
- Strategy reload, gauge snapshots, recorder installation, and known Telegram DC classification return updated or accurate values.
- Affected Cargo tests and native architecture checks pass; changes are committed on main.
