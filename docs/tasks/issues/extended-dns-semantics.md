---
id: DGN-1791527373647141
title: Preserve extended DNS response semantics in diagnostics
kind: feature
status: review
area: diagnostics
priority: high
owner: dns-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: extended-dns-semantics
created: 2026-10-09
updated: 2026-10-09
status_detail: Implementation and local gates passed; hosted CI and device acceptance remain required.
---

## Goal

Preserve and explain DNS response facts for each observed source in scans, DNS tools, history and local exports. Keep resolver differences separate from proof of provider interference.

## Acceptance criteria

- Bind DNS packets to their query and preserve answer, negative, truncated, malformed and transport outcomes with bounded response metadata.
- Show response code, CNAME, TTL, negative TTL, DNS flags, SOA presence and numeric EDE codes in current and historical results in all ten locales.
- Read legacy reports without invented evidence; keep hostname and free-text data out of redacted exports.
- Pass native parser/runner/contract tests, Kotlin diagnostics and app tests, static analysis, locale lint, architecture checks and independent review.
- Record remote CI, Android artifact and device acceptance separately from local checks.

## Ownership

- Native writer owns native DNS parser, diagnostic adapters, runner, native observation types/mapping and tests in a separate worktree. No lockfile, schema version or golden changes.
- Tools writer owns Kotlin dpi DNS tools, probe contracts and their tests in a separate worktree.
- UI writer owns app DNS evidence mapping/cards/tests and all locale files in a separate worktree.
- Integration writer owns Kotlin shared DNS evidence contracts, export/privacy integration, task/spec/docs and integration. Gradle runs are serialized; shared contracts are agreed before writing.
