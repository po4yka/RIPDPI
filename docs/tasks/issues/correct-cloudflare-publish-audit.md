---
id: RLY-1794300683762457
title: Correct Cloudflare publish relay audit defects
kind: bug
status: doing
area: relay
priority: high
owner: Relay audit coordinator
parent: null
blocked_by: []
spec_mode: required
openspec_change: rly14-cloudflare-publish-fixes
created: 2026-11-10
updated: 2026-11-10
---

## Goal

Correct the six RLY-14 audit defects in the native origin, publish configuration, and connection editor. Preserve TCP-only scope and existing authentication.

## Acceptance criteria

- Streaming POST remains open until upload or session termination and closes on cancellation.
- Malformed credentials cannot enter exception messages or cause chains.
- Origin path queries do not alter the matched path.
- UDP and Mux commands fail before authentication and socket creation.
- IPv6 loopback is accepted; non-loopback and invalid ports are rejected.
- Both Cloudflare modes require a VLESS identity in the editor.
- Regression tests, independent review, and affected source gates pass.

## Parallel ownership

- Streaming worker: origin `http_server.rs`, `http_body.rs`, and their tests.
- Origin input worker: origin `path.rs`, `session.rs`, and their tests; two separate fixes.
- Kotlin worker: `CloudflarePublishConfig.kt`, `ConnectionDraftValidationSupport.kt`, and their owning tests; three separate fixes.
- Coordinator: task/OpenSpec files, integration evidence, and combined validation.
- No worker owns dependency manifests, lockfiles, locales, schemas, baselines, or golden fixtures. Changes in those lanes require coordinator review.
- Writers use separate worktrees. Only the coordinator updates shared planning files.
