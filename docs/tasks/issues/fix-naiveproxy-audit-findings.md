---
id: RLY-1794300094981437
title: Fix NaiveProxy audit findings
kind: bug
status: doing
area: relay
priority: high
owner: RLY-12 repair coordinator
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-naiveproxy-audit-findings
created: 2026-11-10
updated: 2026-11-10
---

## Goal

Fix audit findings F1–F4 for NaiveProxy address selection, health, Basic credentials, and owning documentation.

## Acceptance criteria

- Keep the encrypted resolver address order in the Chromium MAP rule.
- Classify proxy TLS, CONNECT, H2, and QUIC failures as degraded health.
- Reject colon in upstream Basic usernames and ASCII controls in both credential fields at import, editor, service, and helper boundaries.
- Keep empty passwords and colons in passwords valid.
- Make runtime and local-auth documentation match the code.
- Pass targeted tests, static checks, CI source gates, and independent review.

## Parallel ownership

- Address worker: NaiveUpstreamLauncher.kt and NaiveUpstreamLauncherTest.kt.
- Health worker: NaiveUpstreamOutputParser.kt, NaiveUpstreamErrorCodes.kt, their tests, and existing subprocess health transition tests.
- Credential worker: runtime-state credential validation and URI codec, editor validation, service credential validation, Rust helper config, and their owning tests.
- Coordinator: portfolio/OpenSpec records, documentation, integration, combined validation, and commits.
- Each writer uses its own worktree. Workers do not edit planning files. No locale, lockfile, golden, baseline, descriptor, or schema changes are planned.
- Each confirmed finding gets a separate Conventional Commit. Only the coordinator commits and pushes the combined result.
