---
id: DGN-1795610818483818
title: Keep typed error kinds for HTTP probe setup failures
kind: chore
status: backlog
area: diagnostics
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

`native/rust/crates/ripdpi-diagnostics-http/src/http/request_error.rs#HttpRequestError` keeps only text for transport and TLS setup errors, so classification falls back to text matching.

## Acceptance criteria

Transport and TLS setup errors keep a typed kind up to `HttpRequestError`, the text fallback is deleted, and existing classification tests stay green.
