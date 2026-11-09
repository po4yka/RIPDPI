---
id: TRN-1794199027304873
title: Correct STD-04 hybrid TLS audit findings
kind: bug
status: review
area: transport
priority: medium
owner: STD-04 integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: std04-hybrid-tls-audit-fixes
created: 2026-11-09
updated: 2026-11-09
---

## Goal

Correct findings F1-F4 in the 2026-11-09 STD-04 audit. Preserve successful hybrid exchange, classical fallback, REALITY admission rules, and current TLS packet fixtures.

## Acceptance criteria

- Invalid hybrid server shares produce the RFC 10024 alert on the wire.
- Profile metadata states configuration validation without a false wire-size guarantee.
- REALITY KEM override documentation states its mandatory hybrid/X25519 prefix.
- Fake-profile provenance identifies the legacy draft group by its correct name and codepoint.
- Each finding has its own fix commit. Targeted tests, independent review, and integration gates pass before main is pushed.

## Ownership

- Parent integration owns this portfolio record, all OpenSpec artifacts, the generated board, review, combined validation, commits, main integration, and push.
- F1 worker owns vendor/boring-sys/deps/boringssl/ssl/extensions.cc, the vendor notes, and a new ripdpi-tls-profiles integration test for hybrid peer alerts.
- F2 worker owns ripdpi-tls-profiles metadata and its existing tests, directly affected Kotlin test samples, and the live status claims in ripdpi-ws-tunnel/README.md plus .agents/skills/ws-tunnel-telegram/SKILL.md and .agents/skills/desync-engine/SKILL.md. It does not change wire bytes, fixtures, baselines, or F1 tests.
- Documentation worker owns ripdpi-vless/src/config.rs rustdoc, the module documentation in ripdpi-tls-profiles/src/pq_kem.rs, and ripdpi-packets/src/fake_profiles/PROVENANCE.md. It prepares F3 and F4 as separate reviewed diffs.
- Every writer uses a separate worktree. Only the parent writes tasking/spec artifacts. No writer changes Cargo.lock, versions, schemas, golden fixtures, architecture baselines, or locale resources.

## Evidence scope

The audit is /Users/npochaev/Downloads/RIPDPI_network_audits/STD-04.md at source SHA 7b475fb9e1b433c80daee34db6cd2ec6b77f23a3. Acceptance uses host loopback and source gates. Physical devices, external servers, release artifacts, hosted CI, and deployment are outside this change.
