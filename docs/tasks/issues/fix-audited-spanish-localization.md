---
id: UIX-1791875091386561
title: Fix audited Spanish localization
kind: bug
status: doing
area: ui
priority: high
owner: Localization team
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-audited-spanish-localization
created: 2026-10-13
updated: 2026-10-13
---

## Goal

Spanish users can read every audited warning, action, and diagnostic result in the selected app language.

## Acceptance criteria

Resource and presentation regression tests cover all confirmed findings. All ten locales retain key, type, and format parity. App and service tests, lint, static analysis, architecture health, and locked Cargo metadata pass before main integration and push. Render and inspect affected Spanish UI without changing golden fixtures.

## Ownership

- Resource writer: every locale XML set, translation manifest, Spanish resource regression script, and provenance ledger in `RIPDPI-es-resources-20261013`.
- Runtime writer: DoQ, DoH bootstrap, BYOH, SNI and snapshot presentation plus their tests in `RIPDPI-es-runtime-20261013`.
- Formatter writer: diagnostic and service number formatters plus their tests in `RIPDPI-es-formatting-20261013`.
- Coordinator: planning, combined validation, visual evidence, review, and integration in `RIPDPI-es-localization-20261013`.
- Locale resources are serialized to the resource writer. Code writers supply key requests and never edit XML. Baselines, golden fixtures, dependencies, and protocol schemas are outside scope.
