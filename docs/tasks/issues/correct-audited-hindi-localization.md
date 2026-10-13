---
id: UIX-1791880509070975
title: Correct audited Hindi localization
kind: bug
status: doing
area: ui
priority: high
owner: Localization maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-audited-hindi-localization
created: 2026-10-13
updated: 2026-10-13
---

## Goal

Correct the confirmed Hindi localization findings from the audit at 17172d4a1fe3bfc02faf6366efad868da2ad3dce. Preserve all ten locales and exact technical values.

## Acceptance criteria

- Correct 63 active resource keys and seven unused keys. Leave the three disputed DNS terms unchanged.
- Correct 42 stale profile-editor references in English, German, French, Persian, Arabic, and Brazilian Portuguese. Keep the already correct references in other locales.
- Translate 46 descriptive array labels, two settings literals, and 21 dynamic diagnostic and host-pack texts.
- Use the selected app language for host-pack and strategy-pack dates after a language change.
- Refresh known PT display text after a language change without another probe. Preserve raw errors and custom model text.
- Preserve array order, wire values, protocol names, unknown evidence, format arguments, and all locale keys.
- Read numeric generic array values without null entries; preserve the original values zero through three.
- Pass resource regressions, app and service unit tests, static analysis, lint, architecture health, and task contracts. Inspect Hindi renders without changing goldens.

## Ownership

- Resource writer: isolated hi-resources worktree; all locale XML, source arrays, and translation manifest. This is the sole writer for every locale resource set.
- Runtime writer: isolated hi-runtime worktree; DPI, PT, and TCP16 Kotlin presentation and tests. Send new key requests to the resource writer.
- Settings writer: isolated hi-settings worktree; host-pack presentation, catalog date formatting, two fake-sequence labels, and tests. Send new key requests to the resource writer.
- Integration writer: hindi-localization-20261013 worktree; task artifacts, resource guard scripts, combined-tree validation, provenance, independent review, and main integration.
- No shared locale writer runs in parallel. Other writers do not edit XML or the manifest.
