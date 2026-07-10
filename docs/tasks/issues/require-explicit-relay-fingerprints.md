---
title: Require explicit relay TLS fingerprints
type: task
status: doing
area: relay
priority: high
owner: Codex relay wire-contract coordinator
parent: null
blocks: []
blocked_by: []
created: 2026-07-10
updated: 2026-07-10
---

## Goal

Make every relay native-config TLS fingerprint field explicit, reject sparse payloads that omit a top-level, chain-hop, or ShadowTLS-inner fingerprint, and bump the Kotlin and Rust schema versions together as an intentional compatibility break.

## Ship definition

- Kotlin serializers always emit every required relay fingerprint field.
- Rust rejects current-schema payloads that omit any required fingerprint field.
- The prior relay schema version is rejected explicitly on both sides.
- Focused Kotlin and Rust contract tests plus broader relay gates pass.
