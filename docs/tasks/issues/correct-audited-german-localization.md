---
id: UIX-1791912007411833
title: Correct audited German localization
kind: bug
status: review
area: ui
priority: high
owner: Localization maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-audited-german-localization
created: 2026-10-13
updated: 2026-10-14
status_detail: Published all main commits with explicit authorization. Combined checks passed; hosted CI is in progress and the real Simple bundle remains absent.
---

## Goal

Correct the confirmed German audit findings at ddd0024d494fb2e734223b8c6e77ec239a255bbe. Preserve every locale and raw technical contract.

## Acceptance criteria

- Correct 107 active and five unused German resource entries. Exclude disputed Authority/Tail wording and optional editorial changes.
- Localize Telegram summary badges and readable share-summary prose. Preserve raw statuses, unknown evidence, and technical identifiers.
- Use the selected app locale for Telegram and sparkline numbers, including after a language switch.
- Interpret Telegram native throughput as bytes per second and display correct bit-rate values without overflow.
- Add each new string key in all ten locales in the same source commit.
- Pass resource regressions, app unit tests, static analysis, app/service lint, architecture and task contracts. Inspect German renders and verify existing screenshots.
- Keep missing Simple relay credentials and hosted CI distinct from local validation. Never use placeholder relay profiles as completion evidence.

## Ownership

- Resource writer: isolated de-resources worktree; sole writer of all locale XML and translation manifest. Correct German entries and translate agreed new keys in all locales.
- Runtime writer: isolated de-runtime worktree; Telegram captions, selected-locale formatting, readable share summary, core/diagnostics DiagnosticsSummaryTextRenderer.kt heading parameters and their tests. Request XML keys from the resource writer.
- Validation writer: isolated de-validation worktree; German resource regression script and Kotlin resource/render tests. Do not edit production Kotlin or resources.
- Integration writer: german-localization-20261013 worktree; OpenSpec/task artifacts, CI wiring, provenance, combined validation, review and authorized main integration/push.
- All writers preserve other agents' changes. No parallel writers share locale resources, baselines, manifests or fixtures. No golden refresh is authorized for this change.
