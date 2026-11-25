---
id: DNS-1795610470133161
title: Replace the deprecated DnsResolver lookup in HttpsRrResolver
kind: chore
status: backlog
area: dns
priority: low
owner: comment-ratchet
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-25
updated: 2026-11-25
spec_reason: mechanical-refactor
---

## Goal

`core/diagnostics` `dpi/HttpsRrResolver.kt` calls the deprecated `DnsResolver.getInstance()`. A deleted `TODO(author)` said that it needs Context plumbing.

## Acceptance criteria

The resolver gets what it needs through injection, the deprecated call and its suppression are removed, and the HTTPS RR tests stay green.
