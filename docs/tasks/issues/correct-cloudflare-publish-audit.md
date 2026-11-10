---
id: RLY-1794300683762457
title: Correct Cloudflare publish relay audit defects
kind: bug
status: done
area: relay
priority: high
owner: Relay audit coordinator
parent: null
blocked_by: []
spec_mode: required
openspec_change: rly14-cloudflare-publish-fixes
created: 2026-11-10
updated: 2026-11-10
closed_at: "2026-11-10T12:01:19Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Source 2215d745483395d636512589176fdbbe3168ffcb passed 30 origin and 115 selected Kotlin tests, clippy, fmt, locked metadata, just ci-gates, full staticAnalysis, independent review and local pinned cloudflared HTTP/2 regression in the rly14 lane; external edge and Android artifact acceptance were not run.
---

## Goal

Correct the six RLY-14 audit defects in the native origin, publish configuration, and connection editor. Preserve TCP-only scope and existing authentication.

## Acceptance criteria

- Streaming POST remains open until upload or session termination and closes on cancellation.
- Malformed credentials cannot enter exception messages or cause chains.
- Origin path queries do not alter the matched path.
- UDP and Mux commands fail before authentication callbacks and socket creation.
- IPv6 loopback is accepted; non-loopback and invalid ports are rejected.
- Both Cloudflare modes require a VLESS identity in the editor.
- Regression tests, independent review, and affected source gates pass.

## Parallel ownership

- Streaming worker: origin `http_server.rs`, `http_body.rs`, and their tests.
- Origin input worker: origin `path.rs`, `session.rs`, and their tests; two separate fixes.
- Kotlin worker: `CloudflarePublishConfig.kt`, `ConnectionDraftValidationSupport.kt`, and their owning tests; three separate fixes.
- Coordinator: task/OpenSpec files, integration evidence, combined validation, and the mechanical reserve-refresh fixture naming correction and Apps Script certificate-size constant extraction required by the integration detekt failures.
- Kotlin worker also owns behavior-preserving guard simplification in `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/subscription/{SingBoxSubscriptionParser,XrayConfigImportParser}.kt` and their existing parser tests. TLS requirements, check order, rejection reasons, and profile values must remain unchanged.
- Kotlin worker also owns the focused `app/src/test/kotlin/com/poyka/ripdpi/ui/screens/connection/ConnectionCloudflareValidationTest.kt`; move only the two new Cloudflare regressions out of the large ViewModel test class.
- No worker owns dependency manifests, lockfiles, locales, schemas, baselines, or golden fixtures. Changes in those lanes require coordinator review.
- Writers use separate worktrees. Only the coordinator updates shared planning files.
