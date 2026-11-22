---
id: DGN-1795322193627833
title: Verify a bounded manual HTTPS upload
kind: feature
status: review
area: diagnostics
priority: high
owner: Diagnostics
parent: null
blocked_by: []
spec_mode: required
openspec_change: verified-diagnostic-upload
created: 2026-11-22
updated: 2026-11-22
---

## Goal

Run one manual HTTPS upload and verify the byte count and SHA-256 reported by the control server.

## Acceptance criteria

- The diagnostic sends at most 1 MiB of locally generated data to the URL chosen by the user.
- A bounded, complete HTTPS receipt must confirm the exact size and digest. A missing, incorrect, or stalled receipt cannot confirm the transfer.
- Android tests cover manual admission. A trusted local HTTPS peer checks the request body and exercises success and failure receipts.

## Ownership

- Android implementation owns the diagnostics UI, Kotlin admission, bundled profiles, and locale resources and their export.
- Native implementation owns Rust, cross-platform contract fixtures, and validation on the combined tree.
