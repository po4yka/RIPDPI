---
id: AND-1792945974437056
title: Import subscriptions, WireGuard/AmneziaWG configs and AmneziaVPN keys from every in-app surface
kind: feature
status: doing
area: android
priority: high
owner: Integration owner
parent: null
blocked_by: []
spec_mode: required
openspec_change: in-app-import-surfaces
created: 2026-10-25
updated: 2026-10-25
---

## Goal

Users import any supported proxy link, subscription link, WireGuard / AmneziaWG config or AmneziaVPN key from the QR scanner, Paste link, the share sheet or a picked file, and each surface behaves the same.

## Acceptance criteria

- The four requirements in `openspec/changes/in-app-import-surfaces/specs/in-app-import-surfaces/spec.md` have passing local tests.
- `:app:testGithubFullDebugUnitTest`, app and service lint, detekt, ktlint and architecture health pass on the integrated commit.
- README files and import docs describe the shipped surfaces only.
