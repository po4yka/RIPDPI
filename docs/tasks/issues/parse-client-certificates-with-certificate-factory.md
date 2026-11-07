---
id: AND-1794036563799026
title: Parse client certificates with CertificateFactory
kind: bug
status: todo
area: android
priority: high
owner: Standard-library migration
parent: EPC-1794036260770217
blocked_by: []
spec_mode: required
openspec_change: parse-client-certificates-with-certificate-factory
created: 2026-11-07
updated: 2026-11-07
---

## Goal

Certificate import must accept valid DER and PEM bytes without first requiring UTF-8 text.

## Audit evidence

Audit finding 3 (attachment 3), 2026-11-07, baseline 202f970bb3574980dd2eb87fea002cac8fbe29f7. This is a confirmed replacement candidate, not proof that an implementation is complete. Recheck callers and tests before editing.

## Scope

Pass bounded bytes to the platform X.509 CertificateFactory. Remove certificate regex and manual Base64 input decoding; keep PEM output normalization.

Keep the 256 KiB input limit, explicit empty or invalid certificate rejection, certificate order, and existing private-key and PKCS12 import contracts.

## Acceptance criteria

- The same certificate imported from binary DER and PEM produces equivalent normalized certificate bytes.
- Oversized, empty, and malformed documents fail explicitly before storage; errors contain no key or certificate payload.
- An ordered PEM chain retains its certificate order; private-key UTF-8 checks and PKCS12 behavior stay intact.
- Extend MasqueClientCredentialImporterTest with independent DER and PEM certificate fixtures, multiple certificates, malformed bytes, empty input, and size boundaries.
- Named local gates and repository-required independent review pass; record exact implementation SHA and command results. Commit each complete atomic implementation unit on main.

## Dependencies and order

Priority band 1; recommended position 3 in the epic queue. Prerequisites: None. Respect the epic priority and shared-file queue.

No dependency is implied by priority alone. Shared Cargo and Gradle files use one writer; the epic's queue prevents concurrent edits without introducing false semantic blockers.

## Ownership

- `app/src/main/kotlin/com/poyka/ripdpi/security/MasqueClientCredentialImporter.kt`

The same writer owns related module tests, direct dependency declarations, and task artifacts. Shared dependency and generated-output lanes follow the epic. The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

## Dependency decision

Use only existing platform or workspace libraries. Check the direct module dependency before editing.

## Verification

- `./gradlew :app:testGithubFullDebugUnitTest --tests "*MasqueClientCredentialImporterTest"`
- `./gradlew staticAnalysis`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
