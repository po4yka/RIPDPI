---
id: AND-1792945974437056
title: Import subscriptions, WireGuard/AmneziaWG configs and AmneziaVPN keys from every in-app surface
kind: feature
status: done
area: android
priority: high
owner: Integration owner
parent: null
blocked_by: []
spec_mode: required
openspec_change: in-app-import-surfaces
created: 2026-10-25
updated: 2026-10-27
closed_at: "2026-10-27T09:23:20Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Requirements REQ-IMPORT-PARITY/SUBSCRIPTION/CONF/SECRETS passed in local tests; hosted CI, CodeQL, fleet-fixtures and Secret Scan passed on origin/main 3959b79bdae646fcfe9286f6b28c27df72f9519d (runs 40391039518, 40391039493, 40391039559, 40391039870).
---

## Goal

Users import any supported proxy link, subscription link, WireGuard / AmneziaWG config or AmneziaVPN key from the QR scanner, Paste link, the share sheet or a picked file, and each surface behaves the same.

## Acceptance criteria

- The four requirements in `openspec/changes/in-app-import-surfaces/specs/in-app-import-surfaces/spec.md` have passing local tests.
- `:app:testGithubFullDebugUnitTest`, app and service lint, detekt, ktlint and architecture health pass on the integrated commit.
- README files and import docs describe the shipped surfaces only.
