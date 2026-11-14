---
id: RST-1794677654202696
title: Reject invalid IPv6 extension configurations
kind: bug
status: review
area: rust-native
priority: medium
owner: IPv6 validation team
parent: null
blocked_by: []
spec_mode: required
openspec_change: rst-1794677654202696-reject-invalid-ipv6-extension-configurations
created: 2026-11-14
updated: 2026-11-14
---

## Goal

Reject invalid IPv6 extension inputs.

## Acceptance criteria

- Reject routing without malformed output.
- Reject invalid explicit ext_type.
- Reject profiles on non-ipfrag2_udp steps.
- Pass tests, review and source gates.

## Ownership

- IPv6 worker owns strategy-ipv6, strategy-config and related TUN tests/docs.
- UDP worker owns proxy-config/tests in separate worktree.
- Planning worker owns task/OpenSpec docs and generated board; parent owns combined gates/integration.
- No shared lockfile, schema, locale, golden fixture, baseline or registry edits. Writers wait for strict planning validation.
